---
spec: 001
titulo: Falhas de protocolo, corrupção assíncrona e colisão de sessões no transporte HTTP/SSE do MCP
status: implementada
atualizado: 2026-10-17
---
# 001 — Falhas de protocolo, corrupção assíncrona e colisão de sessões no transporte HTTP/SSE do MCP

## Objetivo
Garantir que clientes MCP modernos baseados em Streamable HTTP e HTTP+SSE concorrentes (como OpenAI Codex `rmcp` e Google Antigravity) inicializem, descubram ferramentas e executem chamadas no endpoint `/mcp/sse` sem erros HTTP 500, falhas de parsing JSON, colisões de `id` ou timeouts de sessão.

## Contexto
Os clientes **OpenAI Codex** (`codex-mcp-client` / Rust `rmcp 1.5.0`) e **Google Antigravity** (`antigravity-client v1.0.0`) migraram do transporte MCP HTTP+SSE legado (`2024-11-05`) para o modelo **Streamable HTTP** (`2025-03-26`, `2025-06-18`, `2025-11-25` e `2026-07-28`), enviando requisições JSON-RPC diretamente via `POST /mcp/sse` e operando múltiplas janelas/subagentes em paralelo. Essa evolução expôs cinco defeitos críticos em `cortex-api/src/main/java/com/cortex/api/config/McpConfig.java`:

1. **Race condition de leitura assíncrona no Servlet (`McpConfig.java:373-379`)**:
   `req.startAsync()` é chamado na thread do container (`nio-8080-exec-*`), mas `req.getReader().lines()` é consumido dentro de `CompletableFuture.runAsync()` após `doPost()` retornar. No Tomcat, ler o `ServletInputStream` em outra thread após a transição para `ASYNC_WAIT` viola o contrato Jakarta Servlet, trava a leitura por ~6,8s e aborta com **HTTP 500**:
   - No Antigravity: `Encountered error in tool execution: calling "tools/call": rejected by transport: sending "tools/call": Internal Server Error`
   - No container (`docker logs cortex-api`): `java.lang.IllegalStateException: The request associated with the AsyncContext has already completed processing`.

2. **Chave JSON `"type"` duplicada na serialização de `McpSchema.TextContent`**:
   No SDK `io.modelcontextprotocol.sdk:mcp:0.7.0`, a interface `McpSchema.Content` possui `@JsonTypeInfo(..., property = "type")` e `McpSchema.TextContent` também expõe `getType()`, fazendo o Jackson (`2.16.1`) serializar duas vezes a chave `"type"` no mesmo objeto:
   ```json
   {"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"text","type":"text","text":"..."}],"isError":false}}
   ```
   O parser `serde_json` em Rust usado pelo Codex (`rmcp 1.5.0`) rejeita objetos com chaves duplicadas:
   `Client error: HTTP request failed: error decoding response body`.

3. **Colisão global de IDs entre clientes concorrentes (`McpConfig.java:96`)**:
   O mapa `pendingResponses` (`ConcurrentHashMap<Object, CompletableFuture<JSONRPCMessage>>`) é global e indexado apenas pelo `id` bruto enviado pelo cliente (`0`, `1`, `2`...). Como cada janela do Antigravity, subagente e instância do Codex inicia a contagem de requisições em `id: 0` ou `id: 1`, chamadas simultâneas sobrescrevem o `CompletableFuture` umas das outras ou entregam respostas trocadas, gerando nos logs:
   `>>> [POST] Timeout waiting for response ID: 1`.

