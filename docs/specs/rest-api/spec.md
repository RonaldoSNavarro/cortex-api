# API REST do Cortex

## Objetivo

`POST /api/execute` é a superfície REST unificada do Cortex. Todas as operações de memória disponíveis no MCP também podem ser chamadas por esse endpoint; o MCP permanece apenas como compatibilidade para clientes existentes.

## Contrato

Todas as requisições incluem `project` e `command`. A resposta usa o envelope:

```json
{
  "status": "success",
  "output": "..."
}
```

Em erros de validação, a API responde HTTP 400 com `status: "error"`.

| Comando | Campos obrigatórios | Campos opcionais |
| --- | --- | --- |
| `query` | `terms` | - |
| `capture` | `type`, `content` | - |
| `write_page` | `type`, `content` | `tags`, `supersedes`, `consumedRawIds` |
| `consolidate` | - | - |
| `promote_rules` | `file` | - |
| `init` | - | - |
| `bootstrap` | `rootPath` | - |
| `lint` | - | - |
| `handoff` | - | `targetAgent` |

## Exemplo

```http
POST /api/execute
Content-Type: application/json

{
  "project": "cortex",
  "command": "query",
  "terms": "MCP"
}
```

## Verificação

- Testes unitários validam as estratégias REST e os campos encaminhados ao repositório.
- A validação de integração confirma `query` contra o container Docker em execução.

## Aliases e Tolerância

- O campo `terms` aceita o alias `query` (útil para clientes que confundem o nome do parâmetro).
- Quando `project` é omitido e há exatamente 1 projeto configurado em `config.yaml`, o projeto é inferido automaticamente.
- Erros de parsing JSON (ex: encoding não-UTF-8) retornam HTTP 400 com mensagem descritiva.

