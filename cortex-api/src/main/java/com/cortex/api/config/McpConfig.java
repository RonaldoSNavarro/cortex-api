package com.cortex.api.config;

import com.cortex.core.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

@Configuration
public class McpConfig {

    private static final Logger log = LoggerFactory.getLogger(McpConfig.class);

    static final String CODEX_PROTOCOL_VERSION = "2025-06-18";
    static final String LEGACY_PROTOCOL_VERSION = "2024-11-05";

    @Autowired
    private ProjectRepository repo;

    private McpSyncServer mcpServer;

    static String negotiateProtocolVersion(
            ObjectMapper mapper,
            String responseJson,
            String requestedProtocolVersion) throws java.io.IOException {
        if (!CODEX_PROTOCOL_VERSION.equals(requestedProtocolVersion)) {
            return responseJson;
        }

        com.fasterxml.jackson.databind.JsonNode responseNode = mapper.readTree(responseJson);
        com.fasterxml.jackson.databind.JsonNode protocolVersionNode = responseNode.path("result").path("protocolVersion");
        if (!LEGACY_PROTOCOL_VERSION.equals(protocolVersionNode.asText())) {
            return responseJson;
        }

        ((com.fasterxml.jackson.databind.node.ObjectNode) responseNode.path("result"))
                .put("protocolVersion", CODEX_PROTOCOL_VERSION);
        return mapper.writeValueAsString(responseNode);
    }

    @Bean
    public ServletRegistrationBean<jakarta.servlet.http.HttpServlet> mcpServlet() {
        // Custom ObjectMapper with a Jackson module to fix JSONRPCMessage deserialization.
        // The SDK's native ObjectMapper fails because JSONRPCMessage is abstract.
        // This custom deserializer delegates to McpSchema.deserializeJsonRpcMessage()
        // which does manual type resolution (checks for "method", "result", "error" fields).
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        com.fasterxml.jackson.databind.module.SimpleModule mcpModule = new com.fasterxml.jackson.databind.module.SimpleModule("McpJsonRpc");
        final ObjectMapper internalMapper = new ObjectMapper(); // separate mapper to avoid recursion
        internalMapper.configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mcpModule.addDeserializer(
            McpSchema.JSONRPCMessage.class,
            new com.fasterxml.jackson.databind.deser.std.StdDeserializer<McpSchema.JSONRPCMessage>(McpSchema.JSONRPCMessage.class) {
                @Override
                public McpSchema.JSONRPCMessage deserialize(
                        com.fasterxml.jackson.core.JsonParser p,
                        com.fasterxml.jackson.databind.DeserializationContext ctxt) throws java.io.IOException {
                    com.fasterxml.jackson.databind.JsonNode node = p.getCodec().readTree(p);
                    return McpSchema.deserializeJsonRpcMessage(internalMapper, node.toString());
                }
            }
        );
        mapper.registerModule(mcpModule);

        // Response interceptor: the SDK's native doPost echoes the REQUEST in the HTTP body
        // and sends the actual RESPONSE via SSE. The Go MCP client expects the response in
        // the POST body. We intercept sendMessage() to capture responses via CompletableFuture.
        final java.util.concurrent.ConcurrentHashMap<Object, java.util.concurrent.CompletableFuture<McpSchema.JSONRPCMessage>> pendingResponses = 
            new java.util.concurrent.ConcurrentHashMap<>();

        final java.util.concurrent.atomic.AtomicReference<java.util.function.Function<reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>, reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>>> connectHandlerRef = 
            new java.util.concurrent.atomic.AtomicReference<>();

        HttpServletSseServerTransport transport = new HttpServletSseServerTransport(mapper, "/mcp/sse", "/mcp/sse") {
            @Override
            public reactor.core.publisher.Mono<java.lang.Void> connect(java.util.function.Function<reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>, reactor.core.publisher.Mono<McpSchema.JSONRPCMessage>> handler) {
                connectHandlerRef.set(handler);
                return super.connect(handler);
            }

            @Override
            public reactor.core.publisher.Mono<Void> sendMessage(McpSchema.JSONRPCMessage message) {
                try {
                    com.fasterxml.jackson.databind.JsonNode resNode = mapper.valueToTree(message);
                    if (resNode.has("id")) {
                        com.fasterxml.jackson.databind.JsonNode idNode = resNode.get("id");
                        Object resId = null;
                        if (idNode.isNumber()) {
                            resId = idNode.numberValue();
                        } else if (idNode.isTextual()) {
                            resId = idNode.textValue();
                        }
                        if (resId != null) {
                            java.util.concurrent.CompletableFuture<McpSchema.JSONRPCMessage> future = pendingResponses.remove(resId);
                            if (future != null) {
                                future.complete(message);
                                // If we sent it via POST, do not send it over SSE to avoid confusing strict clients
                                return reactor.core.publisher.Mono.empty();
                            }
                        }
                    }
                } catch (Exception e) {
                    // Ignore parsing errors here
                }
                // Also send via SSE for clients that use the SSE stream
                return super.sendMessage(message);
            }
        };

        // (skipping schema strings for brevity - wait I can't skip them, I need to replace only what changed)

        String captureSchema = "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" },\n" +
            "    \"type\": { \"type\": \"string\", \"description\": \"Memory type (fact, decision, rule, etc)\" },\n" +
            "    \"content\": { \"type\": \"string\", \"description\": \"Conteudo bruto\" }\n" +
            "  },\n" +
            "  \"required\": [\"project\", \"type\", \"content\"]\n" +
            "}";

