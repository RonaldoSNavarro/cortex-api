# Project Rules: Model Context Protocol (MCP) Java Integration

When building or modifying Model Context Protocol (MCP) server endpoints in this Java project, always adhere to the following implementation constraints:

## 1. Jackson Deserialization (FAIL_ON_UNKNOWN_PROPERTIES)
- **Constraint**: Always configure Jackson `ObjectMapper` instances used for MCP message parsing with `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` set to `false`.
- **Rationale**: MCP clients (such as the Go SDK) often send client-specific capabilities (e.g., `"elicitation":{"form":{},"url":{}}`) in their `initialize` request. The Java MCP SDK's static class definitions (like `ClientCapabilities`) only model standard fields (`experimental`, `roots`, `sampling`). If `FAIL_ON_UNKNOWN_PROPERTIES` is enabled (the default), Jackson will throw an exception and fail the handshake.

## 2. SSE POST Response Bridging
- **Constraint**: For SSE transports, intercept client-to-server POST requests and return the actual JSON-RPC response in the HTTP POST body.
- **Rationale**: While the official MCP SSE specification states that POST responses can have an empty body (status 202), strict client libraries (such as Go's `mcp-go`) expect the matching JSON-RPC response directly in the HTTP POST response body and fail to decode empty or request-echoed bodies. Ensure you intercept outgoing messages (using `sendMessage`), register futures per-request ID, block the POST request handler until the response is captured, and write the response directly to the POST response body.
