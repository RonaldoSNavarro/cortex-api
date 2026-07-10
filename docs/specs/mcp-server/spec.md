# Servidor MCP (Java) Specification

## Visão Geral
A API do núcleo do sistema é servida via protocolo MCP (Model Context Protocol), rodando um servidor Java através do transporte **stdio**. Ele expõe um conjunto de ferramentas para os agentes de IA interagirem com o modelo de dados de arquivo.

## Requisitos Técnicos
- Baseado em **Java 21**.
- Usa o **MCP Java SDK** (colaboração com Spring AI), usando `StdioServerTransportProvider`.
- **NUNCA** imprimir logs no `System.out`, pois isso corrompe a comunicação JSON-RPC. Usar estritamente `System.err` ou logger que grave em arquivo de log dedicado.

## Ferramentas Expostas (Tools)

| Ferramenta | Entrada | Saída | Descrição |
|---|---|---|---|
| `capture` | `project`, `type`, `content`, `tags` (opcional), `source` (opcional) | `id`, `path` | Grava uma observação bruta na pasta `raw/`. Operação de append-only (cria novo arquivo). |
| `query` | `project`, `terms`, `type` (opcional), `limit` (opcional) | `[{id, title, snippet, path, type}]` | Realiza busca textual/keyword simples sobre `pages/` e opcionalmente `raw/`. |
| `consolidate` | `project`, `since` (opcional), `raw_ids` (opcional) | Lista de observações brutas pendentes de consolidação | Entrega conteúdo bruto para o agente decidir o que promover/curar. |
| `write_page` | `project`, `page_path`, `content`, `type`, `supersedes` (opcional) | `id`, `path` | Cria ou atualiza (via cadeia de supersessão) uma página curada na pasta `pages/`. |
| `promote_rule` | `project`, `rule_content`, `target` (`CLAUDE.md` ou `AGENTS.md`) | `path` | Injeta uma regra durável na raiz do projeto alvo do desenvolvedor. |
| `lint` | `project` | Lista de issues encontradas | Detecta front-matter inválido, links quebrados, duplicatas lógicas ou páginas órfãs. |
| `bootstrap` | `project_name`, `root_path` | `project_id`, `path` | Inicializa a estrutura base de um novo projeto, populando o `config.yaml`. |
| `handoff` | `project`, `target_agent` | `summary_path`, `summary_markdown` | Gera um resumo de estado condensado para reidratação rápida de outro agente. |
