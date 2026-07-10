# Cortex — sistema de memória + wiki pessoal (Java) — Documento de requisitos

**Escopo definido:** plataforma única combinando o padrão *LLM Wiki* (Karpathy) com o padrão *ai-memory* (Akita), servida como servidor MCP para Claude Code e Antigravity, com captura automática via hooks. MVP simples: arquivos markdown em disco, sem banco de dados ou infraestrutura pesada.

---

## 1. Visão geral

O sistema tem dois papéis que se sobrepõem:

1. **Wiki de conhecimento** (Karpathy): uma base de conhecimento em markdown que o próprio agente de IA mantém e vai enriquecendo ao longo do tempo — fontes brutas entram, o agente sintetiza e cura páginas, um índice e um log registram tudo.
2. **Memória de agente de código** (Akita): captura contínua de decisões, fatos, regras e "pegadinhas" (gotchas) descobertas durante sessões de desenvolvimento, com possibilidade de handoff entre agentes diferentes (Claude Code ↔ Antigravity) sem perder contexto.

No seu caso, isso serve tanto para conhecimento geral (ex.: regulação de consórcios, BACEN) quanto para memória de projeto (ex.: decisões de arquitetura do seu sistema de consórcio).

## 2. Conceitos herdados de cada referência

**Referências originais:**
- LLM Wiki (Karpathy): https://gist.github.com/karpathy/442a6bf555914893e9891c11519de94f
- ai-memory (Akita): https://github.com/akitaonrails/ai-memory

| Conceito | Origem | Como entra no seu sistema |
|---|---|---|
| `index.md` (catálogo) | Karpathy | Página que lista todas as páginas da wiki por tópico |
| `log.md` (registro cronológico) | Karpathy | Log append-only de tudo que foi ingerido/capturado |
| `schema.md` (convenções) | Karpathy | Define o front-matter e os tipos de página — vira também a *skill* que orienta o agente |
| Operações Ingest / Query / Lint | Karpathy | Comandos da CLI e ferramentas MCP equivalentes |
| Tipos de memória: fact, decision, rule, gotcha | Akita | Front-matter `type:` em cada página |
| Captura via hooks | Akita | Hooks do Claude Code e do Antigravity gravando em `raw/` |
| Handoff entre agentes | Akita | Ferramenta MCP `handoff` gerando resumo consolidado |
| Escopo por projeto | Akita | Um diretório de wiki isolado por projeto |
| Roteamento de regras duráveis para CLAUDE.md/AGENTS.md | Akita | Ferramenta `promote_rule` |
| Cadeia de supersessão | Akita | Campo `supersedes` no front-matter, nunca apaga, só substitui |

## 3. Escopo do MVP (dentro / fora)

**Dentro do MVP:**
- Armazenamento 100% em arquivos markdown (sem SQLite, sem embeddings, sem vetor de busca)
- Busca textual simples (grep/keyword) sobre os arquivos
- Servidor MCP em Java via stdio, com as ferramentas essenciais
- CLI Java para uso manual (init, ingest, query, lint, consolidate)
- Hooks para Claude Code (SessionStart, Stop, PreCompact)
- Hooks equivalentes para Antigravity (a confirmar contrato exato — ver seção 13)
- Isolamento por projeto
- Promoção de regras para CLAUDE.md/AGENTS.md

**Fora do MVP (fica para depois):**
- Busca híbrida com embeddings/vetores
- Banco de dados (SQLite FTS5, Lucene) — mencionado como evolução futura
- Interface web de navegação (o Akita tem um `/web`; aqui fica para uma fase 2)
- Multiusuário/autenticação
- Retenção/decay automático de memórias antigas
- Sincronização remota/nuvem

## 4. Arquitetura

Camadas, de cima para baixo:

- **Agentes de IA** — Claude Code e Antigravity, cada um com sua própria configuração de hooks e MCP.
- **Captura** — dois caminhos paralelos: hooks automáticos (sem intervenção sua) e CLI manual (quando você quer ingerir um artigo ou fazer uma consulta direta).
- **Servidor MCP (Java)** — núcleo do sistema. Agora embutido diretamente na aplicação `cortex-api` (Spring Boot). Expõe as ferramentas que os agentes usam durante a conversa (capturar, consultar, consolidar, promover regra, lint) via Server-Sent Events (SSE) através do endpoint HTTP `/mcp/sse`.
- **Wiki em arquivos `.md`** — a fonte da verdade. Legível e editável por humano, versionável em git.