4. **Negociação de protocolo restrita a `2025-06-18`, falta de resposta para `server/discover` e corpo não-vazio no `202 Accepted` (`McpConfig.java:49, 418-420, 448-469`)**:
   - `negotiateProtocolVersion()` só reconhece `2025-06-18`; quando o Antigravity envia `protocolVersion: "2025-11-25"`, sofre downgrade para `2024-11-05`.
   - Antes do `initialize`, o Antigravity envia `{"method":"server/discover","params":{"protocolVersion":"2026-07-28"},"id":1}`. Como o SDK `0.7.0` ignora métodos desconhecidos sem emitir `JSONRPCResponse`, o `POST` fica preso por 15s até expirar o timeout.
   - Em notificações JSON-RPC (`notifications/initialized`), `doPost()` retorna `202 Accepted` com corpo `"{}"` e `Content-Type: application/json`, enquanto clientes Streamable HTTP estritos esperam `202 Accepted` sem corpo.

5. **Tempestade de `notifications/roots/list_changed` e sessões SSE órfãs (`McpConfig.java:341-368`)**:
   Quando o Antigravity envia `notifications/roots/list_changed`, o `McpAsyncServer` dispara `roots/list` para o cliente via `sendMessage`. Sem cliente SSE conectado, `sendMessage` retorna `Mono.empty()`, travando o `DefaultMcpSession` por 10s (`TimeoutException: Did not observe any item or terminal signal within 10000ms`). Com múltiplas sessões SSE (incluindo conexões já fechadas), o broadcast gera respostas duplicadas (`WARN DefaultMcpSession : Unexpected response for unkown id`) e dezenas de `HttpMessageNotWritableException: No converter for [class java.util.LinkedHashMap] with preset Content-Type 'text/event-stream;charset=UTF-8'`.

## Comportamento esperado
- Toda requisição `POST` para `/mcp/sse` ou `/mcp/message` lê o corpo HTTP de forma síncrona na thread do Servlet (`req.getReader().readAllAsString()`) antes de iniciar o `AsyncContext` ou despachar para execução assíncrona em Virtual Threads (Java 25).
- Requisições JSON-RPC (com campo `id`) têm seu `id` correlacionado de forma isolada por requisição HTTP (reescrevendo internamente para um identificador único por chamada antes de passar ao `DefaultMcpSession` e restaurando o `id` original do cliente na resposta HTTP), impedindo qualquer colisão quando múltiplos clientes enviam `id: 0` ou `id: 1` simultaneamente.
- O `ObjectMapper` do transporte MCP serializa `McpSchema.TextContent` (e demais implementações de `McpSchema.Content`) com uma única propriedade `"type"` no JSON resultante via MixIn `@JsonTypeInfo(include = JsonTypeInfo.As.EXISTING_PROPERTY)`.
- No método `initialize`, qualquer versão de protocolo `2024-11-05`, `2025-*` ou `2026-*` enviada pelo cliente é aceita e refletida em `result.protocolVersion`.
- Métodos de requisição JSON-RPC não suportados pelo servidor (como `server/discover`) ou ausentes recebem imediatamente HTTP 200 com erro JSON-RPC padrão (`code: -32601`, `message: "Method not found"`) e o `id` original da requisição, sem aguardar timeout.
- Notificações JSON-RPC (mensagens sem campo `id`, como `notifications/initialized`, `notifications/cancelled` e `notifications/roots/list_changed`) recebem resposta imediata HTTP `202 Accepted` com corpo vazio (`Content-Length: 0`).
- Quando o servidor emitir uma requisição servidor→cliente (como `roots/list` em reação a `notifications/roots/list_changed`), `sendMessage` não deixa o `DefaultMcpSession` pendente por 10s sem resposta nem propaga erros de escrita de sessões SSE encerradas para o `DefaultHandlerExceptionResolver` do Spring MVC.

## Critérios de aceite
> Cada item é uma afirmação verificável — algo que alguém pode reprovar.

