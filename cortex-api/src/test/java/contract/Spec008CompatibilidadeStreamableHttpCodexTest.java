package contract;

import com.cortex.api.CortexApiApplication;
import com.cortex.api.config.McpConfig;
import com.cortex.core.ProjectRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de contrato para specs/008-compatibilidade-streamable-http-codex.md
 * Cobre: CA-1, CA-2, CA-3, CA-4, CA-5, CA-6
 */
@SpringBootTest(classes = CortexApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Spec008CompatibilidadeStreamableHttpCodexTest {

    @LocalServerPort
    private int port;

    @Autowired
    private McpConfig mcpConfig;

    @Autowired
    private ProjectRepository repo;

    @TempDir
    Path tempHome;

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private String originalUserHome;
    private String originalGitSyncProp;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws Exception {
        originalUserHome = System.getProperty("user.home");
        originalGitSyncProp = System.getProperty("cortex.git.sync-enabled");
        System.setProperty("user.home", tempHome.toString());
        System.setProperty("cortex.git.sync-enabled", "false");
        repo.setGitSyncEnabled(false);
        repo.init("proj-streamable");
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

    private URI mcpUri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private HttpResponse<String> postWithHeaders(String path, String body, Map<String, String> headers) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(mcpUri(path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));

        headers.forEach(builder::header);
        return HTTP_CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void ca1_initializeERequisicoesComHeaderProtocolVersionRetornamHeaderMcpProtocolVersion() throws Exception {
        // 1. Initialize deve responder com header mcp-protocol-version
        String initPayload = """
                {
                  "jsonrpc": "2.0",
                  "id": 0,
                  "method": "initialize",
                  "params": {
                    "protocolVersion": "2025-06-18",
                    "capabilities": {},
                    "clientInfo": {"name": "codex-test", "version": "1.0"}
                  }
                }
                """;

        HttpResponse<String> initResp = postWithHeaders("/mcp/sse", initPayload, Map.of(
                "Accept", "text/event-stream, application/json",
                "mcp-protocol-version", "2025-06-18"
        ));

        assertEquals(200, initResp.statusCode());
        Optional<String> protoHeader = initResp.headers().firstValue("mcp-protocol-version");
        assertTrue(protoHeader.isPresent(), "Resposta HTTP deve conter o cabeçalho 'mcp-protocol-version'");
        assertEquals("2025-06-18", protoHeader.get());

        // 2. Requisição subsequente de tools/list com o header deve manter o cabeçalho na resposta
        String listPayload = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """;

        HttpResponse<String> listResp = postWithHeaders("/mcp/sse", listPayload, Map.of(
                "Accept", "text/event-stream, application/json",
                "mcp-protocol-version", "2025-06-18"
        ));

        assertEquals(200, listResp.statusCode());
        Optional<String> listProtoHeader = listResp.headers().firstValue("mcp-protocol-version");
        assertTrue(listProtoHeader.isPresent(), "Resposta de tools/list deve conter o cabeçalho 'mcp-protocol-version'");
        assertEquals("2025-06-18", listProtoHeader.get());
    }

    @Test
    void ca2_respostasJsonPossuemContentLengthExatoEmBytesUtf8EEscritaSemTruncamento() throws Exception {
        String listPayload = """
                {"jsonrpc":"2.0","id":42,"method":"tools/list","params":{}}
                """;

        HttpResponse<String> response = postWithHeaders("/mcp/sse", listPayload, Map.of(
                "Accept", "application/json"
        ));

        assertEquals(200, response.statusCode());
        Optional<String> clHeader = response.headers().firstValue("content-length");
        assertTrue(clHeader.isPresent(), "Cabeçalho Content-Length deve estar presente");

        int declaredLength = Integer.parseInt(clHeader.get());
        byte[] actualBytes = response.body().getBytes(StandardCharsets.UTF_8);
        assertEquals(actualBytes.length, declaredLength,
                "Content-Length (" + declaredLength + ") deve ser exatamente o número de bytes UTF-8 (" + actualBytes.length + ")");
    }

    @Test
    void ca3_headerMcpSessionIdEEcoadoNaRespostaHttp() throws Exception {
        String testSessionId = "session-codex-streamable-12345";
        String payload = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """;

        HttpResponse<String> response = postWithHeaders("/mcp/sse", payload, Map.of(
                "Accept", "application/json",
                "mcp-session-id", testSessionId
        ));

        assertEquals(200, response.statusCode());
        Optional<String> sessionHeader = response.headers().firstValue("mcp-session-id");
        assertTrue(sessionHeader.isPresent(), "Cabeçalho 'mcp-session-id' deve ser ecoado na resposta HTTP");
        assertEquals(testSessionId, sessionHeader.get());
    }

    @Test
    void ca4_toolsListComMetaProgressTokenRetornaListaCompletaDasNoveFerramentas() throws Exception {
        String codexPayload = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"progressToken":0}}}
                """;

        HttpResponse<String> response = postWithHeaders("/mcp/sse", codexPayload, Map.of(
                "Accept", "text/event-stream, application/json"
        ));

        assertEquals(200, response.statusCode());
        JsonNode json = mapper.readTree(response.body());
        assertEquals(1, json.path("id").asInt(), "ID deve ser preservado como 1");
        assertTrue(json.has("result"), "Deve ter campo result");
        JsonNode tools = json.path("result").path("tools");
        assertTrue(tools.isArray(), "tools deve ser uma lista");
        assertEquals(9, tools.size(), "Cortex deve expor exatamente as 9 ferramentas canônicas");
    }

    @Test
    void ca5_respostasDeErroEMetodoNaoEncontradoMantemContentLengthExato() throws Exception {
        // Erro -32601 Method not found
        String methodNotFound = """
                {"jsonrpc":"2.0","id":10,"method":"unknown/method","params":{}}
                """;
        HttpResponse<String> nfResp = postWithHeaders("/mcp/sse", methodNotFound, Map.of());
        assertEquals(200, nfResp.statusCode());
        int nfDeclaredLength = Integer.parseInt(nfResp.headers().firstValue("content-length").orElse("0"));
        assertEquals(nfResp.body().getBytes(StandardCharsets.UTF_8).length, nfDeclaredLength);

        // Erro -32700 Parse error
        HttpResponse<String> parseErrResp = postWithHeaders("/mcp/sse", "invalid-json", Map.of());
        assertEquals(400, parseErrResp.statusCode());
        int peDeclaredLength = Integer.parseInt(parseErrResp.headers().firstValue("content-length").orElse("0"));
        assertEquals(parseErrResp.body().getBytes(StandardCharsets.UTF_8).length, peDeclaredLength);

        // Notificação status 202
        HttpResponse<String> notifResp = postWithHeaders("/mcp/sse", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}", Map.of());
        assertEquals(202, notifResp.statusCode());
        assertEquals(0, notifResp.body().getBytes(StandardCharsets.UTF_8).length);
        assertEquals("0", notifResp.headers().firstValue("content-length").orElse("-1"));
    }

    @Test
    void ca6_fluxoCompletoDeInicializacaoCodexInitializeNotificationToolsList() throws Exception {
        // 1. Initialize
        String initPayload = """
                {"jsonrpc":"2.0","id":0,"method":"initialize","params":{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"codex-mcp-client","version":"0.164.0-alpha.11.1"}}}
                """;
        HttpResponse<String> initResp = postWithHeaders("/mcp/sse", initPayload, Map.of(
                "Accept", "text/event-stream, application/json",
                "mcp-protocol-version", "2025-06-18"
        ));
        assertEquals(200, initResp.statusCode());
        assertEquals("2025-06-18", initResp.headers().firstValue("mcp-protocol-version").orElse(null));

        // 2. notifications/initialized
        String notifPayload = """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """;
        HttpResponse<String> notifResp = postWithHeaders("/mcp/sse", notifPayload, Map.of(
                "Accept", "text/event-stream, application/json"
        ));
        assertEquals(202, notifResp.statusCode());

        // 3. tools/list
        String toolsPayload = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{"_meta":{"progressToken":0}}}
                """;
        HttpResponse<String> toolsResp = postWithHeaders("/mcp/sse", toolsPayload, Map.of(
                "Accept", "text/event-stream, application/json",
                "mcp-protocol-version", "2025-06-18"
        ));
        assertEquals(200, toolsResp.statusCode());
        assertEquals(9, mapper.readTree(toolsResp.body()).path("result").path("tools").size());
        assertEquals("2025-06-18", toolsResp.headers().firstValue("mcp-protocol-version").orElse(null));
    }
}