O diagrama acima nesta conversa resume esse fluxo. Um ponto importante: a consolidação (transformar observações brutas em páginas curadas) **não é determinística** — é o próprio agente, usando a ferramenta `consolidate`, que decide o que vale a pena promover para a wiki curada. Isso é o núcleo da ideia do Karpathy: a LLM mantém a wiki, não um script.

## 5. Estrutura de diretórios proposta

```
~/.cortex/
  projects/
    <project-id>/
      schema.md          # convenções: tipos de página, front-matter, regras de nomeação
      index.md            # catálogo de páginas por tópico
      log.md               # log cronológico append-only (o que foi capturado, quando)
      raw/                  # capturas brutas, imutáveis (uma por sessão/evento)
        2026-07-06-session-abc123.md
      pages/                # páginas curadas (resultado da consolidação)
        sorteio-algoritmo-pedra-chave.md
        decisao-arquitetura-testes-frontend.md
      rules/                # regras duráveis já promovidas (espelho do que foi injetado)
        active.md
  config.yaml              # mapeamento project-id -> pasta do projeto de código real
```

Cada projeto de código real (ex.: seu sistema de consórcio) aponta, via `config.yaml`, para um `<project-id>` aqui.

O CLI Java se chama `cortex` (ex.: `cortex init`, `cortex query "pedra-chave"`), e o servidor MCP é registrado nos agentes como `cortex-mcp`.

## 6. Modelo de dados

Front-matter YAML padrão em toda página de `pages/` e `raw/`:

```yaml
---
id: "01J..."              # identificador único (ULID/UUID)
type: fact | decision | rule | gotcha | note
project: consorcio-brasil
tags: [sorteio, ago, apuracao]
status: active | superseded
supersedes: null          # id de um registro anterior, se aplicável
source: session:abc123 | manual | ingest:<url>
created_at: 2026-07-06T14:30:00-03:00
updated_at: 2026-07-06T14:30:00-03:00
---
```

**Tipos de memória:**
- `fact` — algo verificável e estável (ex.: "a Resolução BCB 362/2023 está em vigor desde julho de 2024")
- `decision` — uma escolha de arquitetura/produto e o motivo
- `rule` — algo que deve sempre ser seguido pelo agente (candidato a `promote_rule`)
- `gotcha` — uma armadilha já descoberta (ex.: "o cálculo de apuração de lance embutido trata FGTS separadamente")
- `note` — conhecimento geral de wiki, sem um dos tipos acima

## 7. Requisitos funcionais

| ID | Requisito |
|---|---|
| RF-01 | CLI Java com comandos `init`, `ingest`, `query`, `lint`, `consolidate` |
| RF-02 | Servidor MCP (stdio) expondo `capture`, `query`, `consolidate`, `write_page`, `promote_rule`, `lint`, `bootstrap`, `handoff` |
| RF-03 | Hooks do Claude Code (`SessionStart`, `Stop`, `PreCompact`) capturando contexto de sessão em `raw/` sem intervenção manual |
| RF-04 | Hooks equivalentes configuráveis para Antigravity, com o mesmo contrato de dados de captura |
| RF-05 | `SessionStart` injeta de volta no agente (via `additionalContext`) as regras ativas e decisões recentes do projeto |
| RF-06 | Suporte aos tipos de memória `fact`, `decision`, `rule`, `gotcha`, `note` |
| RF-07 | Isolamento por projeto — múltiplos projetos simultâneos, cada um com sua própria wiki |
| RF-08 | `promote_rule` injeta regras duráveis em `CLAUDE.md`/`AGENTS.md` na raiz do projeto de código |
| RF-09 | Cadeia de supersessão — `supersedes` mantém histórico, nunca apaga um registro anterior |
| RF-10 | `lint` detecta: front-matter inválido, links internos quebrados, duplicatas/conflitos, páginas órfãs |
| RF-11 | Toda escrita fica pronta para versionamento git (arquivos texto, sem lock binário) |
| RF-12 | Consolidação é assistida por LLM — o agente decide o que promover, guiado pelo `schema.md`/skill |
| RF-13 | Busca textual simples (keyword) sobre os arquivos markdown, sem dependência externa |
| RF-14 | `handoff` gera resumo consolidado do estado do projeto para reidratar outro agente |

## 8. Requisitos não funcionais