        String querySchema = "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" },\n" +
            "    \"terms\": { \"type\": \"string\", \"description\": \"Termos de busca (suporta filtros facetados: type:rule tags:mcp status:active)\" },\n" +
            "    \"limit\": { \"type\": \"integer\", \"description\": \"Limite maximo de resultados (padrao 10)\" }\n" +
            "  },\n" +
            "  \"required\": [\"project\", \"terms\"]\n" +
            "}";
            
        String lintSchema = "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" }\n" +
            "  },\n" +
            "  \"required\": [\"project\"]\n" +
            "}";

        String writePageSchema = "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" },\n" +
            "    \"type\": { \"type\": \"string\", \"description\": \"Memory type (fact, decision, rule, etc)\" },\n" +
            "    \"content\": { \"type\": \"string\", \"description\": \"Conteudo consolidado em Markdown (suporta [[wikilinks]])\" },\n" +
            "    \"tags\": { \"type\": \"array\", \"items\": { \"type\": \"string\" }, \"description\": \"Tags para busca (opcional)\" },\n" +
            "    \"supersedes\": { \"type\": \"string\", \"description\": \"ID da página antiga a ser substituída (opcional)\" },\n" +
            "    \"consumed_raw_ids\": { \"type\": \"array\", \"items\": { \"type\": \"string\" }, \"description\": \"IDs dos arquivos raw consumidos nesta consolidação para exclusão (opcional)\" }\n" +
            "  },\n" +
            "  \"required\": [\"project\", \"type\", \"content\"]\n" +
            "}";

        String promoteSchema = "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" },\n" +
            "    \"file\": { \"type\": \"string\", \"description\": \"Target file path (e.g. .agents/AGENTS.md)\" }\n" +
            "  },\n" +
            "  \"required\": [\"project\", \"file\"]\n" +
            "}";

        String consolidateSchema = "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" },\n" +
            "    \"topic\": { \"type\": \"string\", \"description\": \"Topico ou tag opcional para consolidar apenas um cluster especifico\" }\n" +
            "  },\n" +
            "  \"required\": [\"project\"]\n" +
            "}";

        String statsSchema = "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" }\n" +
            "  },\n" +
            "  \"required\": [\"project\"]\n" +
            "}";

        this.mcpServer = McpServer.sync(transport)
            .serverInfo("cortex", "1.0.0")
            .tool(
                new Tool("capture", "Captura informacoes brutas (raw) para a memoria", captureSchema),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        String type = (String) argsMap.get("type");
                        String content = (String) argsMap.get("content");
                        String id = repo.createRaw(project, type, content);
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Ingestão salva com ID " + id)), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("write_page", "Escreve uma página curada na wiki, consolidando contexto bruto com suporte a [[wikilinks]]", writePageSchema),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        String type = (String) argsMap.get("type");
                        String content = (String) argsMap.get("content");
                        String supersedes = (String) argsMap.get("supersedes");
                        
                        @SuppressWarnings("unchecked")
                        java.util.List<String> tags = argsMap.containsKey("tags") ? (java.util.List<String>) argsMap.get("tags") : null;
                        
                        @SuppressWarnings("unchecked")
                        java.util.List<String> consumedRawIds = argsMap.containsKey("consumed_raw_ids") ? (java.util.List<String>) argsMap.get("consumed_raw_ids") : null;
                        
                        String message = repo.writePage(project, type, tags, supersedes, consumedRawIds, content);
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent(message)), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro ao escrever página: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("lint", "Verifica a saúde da base do Cortex, detectando referências inválidas, links quebrados e conflitos.", lintSchema),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        java.util.List<String> warnings = repo.lint(project);
                        if (warnings.isEmpty()) {
                            return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Nenhum problema encontrado. Status: OK")), false);
                        } else {
                            return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Problemas encontrados:\n" + String.join("\n", warnings))), false);
                        }
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro ao executar linter: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("promote_rules", "Extrai as regras ativas da base de memória do Cortex e injeta no contexto da IDE (AGENTS.md)", promoteSchema),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        String file = (String) argsMap.get("file");
                        repo.promoteRules(project, file);
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Regras promovidas com sucesso para " + file)), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro ao promover regras: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("query", "Busca informacoes na memoria do projeto usando ranking BM25 e filtros facetados (type:, tags:, status:)", querySchema),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        String terms = (String) argsMap.get("terms");
                        int limit = argsMap.containsKey("limit") && argsMap.get("limit") instanceof Number
                                ? ((Number) argsMap.get("limit")).intValue() : 10;
                        
                        java.util.List<com.cortex.core.SearchResult> results = repo.searchLexical(project, terms, limit);
                        if (results.isEmpty()) {
                            return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Nenhum resultado encontrado para: " + terms)), false);
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append(String.format("Resultados da busca lexical (BM25) para '%s' (%d encontrados):\n\n", terms, results.size()));
                        for (com.cortex.core.SearchResult res : results) {
                            sb.append(res.toFormattedSummary()).append("\n\n");
                        }
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent(sb.toString())), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro na busca: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("consolidate", "Retorna notas brutas pendentes agrupadas em clusters temáticos para guiar a síntese curada via write_page.", consolidateSchema),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        String topic = (String) argsMap.get("topic");
                        String report = repo.consolidateReport(project, topic);
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent(report)), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro na consolidação: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("stats", "Retorna métricas de saúde, taxa de consolidação, distribuição de tags e densidade do grafo do projeto", statsSchema),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        com.cortex.core.CortexStats stats = repo.getStats(project);
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent(stats.toMarkdownSummary())), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro ao obter estatísticas: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("bootstrap", "Inicializa um projeto completo no Cortex com estrutura de diretórios, Skill e registro no config", 
                    "{\n" +
                    "  \"type\": \"object\",\n" +
                    "  \"properties\": {\n" +
                    "    \"project\": { \"type\": \"string\", \"description\": \"Project ID (opcional)\" },\n" +
                    "    \"root_path\": { \"type\": \"string\", \"description\": \"Caminho absoluto do repositório de código\" }\n" +
                    "  },\n" +
                    "  \"required\": [\"root_path\"]\n" +
                    "}"),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        String rootPath = (String) argsMap.get("root_path");
                        String id = repo.bootstrap(project, rootPath);
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Projeto '" + id + "' inicializado com sucesso via bootstrap.")), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro no bootstrap: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("handoff", "Gera um relatório de handoff consolidado do projeto para transferência de contexto entre agentes",
                    "{\n" +
                    "  \"type\": \"object\",\n" +
                    "  \"properties\": {\n" +
                    "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" },\n" +
                    "    \"target_agent\": { \"type\": \"string\", \"description\": \"Nome do agente alvo (ex: claude, antigravity)\" }\n" +
                    "  },\n" +
                    "  \"required\": [\"project\"]\n" +
                    "}"),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        String targetAgent = (String) argsMap.get("target_agent");
                        String summary = repo.handoff(project, targetAgent);
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent(summary)), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro no handoff: " + e.getMessage())), true);
                    }
                }
            )
            .build();

        // Thin wrapper servlet that delegates everything to the transport.
        // The custom ObjectMapper fixes deserialization and the SDK's native doPost 
        // writes the JSON-RPC response to the HTTP body.
        // An HttpServletRequestWrapper is needed because the servlet is registered at /mcp/*,
        // so getPathInfo() returns "/sse" instead of "/mcp/sse". The SDK compares paths
        // using the full messageEndpoint, so we override getPathInfo() to return the full URI.
        jakarta.servlet.http.HttpServlet wrapperServlet = new jakarta.servlet.http.HttpServlet() {
            private jakarta.servlet.http.HttpServletRequest wrapRequest(jakarta.servlet.http.HttpServletRequest req) {
                return new jakarta.servlet.http.HttpServletRequestWrapper(req) {
                    @Override
                    public String getServletPath() { return ""; }
                    @Override
                    public String getPathInfo() { return getRequestURI(); }
                };
            }
            @Override
            protected void doGet(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse resp) throws jakarta.servlet.ServletException, java.io.IOException {
                log.info(">>> [GET] URI: {} | Query: {}", req.getRequestURI(), req.getQueryString());
                transport.service(wrapRequest(req), resp);
            }
            @Override
            protected void doPost(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse resp) throws jakarta.servlet.ServletException, java.io.IOException {
                final jakarta.servlet.AsyncContext asyncContext = req.startAsync();
                asyncContext.setTimeout(30000); // 30 seconds

                java.util.concurrent.CompletableFuture.runAsync(() -> {
                    try {
                        String body = req.getReader().lines().collect(java.util.stream.Collectors.joining(System.lineSeparator()));
                        log.info(">>> [POST] Body: {}", body);
                        
                        com.fasterxml.jackson.databind.JsonNode jsonNode = mapper.readTree(body);
                        final String requestedProtocolVersion =
                            "initialize".equals(jsonNode.path("method").asText())
                                ? jsonNode.path("params").path("protocolVersion").asText(null)
                                : null;
                        Object msgId = null;
                        if (jsonNode.has("id") && jsonNode.has("method")) {
                            com.fasterxml.jackson.databind.JsonNode idNode = jsonNode.get("id");
                            if (idNode.isNumber()) {
                                msgId = idNode.numberValue();
                            } else if (idNode.isTextual()) {
                                msgId = idNode.textValue();
                            }
                        }
                        
                        io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage message = 
                            io.modelcontextprotocol.spec.McpSchema.deserializeJsonRpcMessage(mapper, body);
                        
                        java.util.function.Function<reactor.core.publisher.Mono<io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage>, reactor.core.publisher.Mono<io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage>> handler = 
                            connectHandlerRef.get();
                        
                        if (handler == null) {
                            log.error(">>> [POST] ERROR: connectHandlerRef is null!");
                            ((jakarta.servlet.http.HttpServletResponse) asyncContext.getResponse()).setStatus(500);
                            asyncContext.complete();
                            return;
                        }
                        
                        java.util.concurrent.CompletableFuture<io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage> responseFuture = null;
                        if (msgId != null) {
                            responseFuture = new java.util.concurrent.CompletableFuture<>();
                            pendingResponses.put(msgId, responseFuture);
                        }
                        
                        // Feed the message into the connectHandler
                        handler.apply(reactor.core.publisher.Mono.just(message)).subscribe();
                        
                        if (responseFuture != null) {
                            final Object finalMsgId = msgId;
                            responseFuture.orTimeout(15, java.util.concurrent.TimeUnit.SECONDS).whenComplete((resMsg, ex) -> {
                                try {
                                    jakarta.servlet.http.HttpServletResponse asyncResp = (jakarta.servlet.http.HttpServletResponse) asyncContext.getResponse();
                                    if (ex != null) {
                                        if (ex instanceof java.util.concurrent.TimeoutException) {
                                            log.warn(">>> [POST] Timeout waiting for response ID: {}", finalMsgId);
                                            pendingResponses.remove(finalMsgId);
                                            if (!asyncResp.isCommitted()) {
                                                asyncResp.setContentType("application/json");
                                                asyncResp.setCharacterEncoding("UTF-8");
                                                asyncResp.setStatus(202);
                                                asyncResp.getWriter().print("{}");
                                            }
                                        } else {
                                            log.error(">>> [POST] Error waiting for response ID: {}", finalMsgId, ex);
                                            pendingResponses.remove(finalMsgId);
                                            asyncResp.setStatus(500);
                                        }
                                    } else {
                                        String responseJson = mapper.writeValueAsString(resMsg);
                                        responseJson = negotiateProtocolVersion(
                                            mapper,
                                            responseJson,
                                            requestedProtocolVersion
                                        );
                                        log.debug(">>> [POST] Captured Response: {}", responseJson);
                                        asyncResp.setContentType("application/json");
                                        asyncResp.setCharacterEncoding("UTF-8");
                                        asyncResp.setStatus(200);
                                        asyncResp.getWriter().print(responseJson);
                                    }
                                } catch (Exception innerEx) {
                                    log.error(">>> [POST] Exception writing response: {}", innerEx.getMessage(), innerEx);
                                } finally {
                                    asyncContext.complete();
                                }
                            });
                        } else {
                            jakarta.servlet.http.HttpServletResponse asyncResp = (jakarta.servlet.http.HttpServletResponse) asyncContext.getResponse();
                            asyncResp.setContentType("application/json");
                            asyncResp.setCharacterEncoding("UTF-8");
                            asyncResp.setStatus(202);
                            asyncResp.getWriter().print("{}");
                            asyncContext.complete();
                        }
                    } catch (Exception e) {
                        log.error(">>> [POST] EXCEPTION: {}", e.getMessage(), e);
                        try {
                            ((jakarta.servlet.http.HttpServletResponse) asyncContext.getResponse()).setStatus(500);
                        } catch (Exception ignore) {}
                        asyncContext.complete();
                    }
                });
            }
        };

        ServletRegistrationBean<jakarta.servlet.http.HttpServlet> registrationBean = new ServletRegistrationBean<>(wrapperServlet, "/mcp/*");
        registrationBean.setName("mcpServlet");
        registrationBean.setLoadOnStartup(1);
        registrationBean.setAsyncSupported(true);
        return registrationBean;
    }
}