- **CA-1:** O corpo de toda requisição `POST /mcp/sse` e `POST /mcp/message` é lido sincronamente na thread do Servlet antes de `req.startAsync()`; uma bateria de 20 requisições `POST` concorrentes (`tools/list` e `tools/call`) responde 100% com HTTP 200 sem lançar `IllegalStateException` do `AsyncContext` nem retornar HTTP 500.
- **CA-2:** O JSON bruto retornado por `POST /mcp/sse` em chamadas `tools/call` contém exatamente uma chave `"type":"text"` em cada elemento de `result.content` (nenhuma ocorrência da substring `"type":"text","type":"text"`).
- **CA-3:** Duas requisições `POST /mcp/sse` disparadas concorrentemente com o mesmo `"id": 1` (uma invocando `tools/list` e outra invocando `tools/call` de `stats`) recebem cada uma o payload correspondente à sua respectiva chamada e preservam `"id": 1` na resposta.
- **CA-4:** Uma requisição `initialize` enviada com `protocolVersion` igual a `"2025-06-18"`, `"2025-11-25"` ou `"2026-07-28"` recebe HTTP 200 com `result.protocolVersion` idêntico ao valor enviado pelo cliente.
- **CA-5:** Uma requisição JSON-RPC para método não suportado (ex.: `{"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"protocolVersion":"2026-07-28"}}`) retorna HTTP 200 em menos de 200ms contendo `"id":1` e `"error":{"code":-32601,"message":"Method not found"}`.
- **CA-6:** Uma notificação JSON-RPC sem campo `id` (ex.: `{"jsonrpc":"2.0","method":"notifications/initialized"}`) retorna status HTTP `202` com corpo vazio (`""`) em menos de 200ms.
- **CA-7:** O envio de `{"jsonrpc":"2.0","method":"notifications/roots/list_changed"}` via `POST /mcp/sse` sem sessão SSE ativa retorna HTTP `202` com corpo vazio e não gera `TimeoutException` (`Did not observe any item or terminal signal within 10000ms`) no `DefaultMcpSession`.
- **CA-8:** Um `POST /mcp/sse` com JSON malformado (ex.: `{"jsonrpc":`) ou corpo em branco retorna HTTP 400 (ou erro JSON-RPC `-32700`) imediatamente, sem criar `AsyncContext` pendente nem vazar entradas em `pendingResponses`.
- **CA-9:** Sem alterar a configuração dos clientes HTTP+SSE legados (`GET /mcp/sse` seguido de `POST /mcp/message?sessionId=...`), o evento `endpoint` continua sendo emitido no stream SSE e as respostas JSON-RPC continuam sendo entregues no corpo do `POST` (status 200).

## Fora de escopo
- Execução assíncrona do `git push` nas ferramentas de escrita da wiki e unificação com o servidor `stdio` (`CortexMcpServer`) — tratado na **Spec 002**.
- Atualização do mapeamento de volumes Docker em `docker-compose.yml` e tradução de diretórios do drive `G:\` — tratado na **Spec 003**.
- Upgrade da dependência `io.modelcontextprotocol.sdk:mcp` de `0.7.0` para versões posteriores — descartado neste momento para evitar quebra de API interna do SDK; as correções desta spec isolam os defeitos na camada de transporte e serialização sob controle do projeto.

## Invariantes tocadas
- **Regra 1 (`AGENTS.md` — Jackson `FAIL_ON_UNKNOWN_PROPERTIES = false`)**: Preservada no `ObjectMapper` do MCP ao adicionar a correção de serialização de `McpSchema.TextContent`.
- **Regra 2 (`AGENTS.md` — Ponte de Resposta SSE POST)**: Preservada e fortalecida com leitura síncrona do request body e correlação isolada por requisição para suportar múltiplos clientes simultâneos sem colisão de `id`.

## Testes que cobrem esta spec
- `cortex-api/src/test/java/contract/Spec001EstabilidadeTransporteMcpTest.java` → CA-1, CA-2, CA-3, CA-4, CA-5, CA-6, CA-7, CA-8, CA-9

## Notas
Implementada com Java 25 (`Reader.readAllAsString()`, Virtual Threads) e MixIn Jackson em `CortexMcpTools`.