| ID | Requisito |
|---|---|
| RNF-01 | Zero infraestrutura externa obrigatória — só JVM + arquivos |
| RNF-02 | Portável entre Linux, macOS e Windows |
| RNF-03 | Arquivos sempre legíveis/editáveis por humano (markdown puro) |
| RNF-04 | Hooks de baixa latência — usar execução assíncrona quando disponível, para não travar o agente |
| RNF-05 | Uso pessoal single-user, sem autenticação nesta fase |
| RNF-06 | *Fail-open*: se o servidor MCP estiver indisponível, os hooks não podem quebrar a sessão do agente |
| RNF-07 | Arquitetura em camadas (storage / domínio / mcp-tools / cli / adaptadores de hooks) para trocar o backend de busca depois sem reescrever tudo |

## 9. Contratos das ferramentas MCP

| Ferramenta | Entrada | Saída | Descrição |
|---|---|---|---|
| `capture` | project, type, content, tags?, source? | id, path | Grava observação bruta em `raw/` (append-only) |
| `query` | project, terms, type?, limit? | lista de {id, title, snippet, path, type} | Busca textual em `pages/` (e opcionalmente `raw/`) |
| `consolidate` | project, since?, raw_ids? | lista de observações brutas ainda não consolidadas | O agente revisa e decide o que promover |
| `write_page` | project, page_path, content, type, supersedes? | id, path | Cria/atualiza página curada respeitando o schema |
| `promote_rule` | project, rule_content, target (CLAUDE.md \| AGENTS.md) | path | Injeta regra durável no arquivo de regras |
| `lint` | project | lista de issues | Roda verificações de consistência |
| `bootstrap` | project_name, root_path | project_id, path | Inicializa a estrutura de um novo projeto |
| `handoff` | project, target_agent | summary_path, summary_markdown | Gera resumo para troca de agente |

## 10. Hooks necessários

### Claude Code (confirmado na documentação atual)

| Evento | Uso no sistema |
|---|---|
| `SessionStart` (source: startup/resume) | Lê `index.md`/regras ativas e injeta como `additionalContext` |
| `Stop` | Captura resumo da sessão via `capture` (cuidado com loop: checar `stop_hook_active`) |
| `PreCompact` | Faz backup do essencial antes da compactação de contexto |
| `SessionEnd` (opcional) | Log de encerramento, sem bloquear nada |

Use `async: true` nos hooks que não precisam bloquear o agente (ex.: `Stop`, `SessionEnd`).

### Antigravity (a validar na hora da implementação)

A Antigravity (IDE, CLI e SDK da Google) suporta hooks de ciclo de vida (início/fim de sessão, pré-chamada de ferramenta, recuperação de erro) e servidores MCP via stdio ou HTTP/SSE, mas a nomenclatura exata dos eventos é uma plataforma nova e ainda em movimento rápido — vale checar a documentação oficial da Antigravity no momento de implementar essa parte, em vez de assumir paridade total com os nomes de eventos do Claude Code. O contrato de dados (o que é gravado em `raw/`) deve ser o mesmo dos hooks do Claude Code, para manter os dois agentes compatíveis com a mesma wiki.

## 11. Stack técnica Java sugerida

- **Java 21** (LTS) + Maven ou Gradle — sugestão de artifactId: `cortex` (ex.: `cortex-core`, `cortex-mcp`, `cortex-cli` como módulos, se optar por multi-módulo)
- **MCP Java SDK oficial** (`io.modelcontextprotocol.sdk:mcp`, mantido em colaboração com a Spring AI) — já traz `StdioServerTransportProvider` pronto, então você não precisa implementar JSON-RPC na mão
- **Picocli** para a CLI (comandos `init/ingest/query/lint/consolidate`)
- **Jackson** para parsing de front-matter YAML (ou `snakeyaml` diretamente)
- **CommonMark-java** ou similar para parsing/validação de markdown (útil no `lint`)
- Nada de banco de dados nesta fase — busca por varredura simples dos arquivos (`Files.walk` + grep)

⚠️ Detalhe importante do MCP sobre stdio: **nunca usar `System.out.println`** para logs — isso corrompe as mensagens JSON-RPC no stdout. Toda saída de log deve ir para stderr ou arquivo.

## 12. Roadmap incremental sugerido

