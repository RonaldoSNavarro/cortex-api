# Servidor MCP (Java) Specification

## Visão Geral
A API do núcleo do sistema é servida via protocolo MCP (Model Context Protocol). O Cortex suporta dois transportes:
1. **HTTP Server-Sent Events (SSE)** nativo no `cortex-api` no endpoint `http://localhost:8080/mcp/sse` (com *SSE POST Response Bridging*).
2. **stdio** no `cortex-mcp` para clientes locais que inicializam o processo via subprocesso standard IO.

---

## Requisitos Técnicos
- Baseado em **Java 25** e **Spring Boot 4.1.0**.
- Utiliza o **MCP Java SDK** (`io.modelcontextprotocol.sdk:mcp:0.7.0`).
- **Jackson Deserialization Constraint**: `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES = false` para suporte a capacidades estendidas de qualquer cliente MCP (ex: Go SDK, Claude Code, Codex, Antigravity).
- **SSE POST Response Bridging**: No transporte SSE, intercepta mensagens POST e escreve a resposta JSON-RPC real no corpo da resposta HTTP POST.
- **Isolamento de Logs**: No transporte stdio, logs nunca são enviados para `System.out` para evitar corromper o fluxo JSON-RPC.

---

## Ferramentas Expostas (9 Tools)

| Ferramenta | Entrada | Saída | Descrição |
|---|---|---|---|
| `query` | `project` (string), `terms` (string), `limit` (int, opcional) | Resultados BM25 formatados com scores, snippets e tags | Realiza busca lexical ponderada (BM25) com suporte a filtros facetados (`type:`, `tags:`, `status:`). |
| `capture` | `project` (string), `type` (string), `content` (string) | `id`, `path` | Grava uma observação bruta na pasta `raw/` de forma rápida e atômica. |
| `write_page` | `project` (string), `type` (string), `content` (string), `tags` (array, opcional), `supersedes` (string, opcional), `consumed_raw_ids` (array, opcional) | `id`, `status` e alertas de impacto | Cria ou atualiza uma página curada na pasta `pages/` com suporte a `[[wikilinks]]`. Se houver `supersedes`, avisa sobre dependentes afetados. |
| `consolidate` | `project` (string), `topic` (string, opcional) | Relatório de clusters semânticos estruturado | Agrupa notas brutas pendentes em tópicos e sugere tags e IDs a consumir. |
| `stats` | `project` (string) | Dashboard em Markdown com métricas | Retorna contagem de páginas ativas/substituídas, taxa de consolidação, nós/arestas do grafo e páginas mais referenciadas. |
| `lint` | `project` (string) | Lista de alertas semânticos | Detecta *dangling wikilinks* (links para IDs inexistentes), conflitos de contexto e débito de consolidação. |
| `promote_rules` | `project` (string), `file` (string) | `status` | Extrai regras ativas da base do Cortex e as injeta no contexto da IDE/Agente (`AGENTS.md` ou `CLAUDE.md`). |
| `bootstrap` | `project` (string), `rootPath` (string) | `project_id`, `path` | Inicializa a estrutura base de um novo projeto, populando o `config.yaml` e criando `schema.md`. |
| `handoff` | `project` (string), `target_agent` (string) | Relatório cirúrgico de handoff | Gera um resumo de estado condensado para reidratação rápida de outro agente de IA. |
