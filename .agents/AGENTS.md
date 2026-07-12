# Project Rules: Model Context Protocol (MCP) Java Integration

When building or modifying Model Context Protocol (MCP) server endpoints in this Java project, always adhere to the following implementation constraints:

## 1. Jackson Deserialization (FAIL_ON_UNKNOWN_PROPERTIES)
- **Constraint**: Always configure Jackson `ObjectMapper` instances used for MCP message parsing with `DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES` set to `false`.
- **Rationale**: MCP clients (such as the Go SDK) often send client-specific capabilities (e.g., `"elicitation":{"form":{},"url":{}}`) in their `initialize` request. The Java MCP SDK's static class definitions (like `ClientCapabilities`) only model standard fields (`experimental`, `roots`, `sampling`). If `FAIL_ON_UNKNOWN_PROPERTIES` is enabled (the default), Jackson will throw an exception and fail the handshake.

## 2. SSE POST Response Bridging
- **Constraint**: For SSE transports, intercept client-to-server POST requests and return the actual JSON-RPC response in the HTTP POST body.
- **Rationale**: While the official MCP SSE specification states that POST responses can have an empty body (status 202), strict client libraries (such as Go's `mcp-go`) expect the matching JSON-RPC response directly in the HTTP POST response body and fail to decode empty or request-echoed bodies. Ensure you intercept outgoing messages (using `sendMessage`), register futures per-request ID, block the POST request handler until the response is captured, and write the response directly to the POST response body.

# Princípios de Memória (AI Memory / LLM Wiki)

Sempre que projetar, codificar ou sugerir arquiteturas para sistemas de memória de IA, agentes ou recuperação de contexto (como o projeto Cortex), é ESTRITAMENTE PROIBIDO sugerir ou implementar "RAG tradicional" (Retrieval-Augmented Generation) baseado em fatiamento de texto (Chunking) e bancos vetoriais opacos.

Siga obrigatoriamente as premissas de Karpathy (LLM Wiki) e Akita (ai-memory):
1. **Unidade Atômica**: O arquivo Markdown (.md) é a unidade fundamental. O conhecimento deve ser acumulado, consolidado e mantido em arquivos legíveis por humanos e versionados via Git.
2. **Consolidação em vez de Recuperação de Fragmentos**: A IA não deve recuperar fragmentos isolados de logs brutos. A IA deve ler logs brutos, processá-los e reescrever páginas de wiki coerentes.
3. **Índices Simples**: Para buscas, prefira abordagens simples e diretas como arquivos `index.md`, `log.md`, ou buscas textuais (FTS5). Se um banco vetorial for absolutamente necessário, ele deve agir APENAS como um índice que aponta para o ID do arquivo (1 vetor = 1 arquivo inteiro), preservando a leitura do front-matter e do documento completo pelo LLM.
4. **Sem Infraestrutura Desnecessária**: Evite sobrecarregar o usuário com dependências (bancos de dados vetoriais, containers extras) a menos que a escala exija. Mantenha o sistema "KISS".