1. **Fase 0 — esqueleto:** estrutura de diretórios, `schema.md`, CLI `init`/`ingest`/`query` operando só em arquivos, sem MCP ainda
2. **Fase 1 — servidor MCP:** expõe `capture`/`query`/`lint` via stdio, testado com Claude Code apontando para ele
3. **Fase 2 — hooks Claude Code:** `SessionStart` injetando contexto, `Stop` capturando resumo
4. **Fase 3 — consolidação assistida:** ferramenta `consolidate` + `write_page`, você pedindo ao agente para "consolidar a semana"
5. **Fase 4 — regras duráveis:** `promote_rule` escrevendo em CLAUDE.md/AGENTS.md
6. **Fase 5 — Antigravity:** replicar hooks e MCP para o segundo agente, validar handoff
7. **Fase 6 (futuro):** busca melhor (SQLite FTS5 ou Lucene), retenção/decay, interface web

## 13. Riscos e decisões em aberto

- **Antigravity é uma plataforma muito recente e em rápida mudança** — o formato exato de hooks pode diferir do que está descrito aqui; validar contra a documentação oficial antes de implementar a fase 5.
- **Loop de hooks `Stop`**: um hook que sempre recaptura pode, em tese, interferir no encerramento da sessão — seguir o padrão de checar `stop_hook_active` antes de agir.
- **Conflito de nomes entre wikis de projetos diferentes**: decidir se `<project-id>` é derivado do caminho do repositório ou escolhido manualmente no `bootstrap`.
- **Consolidação manual vs. automática**: no MVP, a consolidação é sempre disparada por você (via CLI ou pedindo ao agente). Automatizar isso (ex.: rodar toda vez que a sessão termina) é uma decisão de fase futura, com risco de gerar páginas de baixa qualidade sem revisão.

## 14. Skills necessárias

Aqui "skills" tem dois sentidos, e os dois importam:

### 14.1 Skill de convenções da wiki (para os agentes usarem em tempo real)

Esta é a peça mais importante: um `SKILL.md` que ensina qualquer agente (Claude Code, Antigravity ou você mesmo) a ler e escrever na wiki corretamente — os tipos de memória, o front-matter, quando promover uma regra, como funciona a supersessão. É essencialmente o `schema.md` do Karpathy, empacotado como skill para ser carregado automaticamente pelo Cortex. Rascunho no apêndice abaixo.

### 14.2 Competências técnicas a desenvolver

- **MCP em Java**: entender a diferença entre `McpSyncServer`/`McpAsyncServer`, transporte stdio, definição de `Tool` com JSON Schema
- **Hooks do Claude Code**: contrato de entrada/saída via stdin/stdout, exit codes (0 = ok, 2 = bloqueia), `additionalContext`
- **Extensibilidade da Antigravity**: MCP, Skills, Hooks e o mecanismo de "Rules" (equivalente a um AGENTS.md composável)
- **Parsing de front-matter YAML** e markdown em Java
- **Design de CLI** com Picocli (comandos, subcomandos, saída amigável)

### Apêndice — rascunho do SKILL.md de convenções da wiki

```markdown
---
name: cortex-conventions
description: Convenções de leitura e escrita da wiki pessoal de memória (Cortex). Use sempre que precisar registrar uma decisão, fato, regra ou gotcha do projeto, consultar conhecimento já registrado, ou consolidar observações brutas em páginas curadas. Aciona também ao promover uma regra durável para CLAUDE.md/AGENTS.md.
---

# Convenções do Cortex

## Tipos de memória
- `fact`: algo verificável e estável
- `decision`: uma escolha e o motivo
- `rule`: algo que deve sempre ser seguido (candidato a promote_rule)
- `gotcha`: uma armadilha já descoberta
- `note`: conhecimento geral sem um tipo específico

## Antes de escrever uma página nova
1. Rode `query` para checar se já existe algo relacionado
2. Se existir e o novo conteúdo o substitui, use `supersedes` em vez de editar o antigo
3. Front-matter é obrigatório em toda página (ver schema completo no requisitos)

## Quando promover uma regra
Só use `promote_rule` para algo que deve valer em toda sessão futura, não para decisões pontuais. Prefira `rule` normal na wiki quando houver dúvida.

## Consolidação
Ao consolidar, leia as observações brutas de `raw/` desde a última consolidação, agrupe por tópico, e escreva páginas curadas com `write_page`. Não apague o raw original.
```

---

*Próximo passo sugerido: começar pela Fase 0 (esqueleto + CLI), sem MCP ainda, para validar a estrutura de dados antes de conectar aos agentes.*
