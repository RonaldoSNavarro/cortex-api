# Hooks, Handoff e Bootstrap — Especificação

## Visão Geral
Esta especificação cobre as ferramentas de ciclo de vida do Cortex que automatizam a inicialização de projetos (`bootstrap`), a transferência de contexto entre agentes (`handoff`), e a integração passiva com os ciclos de vida dos agentes de IA (hooks).

## 1. Ferramenta `bootstrap`

### Objetivo
Inicializar a estrutura completa de um novo projeto Cortex, incluindo a criação automática da Skill de convenções e o registro no `config.yaml`.

### Contrato

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `project_name` | string | sim | ID lógico do projeto (ex: `consorcio-brasil`) |
| `root_path` | string | sim | Caminho absoluto do repositório de código real |

### Saída
- `project_id` — ID atribuído ao projeto.
- `path` — Caminho da estrutura criada em `~/.cortex/projects/<project_id>/`.

### Comportamento
1. Cria a árvore `~/.cortex/projects/<project_name>/` com subdiretórios `raw/`, `pages/`, `rules/`.
2. Gera `schema.md` com as convenções padrão.
3. Gera `index.md` vazio (catálogo de páginas).
4. Gera `log.md` vazio (log cronológico append-only).
5. Registra o mapeamento `root_path → project_name` no `config.yaml`.
6. Ejeta a Skill de convenções em `<root_path>/.agents/skills/cortex-conventions/SKILL.md` se ela não existir.

### CLI
```
cortex bootstrap --project=<nome> --path=<caminho_do_repo>
```

---

## 2. Ferramenta `handoff`

### Objetivo
Gerar um relatório condensado do estado atual do projeto para reidratação rápida de outro agente (ou do mesmo agente em uma nova sessão). Funciona como um "resumo executivo" vivo da wiki.

### Contrato

| Campo | Tipo | Obrigatório | Descrição |
|---|---|---|---|
| `project` | string | sim | Project ID |
| `target_agent` | string | não | Nome do agente alvo (ex: `claude`, `antigravity`). Informativo. |

### Saída
- `summary_markdown` — Conteúdo Markdown do resumo gerado.

### Comportamento
1. Carrega todas as memórias ativas (status `active`) do projeto.
2. Agrupa por tipo (`rule`, `decision`, `fact`, `gotcha`, `note`).
3. Para cada tipo, lista as entradas com ID, data de criação e um snippet do conteúdo.
4. Gera um bloco final de estatísticas (total de memórias ativas, pendentes em `raw/`, regras promovidas).
5. Retorna o resumo formatado em Markdown.
6. Opcionalmente salva o resumo como um arquivo em `raw/` com type `note` e source `handoff`.

### CLI
```
cortex handoff --project=<id> [--target=<agente>]
```

---

## 3. Hooks de Ciclo de Vida dos Agentes

### 3.1 Princípios
- **Fail-Open:** A falha de um hook jamais pode travar o agente ou a sessão do usuário.
- **Async quando possível:** Hooks que não precisam bloquear devem ser assíncronos.
- **Contrato de dados unificado:** O formato de saída dos hooks (para `raw/`) é idêntico entre Claude Code e Antigravity.

### 3.2 Hooks do Claude Code

| Evento | Ação no Cortex |
|---|---|
| `SessionStart` | Executa `query` buscando regras e decisões ativas. Injeta resultado como `additionalContext`. |
| `Stop` | Captura um resumo da sessão via `capture`. Deve checar flag `stop_hook_active` para prevenir loop. |
| `PreCompact` | Dump rápido de preservação de estado (backup das informações essenciais antes do descarte de tokens). |
| `SessionEnd` | Log de encerramento sem bloqueio (opcional). |

### 3.3 Hooks do Antigravity
A integração com Antigravity busca equivalência funcional com o Claude Code:
- Os eventos de lifecycle podem ter nomenclatura diferente (ex: `onSessionStart`, `onToolPreCall`).
- O contrato de dados (saída para `raw/`) deve ser idêntico ao do Claude Code.
- A documentação dos hooks específicos do Antigravity SDK será atualizada conforme a evolução da plataforma.

### 3.4 Formato de Captura de Hook (Padrão)
```yaml
---
id: "<uuid>"
type: note
project: <project-id>
source: hook:<event_name>
status: active
created_at: <timestamp>
---

# Resumo de Sessão (<event_name>)

<conteúdo capturado automaticamente pelo hook>
```

---

## 4. Arquivos Afetados

| Arquivo | Ação |
|---|---|
| `ProjectRepository.java` | Novos métodos `bootstrap()` e `handoff()` |
| `BootstrapCommand.java` | Novo comando CLI |
| `HandoffCommand.java` | Novo comando CLI |
| `CortexCommand.java` | Registrar novos subcomandos |
| `CortexMcpServer.java` | Registrar ferramentas MCP `bootstrap` e `handoff` |
