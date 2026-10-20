package contract;

import com.cortex.api.CortexApiApplication;
import com.cortex.api.config.McpConfig;
import com.cortex.core.ProjectRepository;
import com.cortex.mcp.CortexMcpServer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolRegistration;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de contrato para specs/002-git-sync-assincrono-e-unificacao-mcp.md
 * Cobre: CA-5, CA-6, CA-7
 */
@SpringBootTest(classes = CortexApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Spec002UnificacaoMcpTest {

    @LocalServerPort
    private int port;

    @Autowired
    private McpConfig mcpConfig;

    @Autowired
    private ProjectRepository repo;

    @TempDir
    Path tempDir;

    private String originalUserHome;
    private String originalGitSyncProp;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        originalGitSyncProp = System.getProperty("cortex.git.sync-enabled");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        System.setProperty("cortex.git.sync-enabled", "false");
        repo.setGitSyncEnabled(false);
    }

    @AfterEach
    void tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
        if (originalGitSyncProp != null) {
            System.setProperty("cortex.git.sync-enabled", originalGitSyncProp);
        } else {
            System.clearProperty("cortex.git.sync-enabled");
        }
    }

    @Test
    void ca5_objectMapperDoCortexMcpServerIgnoraPropriedadesDesconhecidasNoInitializeESerializaTextContentSemTypeDuplicado() throws Exception {
        ObjectMapper stdioMapper = CortexMcpServer.createObjectMapper();

        String initializeWithCustomCapabilities = """
                {
                  "jsonrpc": "2.0",
                  "id": 1,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2025-11-25",
                    "capabilities": {
                      "elicitation": {"form": {}, "url": {}},
                      "customExtension": {"enabled": true}
                    },
                    "clientInfo": {"name": "claude-code", "version": "1.0.0"}
                  }
                }
                """;

        McpSchema.JSONRPCMessage msg = assertDoesNotThrow(() ->
                McpSchema.deserializeJsonRpcMessage(stdioMapper, initializeWithCustomCapabilities));
        assertInstanceOf(McpSchema.JSONRPCRequest.class, msg);

        McpSchema.CallToolResult toolResult = new McpSchema.CallToolResult(
                List.of(new McpSchema.TextContent("Resultado unificado")),
                false
        );
        String serialized = stdioMapper.writeValueAsString(toolResult);
        assertTrue(serialized.contains("\"type\":\"text\""), "Deve conter \"type\":\"text\": " + serialized);
        assertFalse(serialized.contains("\"type\":\"text\",\"type\":\"text\""),
                "Não deve duplicar a propriedade \"type\":\"text\" no servidor Stdio: " + serialized);
    }

    @Test
    void ca6_bootstrapSchemaDeclaraApenasRootPathObrigatorioEInfereProjectNosServidoresStdioEHttp() throws Exception {
        ObjectMapper mapper = CortexMcpServer.createObjectMapper();

        SyncToolRegistration stdioBootstrap = CortexMcpServer.buildToolRegistrations(repo).stream()
                .filter(r -> "bootstrap".equals(r.tool().name()))
                .findFirst()
                .orElseThrow();

        SyncToolRegistration httpBootstrap = mcpConfig.getToolRegistrations().stream()
                .filter(r -> "bootstrap".equals(r.tool().name()))
                .findFirst()
                .orElseThrow();

        JsonNode stdioRequired = mapper.valueToTree(stdioBootstrap.tool().inputSchema()).path("required");
        JsonNode httpRequired = mapper.valueToTree(httpBootstrap.tool().inputSchema()).path("required");

        assertEquals(List.of("root_path"), mapper.convertValue(stdioRequired, List.class),
                "Schema de bootstrap no Stdio deve exigir apenas ['root_path']");
        assertEquals(List.of("root_path"), mapper.convertValue(httpRequired, List.class),
                "Schema de bootstrap no HTTP deve exigir apenas ['root_path']");

        Path repoPathStdio = tempDir.resolve("meu-projeto-stdio");
        Files.createDirectories(repoPathStdio);
        McpSchema.CallToolResult resStdio = stdioBootstrap.call().apply(Map.of("root_path", repoPathStdio.toString()));
        assertFalse(Boolean.TRUE.equals(resStdio.isError()));
        String textStdio = ((McpSchema.TextContent) resStdio.content().getFirst()).text();
        assertTrue(textStdio.contains("meu-projeto-stdio"), "Stdio bootstrap deve inferir 'meu-projeto-stdio': " + textStdio);

        Path repoPathHttp = tempDir.resolve("meu-projeto-http");
        Files.createDirectories(repoPathHttp);
        McpSchema.CallToolResult resHttp = httpBootstrap.call().apply(Map.of("root_path", repoPathHttp.toString()));
        assertFalse(Boolean.TRUE.equals(resHttp.isError()));
        String textHttp = ((McpSchema.TextContent) resHttp.content().getFirst()).text();
        assertTrue(textHttp.contains("meu-projeto-http"), "HTTP bootstrap deve inferir 'meu-projeto-http': " + textHttp);
    }

    @Test
    void ca7_commandControllerMantemContratoRestParaTodasAsOperacoes() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path projectRoot = tempDir.resolve("proj-rest");
        Files.createDirectories(projectRoot);

        // 1. bootstrap
        assertRestSuccess(mapper, Map.of(
                "command", "bootstrap",
                "rootPath", projectRoot.toString()
        ));

        // 2. capture
        assertRestSuccess(mapper, Map.of(
                "command", "capture",
                "project", "proj-rest",
                "type", "fact",
                "content", "Fato via REST API"
        ));

        // 3. consolidate
        assertRestSuccess(mapper, Map.of(
                "command", "consolidate",
                "project", "proj-rest"
        ));

        // 4. write_page
        assertRestSuccess(mapper, Map.of(
                "command", "write_page",
                "project", "proj-rest",
                "type", "rule",
                "tags", List.of("rest", "contrato"),
                "content", "Regra consolidada via REST API"
        ));

        // 5. query
        assertRestSuccess(mapper, Map.of(
                "command", "query",
                "project", "proj-rest",
                "terms", "REST API"
        ));

        // 6. lint
        assertRestSuccess(mapper, Map.of(
                "command", "lint",
                "project", "proj-rest"
        ));

        // 7. promote_rules
        assertRestSuccess(mapper, Map.of(
                "command", "promote_rules",
                "project", "proj-rest",
                "file", projectRoot.resolve(".agents/AGENTS.md").toString()
        ));

        // 8. stats
        assertRestSuccess(mapper, Map.of(
                "command", "stats",
                "project", "proj-rest"
        ));

        // 9. handoff
        assertRestSuccess(mapper, Map.of(
                "command", "handoff",
                "project", "proj-rest",
                "targetAgent", "antigravity"
        ));
    }

    private void assertRestSuccess(ObjectMapper mapper, Map<String, Object> bodyMap) throws Exception {
        String jsonBody = mapper.writeValueAsString(bodyMap);
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:" + port + "/api/execute"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, resp.statusCode(), "Comando REST " + bodyMap.get("command") + " deve retornar HTTP 200: " + resp.body());
        JsonNode json = mapper.readTree(resp.body());
        assertEquals("success", json.path("status").asText(), "Status deve ser 'success' para " + bodyMap.get("command"));
    }
}
