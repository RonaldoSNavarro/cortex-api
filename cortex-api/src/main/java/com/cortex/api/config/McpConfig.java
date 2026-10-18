package com.cortex.api.config;

import com.cortex.core.ProjectRepository;
import com.cortex.mcp.CortexMcpTools;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolRegistration;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import jakarta.annotation.PreDestroy;

/**
 * Configuração do servidor e transporte HTTP/SSE para o protocolo MCP (Model Context Protocol).
 * Centraliza registro de ferramentas, serialização unificada e controle de ciclo de vida.
 */
@Configuration
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    static final String CODEX_PROTOCOL_VERSION = "2025-06-18";
    static final String LEGACY_PROTOCOL_VERSION = "2024-11-05";

    private static final Set<String> SUPPORTED_REQUEST_METHODS = Set.of(
            "initialize",
            "ping",
            "tools/list",
            "tools/call",
            "resources/list",
            "resources/read",
            "resources/templates/list",
            "prompts/list",
            "prompts/get",
            "logging/setLevel"
    );

    private final ProjectRepository repo;

    private McpSyncServer mcpServer;

    private final ConcurrentHashMap<String, CompletableFuture<McpSchema.JSONRPCMessage>> pendingResponses =
            new ConcurrentHashMap<>();

    private final ExecutorService virtualThreadExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public McpConfig() {
        this(null);
    }

    @Autowired
    public McpConfig(@Autowired(required = false) ProjectRepository repo) {
        this.repo = repo;
    }

    public ExecutorService getVirtualThreadExecutor() {
        return virtualThreadExecutor;
    }

    @PreDestroy
    public void shutdown() {
        if (mcpServer != null) {
            try {
                mcpServer.close();
            } catch (Exception ignored) {
            }
        }
        virtualThreadExecutor.shutdown();
        try {
            if (!virtualThreadExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                virtualThreadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            virtualThreadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public static ObjectMapper createMcpObjectMapper() {
        return CortexMcpTools.createMcpObjectMapper();
    }

    public List<SyncToolRegistration> getToolRegistrations() {
        return CortexMcpTools.buildToolRegistrations(repo);
    }

    public Map<String, CompletableFuture<McpSchema.JSONRPCMessage>> getPendingResponses() {
        return pendingResponses;
    }

    static boolean isSupportedProtocolVersion(String requestedProtocolVersion) {
        if (requestedProtocolVersion == null || requestedProtocolVersion.isBlank()) {
            return false;
        }
        return LEGACY_PROTOCOL_VERSION.equals(requestedProtocolVersion)
                || requestedProtocolVersion.startsWith("2025-")
                || requestedProtocolVersion.startsWith("2026-");
    }

    static String negotiateProtocolVersion(
            ObjectMapper mapper,
            String responseJson,
            String requestedProtocolVersion) throws IOException {
        if (!isSupportedProtocolVersion(requestedProtocolVersion)) {
            return responseJson;
        }

        JsonNode responseNode = mapper.readTree(responseJson);
        JsonNode resultNode = responseNode.path("result");
        if (!resultNode.isObject() || !resultNode.has("protocolVersion")) {
            return responseJson;
        }

        ((ObjectNode) resultNode).put("protocolVersion", requestedProtocolVersion);
        return mapper.writeValueAsString(responseNode);
    }

    @Bean
    public ServletRegistrationBean<HttpServlet> mcpServlet() {
        ObjectMapper mapper = createMcpObjectMapper();

        final AtomicReference<Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>>> connectHandlerRef =
                new AtomicReference<>();

        HttpServletSseServerTransport transport = new HttpServletSseServerTransport(mapper, "/mcp/sse", "/mcp/sse") {
            @Override
            public Mono<Void> connect(Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler) {
                connectHandlerRef.set(handler);
                return super.connect(handler);
            }

            @Override
            public Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                try {
                    // Intercept server-to-client requests (such as roots/list) and complete them immediately
                    // with an empty response so DefaultMcpSession never blocks for 10s when no SSE client responds.
                    if (message instanceof McpSchema.JSONRPCRequest serverReq) {
                        var handler = connectHandlerRef.get();
                        if (handler != null) {
                            Map<String, Object> emptyResult = "roots/list".equals(serverReq.method())
                                    ? Map.of("roots", List.of())
                                    : Map.of();
                            McpSchema.JSONRPCResponse syntheticResponse = new McpSchema.JSONRPCResponse(
                                    McpSchema.JSONRPC_VERSION,
                                    serverReq.id(),
                                    emptyResult,
                                    null
                            );
                            handler.apply(Mono.just(syntheticResponse)).subscribe();
                        }
                        return Mono.empty();
                    }

                    JsonNode resNode = mapper.valueToTree(message);
                    if (resNode.has("id") && !resNode.get("id").isNull()) {
                        String correlationId = resNode.get("id").asText();
                        CompletableFuture<McpSchema.JSONRPCMessage> future = pendingResponses.remove(correlationId);
                        if (future != null) {
                            future.complete(message);
                            // Delivered in HTTP POST body; do not broadcast over SSE
                            return Mono.empty();
                        }
                    }
                } catch (Exception e) {
                    log.debug("Error inspecting outgoing MCP message: {}", e.getMessage());
                }
                return super.sendMessage(message).onErrorResume(ex -> Mono.empty());
            }
        };

        this.mcpServer = CortexMcpTools.registerAll(
                McpServer.sync(transport).serverInfo("cortex", "1.0.0"),
                repo
        ).build();

        HttpServlet wrapperServlet = new HttpServlet() {
            private HttpServletRequest wrapRequest(HttpServletRequest req) {
                return new HttpServletRequestWrapper(req) {
                    @Override
                    public String getServletPath() {
                        return "";
                    }

                    @Override
                    public String getPathInfo() {
                        return getRequestURI();
                    }
                };
            }

            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
                log.info(">>> [GET] URI: {} | Query: {}", req.getRequestURI(), req.getQueryString());
                HttpServletRequest wrapped = wrapRequest(req);
                transport.service(wrapped, resp);
                if (wrapped.isAsyncStarted()) {
                    AsyncContext asyncCtx = wrapped.getAsyncContext();
                    asyncCtx.addListener(new AsyncListener() {
                        @Override
                        public void onComplete(AsyncEvent event) {
                        }

                        @Override
                        public void onTimeout(AsyncEvent event) {
                            try {
                                event.getAsyncContext().complete();
                            } catch (Exception ignored) {
                            }
                        }

                        @Override
                        public void onError(AsyncEvent event) {
                            try {
                                event.getAsyncContext().complete();
                            } catch (Exception ignored) {
                            }
                        }

                        @Override
                        public void onStartAsync(AsyncEvent event) {
                        }
                    });
                }
            }

            @Override
            protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
                // 1. Read request body synchronously on the Servlet thread BEFORE starting AsyncContext (Spec 001 CA-1)
                final String body;
                try {
                    body = req.getReader().readAllAsString();
                } catch (Exception e) {
                    writeParseError(resp, "Failed to read request body");
                    return;
                }

                if (body == null || body.isBlank()) {
                    writeParseError(resp, "Empty request body");
                    return;
                }

                final JsonNode jsonNode;
                try {
                    jsonNode = mapper.readTree(body);
                } catch (Exception e) {
                    writeParseError(resp, "Malformed JSON payload");
                    return;
                }

                if (jsonNode == null || !jsonNode.isObject()) {
                    writeParseError(resp, "JSON-RPC payload must be a JSON object");
                    return;
                }

                log.info(">>> [POST] Body: {}", body);

                final boolean hasId = jsonNode.has("id") && !jsonNode.get("id").isNull();
                final boolean hasMethod = jsonNode.has("method") && jsonNode.get("method").isTextual();
                final String method = hasMethod ? jsonNode.get("method").asText() : null;

                // 2. JSON-RPC Notifications (no "id" field): respond immediately with 202 Accepted and empty body (Spec 001 CA-6, CA-7)
                if (hasMethod && !hasId) {
                    if (!"notifications/roots/list_changed".equals(method)) {
                        Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler = connectHandlerRef.get();
                        if (handler != null) {
                            try {
                                McpSchema.JSONRPCMessage notificationMsg = McpSchema.deserializeJsonRpcMessage(mapper, body);
                                virtualThreadExecutor.submit(() -> {
                                    try {
                                        handler.apply(Mono.just(notificationMsg)).subscribe();
                                    } catch (Exception ignored) {
                                    }
                                });
                            } catch (Exception ignored) {
                            }
                        }
                    }
                    resp.setStatus(HttpServletResponse.SC_ACCEPTED);
                    resp.setContentLength(0);
                    return;
                }

                // 3. Fast-fail unsupported JSON-RPC request methods with -32601 Method not found (Spec 001 CA-5)
                if (hasId && (!hasMethod || !SUPPORTED_REQUEST_METHODS.contains(method))) {
                    ObjectNode errorResp = mapper.createObjectNode();
                    errorResp.put("jsonrpc", "2.0");
                    errorResp.set("id", jsonNode.get("id").deepCopy());
                    ObjectNode errorObj = errorResp.putObject("error");
                    errorObj.put("code", -32601);
                    errorObj.put("message", "Method not found");

                    resp.setContentType("application/json");
                    resp.setCharacterEncoding("UTF-8");
                    resp.setStatus(HttpServletResponse.SC_OK);
                    resp.getWriter().print(mapper.writeValueAsString(errorResp));
                    return;
                }

                Function<Mono<McpSchema.JSONRPCMessage>, Mono<McpSchema.JSONRPCMessage>> handler = connectHandlerRef.get();
                if (handler == null) {
                    log.error(">>> [POST] ERROR: connectHandlerRef is null!");
                    resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                    return;
                }

                // 4. Rewrite request "id" to a unique internal correlation ID to prevent concurrent ID collisions (Spec 001 CA-3)
                final JsonNode originalIdNode = hasId ? jsonNode.get("id").deepCopy() : null;
                final String internalCorrelationId = hasId ? "req-" + UUID.randomUUID() : null;
                final String requestedProtocolVersion = "initialize".equals(method)
                        ? jsonNode.path("params").path("protocolVersion").asText(null)
                        : null;

                final McpSchema.JSONRPCMessage message;
                try {
                    if (internalCorrelationId != null) {
                        ObjectNode rewritten = ((ObjectNode) jsonNode).deepCopy();
                        rewritten.put("id", internalCorrelationId);
                        message = McpSchema.deserializeJsonRpcMessage(mapper, mapper.writeValueAsString(rewritten));
                    } else {
                        message = McpSchema.deserializeJsonRpcMessage(mapper, body);
                    }
                } catch (Exception e) {
                    writeParseError(resp, "Invalid JSON-RPC message structure");
                    return;
                }

                final CompletableFuture<McpSchema.JSONRPCMessage> responseFuture;
                if (internalCorrelationId != null) {
                    responseFuture = new CompletableFuture<>();
                    pendingResponses.put(internalCorrelationId, responseFuture);
                } else {
                    responseFuture = null;
                }

                // 5. Start AsyncContext only after body is read, validated, and correlated
                final AsyncContext asyncContext = req.startAsync();
                asyncContext.setTimeout(30000);

                virtualThreadExecutor.submit(() -> {
                    try {
                        handler.apply(Mono.just(message)).subscribe();

                        if (responseFuture != null) {
                            responseFuture.orTimeout(15, TimeUnit.SECONDS).whenComplete((resMsg, ex) -> {
                                try {
                                    HttpServletResponse asyncResp = (HttpServletResponse) asyncContext.getResponse();
                                    if (ex != null) {
                                        pendingResponses.remove(internalCorrelationId);
                                        if (ex instanceof TimeoutException) {
                                            log.warn(">>> [POST] Timeout waiting for response correlation ID: {}", internalCorrelationId);
                                            if (!asyncResp.isCommitted()) {
                                                asyncResp.setStatus(HttpServletResponse.SC_ACCEPTED);
                                                asyncResp.setContentLength(0);
                                            }
                                        } else {
                                            log.error(">>> [POST] Error waiting for response correlation ID: {}", internalCorrelationId, ex);
                                            if (!asyncResp.isCommitted()) {
                                                asyncResp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                                            }
                                        }
                                    } else {
                                        JsonNode resTree = mapper.valueToTree(resMsg);
                                        if (resTree.isObject() && originalIdNode != null) {
                                            ((ObjectNode) resTree).set("id", originalIdNode);
                                        }
                                        String responseJson = mapper.writeValueAsString(resTree);
                                        responseJson = negotiateProtocolVersion(
                                                mapper,
                                                responseJson,
                                                requestedProtocolVersion
                                        );
                                        log.debug(">>> [POST] Captured Response: {}", responseJson);
                                        asyncResp.setContentType("application/json");
                                        asyncResp.setCharacterEncoding("UTF-8");
                                        asyncResp.setStatus(HttpServletResponse.SC_OK);
                                        asyncResp.getWriter().print(responseJson);
                                    }
                                } catch (Exception innerEx) {
                                    log.error(">>> [POST] Exception writing response: {}", innerEx.getMessage(), innerEx);
                                } finally {
                                    try {
                                        asyncContext.complete();
                                    } catch (Exception ignored) {
                                    }
                                }
                            });
                        } else {
                            HttpServletResponse asyncResp = (HttpServletResponse) asyncContext.getResponse();
                            asyncResp.setStatus(HttpServletResponse.SC_ACCEPTED);
                            asyncResp.setContentLength(0);
                            asyncContext.complete();
                        }
                    } catch (Exception e) {
                        if (internalCorrelationId != null) {
                            pendingResponses.remove(internalCorrelationId);
                        }
                        log.error(">>> [POST] EXCEPTION: {}", e.getMessage(), e);
                        try {
                            ((HttpServletResponse) asyncContext.getResponse()).setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                        } catch (Exception ignored) {
                        }
                        try {
                            asyncContext.complete();
                        } catch (Exception ignored) {
                        }
                    }
                });
            }

            private void writeParseError(HttpServletResponse resp, String detail) throws IOException {
                resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                resp.setContentType("application/json");
                resp.setCharacterEncoding("UTF-8");
                ObjectNode errResp = mapper.createObjectNode();
                errResp.put("jsonrpc", "2.0");
                errResp.putNull("id");
                ObjectNode err = errResp.putObject("error");
                err.put("code", -32700);
                err.put("message", "Parse error: " + detail);
                resp.getWriter().print(mapper.writeValueAsString(errResp));
            }
        };

        ServletRegistrationBean<HttpServlet> registrationBean = new ServletRegistrationBean<>(wrapperServlet, "/mcp/*");
        registrationBean.setName("mcpServlet");
        registrationBean.setLoadOnStartup(1);
        registrationBean.setAsyncSupported(true);
        return registrationBean;
    }
}
