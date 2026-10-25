---
spec: 008
titulo: Incompatibilidade de transporte Streamable HTTP com OpenAI Codex Desktop e clientes baseados em reqwest/hyper
status: implementada
atualizado: 2026-10-24
---
# 008 — Incompatibilidade de transporte Streamable HTTP com OpenAI Codex Desktop e clientes baseados em reqwest/hyper

## Objetivo
Garantir interoperabilidade estrita do transporte MCP HTTP/SSE com clientes que utilizam `StreamableHttpClientWorker` e `reqwest`/`hyper` (especificamente OpenAI Codex Desktop), eliminando o erro fatal `Client error: HTTP request failed: error decoding response body` durante a inicialização e descoberta de ferramentas (`tools/list`).

## Contexto
O cliente OpenAI Codex Desktop (`v26.1021.31345` / CLI `0.164.0-alpha.11.1`) utiliza a stack Rust `rmcp` com `StreamableHttpClientWorker` e `codex_rmcp_client::http_client_adapter::StreamableHttpClientAdapter`.
Durante o handshake com o servidor Cortex em `http://127.0.0.1:8080/mcp/sse`, a sequência observada nos logs do container foi:
1. `POST /mcp/sse` (`initialize`, `id: 0`, protocolo `2025-06-18`) → respondido com HTTP 200 (175 bytes).
2. `POST /mcp/sse` (`notifications/initialized`) → respondido com HTTP 202 (0 bytes).
3. `POST /mcp/sse` (`tools/list`, `id: 1`, params com `_meta.progressToken`) → respondido com HTTP 200 (3660 bytes).

Imediatamente após receber a resposta de `tools/list`, o Codex encerra a conexão com erro fatal registrado em `logs_2.sqlite`:
```text
worker quit with fatal: Transport channel closed, when Client(HttpRequest(HttpRequest("error decoding response body")))
failed to initialize MCP client during shutdown: MCP startup failed: Transport send error: Transport [codex_rmcp_client::tool_list_changes::ToolListChanges<codex_rmcp_client::event_notification_transport::EventNotificationTransport<rmcp::transport::worker::WorkerTransport<rmcp::transport::streamable_http_client::StreamableHttpClientWorker<codex_rmcp_client::http_client_adapter::StreamableHttpClientAdapter>>>>] error: Client error: HTTP request failed: error decoding response body
```

A análise da implementação em `cortex-api/src/main/java/com/cortex/api/config/McpConfig.java` identificou as causas-raiz:
1. **Ausência de `Content-Length` explícito e escrita via `PrintWriter` sem `flush()`**: A resposta em `asyncResp` utilizava `asyncResp.getWriter().print(responseJson)` sem definir `Content-Length` em bytes UTF-8 e sem chamar `flush()` antes de `asyncContext.complete()`. Em conexões HTTP/1.1 persistentes (`Keep-Alive`), isso provocava buffering incompleto no Tomcat antes do fechamento do contexto assíncrono na virtual thread.
2. **Ausência do cabeçalho de resposta `mcp-protocol-version`**: Clientes MCP modernos (`rmcp` >= `2025-06-18`) enviam e esperam o cabeçalho HTTP `mcp-protocol-version` correspondente à versão negociada em todas as respostas HTTP POST.
3. **Ausência de propagação de `mcp-session-id`**: Quando o cliente envia um ID de sessão via cabeçalho HTTP `mcp-session-id`, o servidor deve ecoá-lo para manter o tracking de sessão no worker do cliente.

## Comportamento esperado
Ao receber requisições MCP via HTTP POST:
1. Todas as respostas JSON (tanto de sucesso quanto de erro) devem calcular o tamanho exato em bytes UTF-8, definir explicitamente o cabeçalho `Content-Length`, escrever diretamente através de `OutputStream` e efetuar o `flush()` imediato antes de concluir o `AsyncContext`.
2. O cabeçalho HTTP `mcp-protocol-version` deve ser retornado contendo a versão negociada do protocolo MCP.
3. Caso a requisição traga o cabeçalho `mcp-session-id`, este deve ser ecoado no cabeçalho da resposta HTTP.
4. Requisições `tools/list` com `params._meta` continuam sendo atendidas com sucesso, devolvendo os metadados correlacionados sem quebras.

## Critérios de aceite
> Cada item é uma afirmação verificável.

- **CA-1:** Respostas a requisições POST com versão de protocolo negociada (`initialize` ou requisição subsequente com header `mcp-protocol-version`) incluem o cabeçalho HTTP `mcp-protocol-version` com o valor correspondente.
- **CA-2:** Todas as respostas JSON da ponte de transporte HTTP MCP possuem o cabeçalho `Content-Length` explicitamente correspondente ao número exato de bytes UTF-8 do payload, escritas via `OutputStream` com `flush()` executado antes de `asyncContext.complete()`.
- **CA-3:** Quando a requisição POST traz o cabeçalho HTTP `mcp-session-id`, a resposta HTTP contém o mesmo cabeçalho `mcp-session-id` com o valor ecoado.
- **CA-4:** A chamada de `tools/list` contendo `{"_meta":{"progressToken":0}}` no objeto `params` responde com HTTP 200, preserva o `id` da requisição e retorna a lista com as 9 ferramentas registradas do Cortex.
- **CA-5:** Respostas de erro (-32601 Method Not Found, -32700 Parse Error e notificações HTTP 202) mantêm `Content-Length` exato e escrita limpa via `OutputStream`/`setStatus`.
- **CA-6:** Todas as invariantes e testes de contrato anteriores (`Spec001EstabilidadeTransporteMcpTest` e `Spec002UnificacaoMcpTest`) permanecem verdes (não-regressão).

## Fora de escopo
- Implementação de servidor SSE server-initiated multiplexado bidirecional RFC/SEP-1699 completo com streaming de chunks contínuos (o Cortex opera via ponte POST síncrona com resposta JSON-RPC direta no corpo, conforme regra de projeto em `AGENTS.md`).
- Alteração no conjunto de ferramentas ou contratos funcionais das tools do Cortex.

## Invariantes tocadas
- **Regra 1 do AGENTS.md**: Jackson Deserialization `FAIL_ON_UNKNOWN_PROPERTIES = false` é estritamente preservada.
- **Regra 2 do AGENTS.md**: Ponte de Resposta SSE POST (retorno do JSON-RPC no corpo do HTTP POST) é estritamente preservada e fortalecida com `Content-Length` e `OutputStream.flush()`.
- **Princípios de Memória LLM Wiki**: Arquivos Markdown como unidade de conhecimento permanecem inalterados.

## Testes que cobrem esta spec
- `cortex-api/src/test/java/contract/Spec008CompatibilidadeStreamableHttpCodexTest.java` → CA-1, CA-2, CA-3, CA-4, CA-5, CA-6

## Notas
- A mudança migra a escrita de resposta de `PrintWriter` para `OutputStream` em `McpConfig.java` para evitar qualquer discrepância de contagem de caracteres vs bytes em strings UTF-8 contendo caracteres acentuados nas descrições das ferramentas.
