# API REST do Cortex

## Objetivo

`POST /api/execute` é a superfície REST unificada do Cortex, executando sob **Spring Boot 4.1.0** e **Java 25**. Todas as operações de memória e ferramentas disponíveis no MCP podem ser chamadas por esse endpoint.

---

## Contrato de Requisição e Resposta

Todas as requisições recebem um payload JSON e respondem no envelope padronizado:

```json
{
  "status": "success",
  "output": "..."
}
```

Em caso de erro de validação ou parsing, a API responde HTTP 400 com `status: "error"`.

---

## Comandos Suportados

| Comando | Campos Obrigatórios | Campos Opcionais | Descrição |
|---|---|---|---|
| `query` | `terms` | `limit` | Executa busca lexical ponderada (BM25) com suporte a filtros facetados (`type:rule tags:mcp status:active`). |
| `capture` | `type`, `content` | - | Grava uma observação atômica em `raw/`. |
| `write_page` | `type`, `content` | `tags`, `supersedes`, `consumedRawIds` | Cria ou atualiza página curada em `pages/` com suporte a wikilinks `[[target]]` e cadeia de substituição. |
| `consolidate` | - | `topic` | Retorna o relatório de agrupamento de notas brutas por clusters temáticos (*Smart Synthesizer*). |
| `stats` | - | - | Retorna métricas de saúde, nós e conexões do grafo de conhecimento e top tags. |
| `promote_rules` | `file` | - | Extrai as regras duráveis ativas e as injeta no arquivo especificado (ex: `.agents/AGENTS.md`). |
| `init` | - | - | Inicializa o diretório e a estrutura base do projeto. |
| `bootstrap` | `rootPath` | - | Realiza o bootstrap do projeto no `config.yaml`. |
| `lint` | - | - | Executa checagens de integridade e links quebrados (*dangling wikilinks*). |
| `handoff` | - | `targetAgent` | Gera relatório cirúrgico para transferência de contexto entre agentes. |

---

## Exemplo de Uso

```http
POST /api/execute
Content-Type: application/json; charset=utf-8

{
  "project": "cortex",
  "command": "query",
  "terms": "type:rule jackson mcp"
}
```

---

## Aliases e Tolerância

- O campo `terms` aceita o alias `query`.
- Quando `project` é omitido e há exatamente 1 projeto configurado em `config.yaml`, o projeto é inferido automaticamente.
- Erros de parsing JSON retornam HTTP 400 com mensagens explicativas.
