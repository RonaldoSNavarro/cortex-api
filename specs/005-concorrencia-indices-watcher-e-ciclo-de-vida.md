---
spec: 005
titulo: Condições de corrida em CortexConfigManager, ProjectWatcherService, índices em memória e ausência de shutdown gracioso de recursos
status: implementada
atualizado: 2026-10-18
---
# 005 — Condições de corrida em CortexConfigManager, ProjectWatcherService, índices em memória e ausência de shutdown gracioso de recursos

## Objetivo
Eliminar condições de corrida na leitura/escrita de `~/.cortex/config.yaml`, no registro de watchers de arquivos e nas consultas concorrentes ao índice léxico BM25 e ao grafo de conhecimento, garantindo também o fechamento gracioso de executores e recursos no encerramento da aplicação.

## Contexto
Originada do Code Review completo registrado em [docs/code_review.md](file:///mnt/g/Dev/Projetos/cortex/docs/code_review.md) em `2026-10-17` (Achados **#5, #7, #8 e #10**):

1. **Achado #5 — Condição de corrida e duplicação de chaves em `CortexConfigManager`** (`CortexConfigManager.java:12-38`, `ProjectRepository.java:674, 738`, `CommandController.java:22`):
   - `CortexConfigManager` é anotado com `@Service`, mas `ProjectRepository` e `CommandController` o instanciam manualmente com `new CortexConfigManager()`.
   - O ciclo `loadConfig()` → mutação → `saveConfig()` em `bootstrap()` não possui sincronização nem escrita atômica (`ATOMIC_MOVE`), permitindo perda de registros quando dois projetos executam `bootstrap` concorrentemente.
   - O mapa `projects` em `config.yaml` armazena `rootPath -> projectId`. Quando o mesmo `projectId` (ex.: `"cortex"`) é registrado com caminhos equivalentes em ambientes distintos (ex.: `/mnt/g/Dev/Projetos/cortex` e `/projetos/cortex`), entradas antigas para o mesmo `projectId` permanecem órfãs no `HashMap`, tornando a busca reversa em `resolveTargetFileForProject` (`ProjectRepository.java:677`) não determinística.
2. **Achado #7 — `check-then-act` e remoção incorreta de sufixo em `ProjectWatcherService`** (`ProjectWatcherService.java:27-94`):
   - `if (projectWatchers.containsKey(projectId)) return;` seguido de `projectWatchers.put(projectId, watchService)` não é atômico: chamadas simultâneas a `getIndex(projectId)` para um projeto ainda não carregado podem instanciar múltiplos `WatchService` e múltiplas Virtual Threads.
   - No evento `ENTRY_DELETE` (`ProjectWatcherService.java:73`), `filename.toString().replace(".md", "")` remove todas as ocorrências da substring `".md"` no nome do arquivo em vez de remover apenas a extensão final `.md`.
3. **Achado #8 — Leitura sem sincronização durante re-indexação em `InMemoryLexicalIndex` e `KnowledgeGraph`** (`InMemoryLexicalIndex.java:32-185`, `KnowledgeGraph.java:26-142`):
   - Em `InMemoryLexicalIndex`, `indexPage()`, `removePage()` e `clear()` são `synchronized`, mas `search()` não é sincronizado. Como `indexPage(page)` começa chamando `removePage(page.getId())`, uma thread executando `search()` simultaneamente pode observar a página ausente ou ler `pagesById` já populado enquanto `docTermFrequencies` ainda é `null`.
   - Em `KnowledgeGraph`, `indexPage()` armazena um `HashSet` mutável em `outlinks.put(pageId, pageOutlinks)` (`linha 55`), e métodos de leitura (`getBacklinks`, `getOutlinks`, `getImpactedPagesOnSupersede`, `getTotalNodes`, `getTotalEdges`, `getTopReferenced`) acessam as coleções sem sincronização com `indexPage`/`removePage`.
4. **Achado #10 — Ausência de `@PreDestroy` em executores e servidor MCP** (`McpConfig.java:67-72`, `ProjectRepository.java:53`):
   - `McpConfig.virtualThreadExecutor`, `McpConfig.mcpServer` e `ProjectRepository.gitSyncExecutor` não possuem métodos anotados com `@PreDestroy` para encerrar recursos de forma limpa no shutdown do contexto Spring.

## Comportamento esperado
1. **Gerenciamento atômico e determinístico de `config.yaml`**:
   - `CortexConfigManager` deve prover operações thread-safe para leitura, atualização e persistência atômica (gravando em arquivo temporário no mesmo diretório e movendo para `config.yaml`).
   - Ao registrar um `projectId` com um novo `rootPath` em `bootstrap()`, quaisquer chaves anteriores que apontem para o mesmo `projectId` devem ser substituídas pela nova entrada canônica, garantindo mapeamento `1:1` entre `projectId` e seu `rootPath` ativo.
2. **Registro atômico e remoção precisa no `ProjectWatcherService`**:
   - `registerProject` deve garantir atomicamente que apenas um `WatchService` e uma Virtual Thread sejam criados por `projectId`.
   - No evento `ENTRY_DELETE`, apenas o sufixo final `.md` deve ser removido para compor o `id` da página removida.
3. **Consistência de leitura/escrita em `InMemoryLexicalIndex` e `KnowledgeGraph`**:
   - Leituras (`search` no índice; `getBacklinks`, `getOutlinks`, `getImpactedPagesOnSupersede`, `getTotalNodes`, `getTotalEdges`, `getTopReferenced` no grafo) nunca devem enxergar o estado intermediário entre `removePage` e a conclusão de `indexPage`.
4. **Encerramento gracioso (`@PreDestroy`)**:
   - `ProjectRepository` e `McpConfig` devem expor métodos `@PreDestroy` que fechem seus `ExecutorService` (e o `McpSyncServer`, quando inicializado) sem lançar exceções.

## Critérios de aceite
> Cada item é uma afirmação verificável — algo que alguém pode reprovar.

- **CA-1:** Dez chamadas concorrentes a `bootstrap` registrando 10 projetos distintos resultam nos 10 projetos presentes em `~/.cortex/config.yaml`, sem perda de entradas nem corrupção de YAML.
- **CA-2:** Executar `bootstrap` para o mesmo `projectId` primeiro com um caminho antigo e depois com um novo caminho válido substitui o registro anterior em `~/.cortex/config.yaml`, mantendo exatamente uma entrada associada àquele `projectId`.
- **CA-3:** Vinte chamadas concorrentes a `ProjectWatcherService.registerProject` para o mesmo `projectId` criam exatamente um `WatchService` ativo para esse projeto.
- **CA-4:** Quando um arquivo cujo ID contém `.md` no meio do identificador (ex.: `nota.md.v1.md`) é removido do diretório monitorado pelo `ProjectWatcherService`, o ID desindexado é exatamente `nota.md.v1` (removendo apenas a extensão final `.md`).
- **CA-5:** Durante 100 atualizações repetidas (`indexPage`) de uma mesma página em `InMemoryLexicalIndex` em paralelo com 100 chamadas concorrentes a `search` pelo termo dessa página, 100% das chamadas a `search` retornam a página (nenhuma leitura observa a página momentaneamente removida).
- **CA-6:** Durante 100 atualizações repetidas (`indexPage`) de uma página com wikilink `[[alvo]]` em `KnowledgeGraph` em paralelo com 100 chamadas concorrentes a `getBacklinks("alvo")` e `getOutlinks(pageId)`, 100% das leituras retornam o vínculo esperado sem `ConcurrentModificationException` ou conjunto vazio transitório.
- **CA-7:** Invocar o ciclo de encerramento (`@PreDestroy`) de `ProjectRepository` e `McpConfig` encerra seus executores (`isTerminated()` ou `isShutdown()` igual a `true`) sem lançar exceção.

## Fora de escopo
- Validações de Path Traversal, escape de regex em `promoteRules` e drenagem de pipes do Git — tratadas na [Spec 004](004-seguranca-integridade-e-git-core.md).
- Mudanças de CLI, `Dockerfile` e Javadoc — tratadas na [Spec 006](006-alinhamento-cli-docker-hardening-e-padroes-spring.md).

## Invariantes tocadas
- **Princípios de Memória LLM Wiki #3 e #4 (Índices Simples e Sem Infraestrutura Desnecessária)**: mantém os índices léxico e de grafo 100% em memória no próprio processo Java, garantindo consistência sob acesso concorrente via Virtual Threads sem adicionar bancos externos.

## Testes que cobrem esta spec
- `cortex-core/src/test/java/contract/Spec005ConcorrenciaIndicesWatcherTest.java` → CA-1, CA-2, CA-3, CA-4, CA-5, CA-6, CA-7 (a ser criado na implementação).

## Notas
- Spec criada em estado `rascunho` a partir dos achados #5, #7, #8 e #10 do [docs/code_review.md](file:///mnt/g/Dev/Projetos/cortex/docs/code_review.md).
