---
spec: 004
titulo: Corrupção em promote_rules, vulnerabilidade de path traversal, bloqueio de subprocesso Git e inconsistências de formatação Markdown/YAML no core
status: implementada
atualizado: 2026-10-18
---
# 004 — Corrupção em promote_rules, vulnerabilidade de path traversal, bloqueio de subprocesso Git e inconsistências de formatação Markdown/YAML no core

## Objetivo
Garantir que operações de leitura, escrita, promoção de regras, parsing YAML/Markdown e sincronização Git no `cortex-core` preservem a integridade literal dos arquivos `.md`, bloqueiem travessia de diretórios (`Path Traversal`) e não travem em buffers de pipe de subprocessos Git.

## Contexto
Originada do Code Review completo registrado em [docs/code_review.md](file:///mnt/g/Dev/Projetos/cortex/docs/code_review.md) em `2026-10-17` (Achados **#1, #2, #3, #6, #11 e #12**):

1. **Achado #1 — Corrupção ou exceção em `promoteRules` com `$` ou `\`** (`ProjectRepository.java:646`):
   - Ao atualizar um arquivo `AGENTS.md` que já contém `<!-- BEGIN CORTEX RULES -->...<!-- END CORTEX RULES -->`, o método executa:
     ```java
     existingContent = existingContent.replaceAll("(?s)<!-- BEGIN CORTEX RULES -->.*?<!-- END CORTEX RULES -->\n?", rulesBlock.toString());
     ```
   - `String.replaceAll` interpreta `$` como referência a grupo de captura regex e `\` como caractere de escape no texto substituto. Regras reais do projeto contendo `${project.version}`, `$HOME` ou caminhos Windows `G:\Dev\Projetos` disparam `IllegalArgumentException: Illegal group reference` ou têm as barras invertidas suprimidas.
2. **Achado #2 — Risco de Path Traversal em identificadores** (`ProjectRepository.java:217, 369, 446`):
   - `getProjectDir(projectId)`, `updatePageStatus(projectId, pageId, ...)` e o loop de exclusão `consumedRawIds` em `writePage` concatenam strings diretamente em `Path.resolve(...)` sem validar separadores de caminho ou `..`. Um `consumed_raw_ids: ["../pages/pagina-ativa"]` apagararia uma página consolidada fora de `raw/`.
3. **Achado #3 — Bloqueio de pipe e falha em `git commit` sem alterações** (`ProjectRepository.java:937-983`):
   - `runCommand` chama `p.waitFor(...)` antes de ler `stdout`/`stderr`. Se o comando Git emitir mais de ~64 KB no pipe do sistema operacional, o subprocesso bloqueia até estourar o timeout de 15s.
   - `syncToGit` executa `git commit -m ...` incondicionalmente; quando não há mudanças staged ("nothing to commit, working tree clean"), o Git retorna exit code `1`, abortando a execução antes do `git push`.
   - `syncToGit` configura `StrictHostKeyChecking=no` (`ProjectRepository.java:954`), quando o padrão seguro do OpenSSH para conexões automatizadas não interativas é `StrictHostKeyChecking=accept-new`.
4. **Achado #6 — Formato divergente de `log.md` e `index.md`** (`ProjectRepository.java:478-495, 718-735`):
   - `bootstrap()` inicializa `log.md` como uma tabela Markdown (`| Data | Evento | ID |`), mas `appendLog()` acrescenta cabeçalhos `## [timestamp] write | ...`, corrompendo a tabela na primeira escrita.
   - `bootstrap()` inicializa `index.md` com o placeholder `_Nenhuma página consolidada ainda._`, e `updateIndex()` apenas faz `APPEND` de novas linhas abaixo dele sem remover o placeholder.
5. **Achado #11 — Recorte frágil de front-matter YAML** (`MarkdownParser.java:25-38`):
   - `MarkdownParser.parseString` usa `content.indexOf("---", startOfYaml + 3)`, que casa incorretamente com `---` contido no meio de um valor escalar do próprio YAML se não exigir delimitador de quebra de linha.
6. **Achado #12 — Divergência de regex de `[[wikilinks]]` no `lint()`** (`ProjectRepository.java:570` vs `KnowledgeGraph.java:15`):
   - `KnowledgeGraph` aceita espaços em wikilinks (`\[\[([a-zA-Z0-9_\-\s]+)\]\]`), mas `lint()` usa `\[\[([a-zA-Z0-9_\-]+)\]\]` (sem `\s`), deixando de auditar wikilinks com espaços.

## Comportamento esperado
1. **Substituição literal em `promoteRules`**:
   - Ao substituir um bloco existente delimitado por `<!-- BEGIN CORTEX RULES -->` e `<!-- END CORTEX RULES -->`, o conteúdo das regras deve ser escapado com `Matcher.quoteReplacement(...)` (ou substituído por recorte de índice), preservando literalmente qualquer ocorrência de `$`, `${...}` e `\`.
2. **Validação estrita contra Path Traversal**:
   - Identificadores `projectId`, `pageId` (em `supersedes` / `updatePageStatus`) e cada item de `consumedRawIds` devem ser validados contra o padrão seguro `^[a-zA-Z0-9._-]+$` (rejeitando `.` isolado, `..`, `/`, `\` e strings vazias com `IllegalArgumentException`) e o `Path` resolvido e normalizado (`normalize()`) deve permanecer estritamente dentro do diretório pai esperado (`startsWith`).
3. **Subprocesso Git resiliente**:
   - `runCommand` deve unificar `stderr` em `stdout` (`pb.redirectErrorStream(true)`) e drenar o stream de saída continuamente enquanto aguarda o término do processo, evitando deadlock por enchimento de buffer de pipe.
   - `syncToGit` só deve invocar `git commit` quando `git status --porcelain` indicar alterações pendentes, prosseguindo normalmente para `git push` caso já existam commits locais não enviados.
   - A configuração SSH inicial deve usar `ssh -o StrictHostKeyChecking=accept-new`.
4. **Formatação unificada de `index.md` e `log.md`**:
   - `updateIndex` deve remover o placeholder `_Nenhuma página consolidada ainda._` ao inserir a primeira página consolidada em `index.md` e não duplicar entradas para o mesmo `id`.
   - `bootstrap`, `createRaw` e `writePage` devem usar um único formato consistente em `log.md`.
5. **Front-matter YAML e `lint()` consistentes**:
   - `MarkdownParser.parseString` deve exigir que o delimitador inicial `---` esteja na primeira linha do documento e que o delimitador de fechamento `---` ocupe uma linha própria (`\R---(\R|$)`).
   - `ProjectRepository.lint()` deve usar a mesma expressão regular de wikilinks do `KnowledgeGraph` (suportando espaços internos e fazendo `trim()`).

## Critérios de aceite
> Cada item é uma afirmação verificável — algo que alguém pode reprovar.

- **CA-1:** Chamar `promoteRules` duas vezes consecutivas em um arquivo `AGENTS.md` quando uma regra ativa contém literalmente `${project.version}`, `$1` e `G:\Dev\Projetos\cortex` conclui sem exceção e preserva os três trechos intactos no arquivo final.
- **CA-2:** Chamar qualquer operação de `ProjectRepository` com `projectId` contendo `..`, `/` ou `\` (ex.: `"../outro"`, `"a/b"`, `"a\\b"`) lança `IllegalArgumentException` antes de tocar no sistema de arquivos.
- **CA-3:** Chamar `writePage` com `consumedRawIds` contendo `../pages/<id>` ou `supersedes` contendo `../raw/<id>` lança `IllegalArgumentException` e mantém os arquivos existentes em `pages/` e `raw/` intactos.
- **CA-4:** `runCommand` executando um comando que produz mais de 128 KB em `stdout`/`stderr` consome toda a saída e retorna sem bloqueio de pipe dentro do timeout configurado.
- **CA-5:** Executar `syncToGit` quando o repositório `.cortex` não possui mudanças pendentes na working tree (`git status --porcelain` vazio) não falha com erro de `git commit` e configura `core.sshCommand` com `StrictHostKeyChecking=accept-new` ao inicializar um repositório novo.
- **CA-6:** Após executar `bootstrap` seguido de `writePage`, o arquivo `index.md` contém o link da nova página e **não** contém mais o texto `_Nenhuma página consolidada ainda._`, e o arquivo `log.md` mantém todas as entradas (de `bootstrap`, `createRaw` e `writePage`) no mesmo formato estrutural.
- **CA-7:** `MarkdownParser.parseString` faz o parse correto de um arquivo Markdown cujo front-matter YAML possui um campo com valor contendo `---` no meio da linha (ex.: `source: "nota --- revisão"`), extraindo o YAML completo e o corpo Markdown sem truncamento.
- **CA-8:** `ProjectRepository.lint` detecta e reporta `[DANGLING WIKILINK]` para wikilinks contendo espaços internos (ex.: `[[pagina inexistente]]`) da mesma forma que para wikilinks sem espaços, mantendo não-regressão para páginas válidas.

## Fora de escopo
- Sincronização concorrente de estruturas em memória (`InMemoryLexicalIndex`, `KnowledgeGraph`, `ProjectWatcherService` e `CortexConfigManager`) — tratada na [Spec 005](005-concorrencia-indices-watcher-e-ciclo-de-vida.md).
- Mudanças nos subcomandos do `cortex-cli`, `Dockerfile` e injeção de dependências Spring — tratadas na [Spec 006](006-alinhamento-cli-docker-hardening-e-padroes-spring.md).

## Invariantes tocadas
- **Princípios de Memória LLM Wiki #1 e #3 (Unidade Atômica Markdown e Índices Simples)**: preserva arquivos `.md`, `index.md` e `log.md` íntegros, legíveis por humanos e livres de corrupção em substituições ou travessias de caminho.

## Testes que cobrem esta spec
- `cortex-core/src/test/java/contract/Spec004SegurancaIntegridadeGitCoreTest.java` → CA-1, CA-2, CA-3, CA-4, CA-5, CA-6, CA-7, CA-8 (a ser criado na implementação).

## Notas
- Spec criada em estado `rascunho` a partir dos achados #1, #2, #3, #6, #11 e #12 do [docs/code_review.md](file:///mnt/g/Dev/Projetos/cortex/docs/code_review.md).
