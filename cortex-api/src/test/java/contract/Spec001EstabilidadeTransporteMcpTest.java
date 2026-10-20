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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de contrato para specs/001-estabilidade-transporte-mcp.md
 * Cobre: CA-1, CA-2, CA-3, CA-4, CA-5, CA-6, CA-7, CA-8, CA-9
 */
@SpringBootTest(classes = CortexApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class Spec001EstabilidadeTransporteMcpTest {

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
        repo.init("proj-mcp");
        postJson("/mcp/sse", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
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

    private HttpResponse<String> postJson(String path, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(mcpUri(path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        return HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    @Test
    void ca1_vinteRequisicoesPostConcorrentesLeemBodySincronamenteERespondemHttp200SemErrosDeAsyncContext() throws Exception {
        List<CompletableFuture<HttpResponse<String>>> futures = new ArrayList<>();

        for (int i = 1; i <= 20; i++) {
            final int id = i;
            final String payload = (i % 2 == 0)
                    ? """
                      {"jsonrpc":"2.0","id":%d,"method":"tools/list","params":{}}
                      """.formatted(id)
                    : """
                      {"jsonrpc":"2.0","id":%d,"method":"tools/call","params":{"name":"stats","arguments":{"project":"proj-mcp"}}}
                      """.formatted(id);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(mcpUri("/mcp/sse"))
                    .header("Content-Type", "application/json")
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();

            futures.add(HTTP_CLIENT.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(15, TimeUnit.SECONDS);

        for (int i = 0; i < futures.size(); i++) {
            HttpResponse<String> resp = futures.get(i).get();
            assertEquals(200, resp.statusCode(), "Requisição #" + (i + 1) + " deve responder HTTP 200");
            JsonNode json = mapper.readTree(resp.body());
            assertEquals(i + 1, json.path("id").asInt(), "O id correlacionado deve bater com a requisição");
            assertTrue(json.has("result"), "Deve conter campo result válido: " + resp.body());
        }
    }

    @Test
    void ca2_serializacaoDeToolsCallContemApenasUmaChaveTypeTextPorItemDeConteudo() throws Exception {
        String payload = """
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"capture","arguments":{"project":"proj-mcp","type":"fact","content":"Fato de teste CA-2"}}}
                """;

        HttpResponse<String> response = postJson("/mcp/sse", payload);
        assertEquals(200, response.statusCode());

        String rawBody = response.body();
        assertTrue(rawBody.contains("\"type\":\"text\""), "Deve conter a propriedade \"type\":\"text\": " + rawBody);
        assertFalse(rawBody.contains("\"type\":\"text\",\"type\":\"text\""),
                "Não deve serializar chave \"type\":\"text\" duplicada: " + rawBody);
    }

    @Test
    void ca3_requisicoesConcorrentesComMesmoId1RecebemPayloadsCorretosSemColisao() throws Exception {
        String toolsListPayload = """
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """;
        String statsCallPayload = """
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"stats","arguments":{"project":"proj-mcp"}}}
                """;

        HttpRequest reqList = HttpRequest.newBuilder()
                .uri(mcpUri("/mcp/sse"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(toolsListPayload, StandardCharsets.UTF_8))
                .build();

        HttpRequest reqStats = HttpRequest.newBuilder()
                .uri(mcpUri("/mcp/sse"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(statsCallPayload, StandardCharsets.UTF_8))
                .build();

        CompletableFuture<HttpResponse<String>> futureList =
                HTTP_CLIENT.sendAsync(reqList, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        CompletableFuture<HttpResponse<String>> futureStats =
                HTTP_CLIENT.sendAsync(reqStats, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        HttpResponse<String> respList = futureList.get(10, TimeUnit.SECONDS);
        HttpResponse<String> respStats = futureStats.get(10, TimeUnit.SECONDS);

        assertEquals(200, respList.statusCode());
        assertEquals(200, respStats.statusCode());

        JsonNode jsonList = mapper.readTree(respList.body());
        JsonNode jsonStats = mapper.readTree(respStats.body());

        assertEquals(1, jsonList.path("id").asInt(), "Resposta de tools/list deve preservar id: 1");
        assertEquals(1, jsonStats.path("id").asInt(), "Resposta de tools/call deve preservar id: 1");

        assertTrue(jsonList.path("result").has("tools"),
                "Chamada tools/list deve receber result.tools: " + respList.body());
        assertTrue(jsonStats.path("result").has("content"),
                "Chamada tools/call deve receber result.content: " + respStats.body());
    }

    @Test
    void ca4_initializeAceitaVersoesDeProtocolo2025E2026RefletindoValorSolicitado() throws Exception {
        List<String> versions = List.of("2025-06-18", "2025-11-25", "2026-07-28");

        for (String version : versions) {
            String initPayload = """
                    {
                      "jsonrpc": "2.0",
                      "id": 1,
                      "method": "initialize",
                      "params": {
                        "protocolVersion": "%s",
                        "capabilities": {"elicitation": {"form": {}}},
                        "clientInfo": {"name": "test-client", "version": "1.0.0"}
                      }
                    }
                    """.formatted(version);

            HttpResponse<String> response = postJson("/mcp/sse", initPayload);
            assertEquals(200, response.statusCode());

            JsonNode root = mapper.readTree(response.body());
            assertEquals(version, root.path("result").path("protocolVersion").asText(),
                    "result.protocolVersion deve refletir exatamente a versão solicitada: " + version);
        }
    }

    @Test
    void ca5_metodoNaoSuportadoServerDiscoverRetornaErro32601EmMenosDe200ms() throws Exception {
        String payload = """
                {"jsonrpc":"2.0","id":1,"method":"server/discover","params":{"protocolVersion":"2026-07-28"}}
                """;

        long startMs = System.nanoTime();
        HttpResponse<String> response = postJson("/mcp/sse", payload);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startMs);

        assertEquals(200, response.statusCode());
        assertTrue(elapsedMs < 200, "Deve responder em menos de 200ms, mas levou " + elapsedMs + "ms");

        JsonNode json = mapper.readTree(response.body());
        assertEquals(1, json.path("id").asInt());
        assertEquals(-32601, json.path("error").path("code").asInt());
        assertEquals("Method not found", json.path("error").path("message").asText());
    }

    @Test
    void ca6_notificacaoSemIdRetornaStatus202ComCorpoVazioEmMenosDe200ms() throws Exception {
        String payload = """
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """;

        long startMs = System.nanoTime();
        HttpResponse<String> response = postJson("/mcp/sse", payload);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startMs);

        assertEquals(202, response.statusCode());
        assertEquals("", response.body(), "Notificação deve retornar corpo vazio");
        assertTrue(elapsedMs < 200, "Notificação deve retornar em menos de 200ms, mas levou " + elapsedMs + "ms");
    }

    @Test
    void ca7_notificacaoRootsListChangedSemSessaoSseRetorna202VazioSemTimeoutNoDefaultMcpSession() throws Exception {
        String payload = """
                {"jsonrpc":"2.0","method":"notifications/roots/list_changed"}
                """;

        long startMs = System.nanoTime();
        HttpResponse<String> response = postJson("/mcp/sse", payload);
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startMs);

        assertEquals(202, response.statusCode());
        assertEquals("", response.body());
        assertTrue(elapsedMs < 200);

        // Garante que a sessão MCP continua saudável e imediata após notifications/roots/list_changed
        HttpResponse<String> followUp = postJson("/mcp/sse", """
                {"jsonrpc":"2.0","id":99,"method":"tools/list","params":{}}
                """);
        assertEquals(200, followUp.statusCode());
        assertEquals(99, mapper.readTree(followUp.body()).path("id").asInt());
    }

    @Test
    void ca8_postComJsonMalformadoOuCorpoEmBrancoRetornaHttp400SemVazarPendingResponses() throws Exception {
        int pendingBefore = mcpConfig.getPendingResponses().size();

        HttpResponse<String> malformedResp = postJson("/mcp/sse", "{\"jsonrpc\":");
        assertEquals(400, malformedResp.statusCode());
        assertEquals(-32700, mapper.readTree(malformedResp.body()).path("error").path("code").asInt());

        HttpResponse<String> blankResp = postJson("/mcp/sse", "   ");
        assertEquals(400, blankResp.statusCode());
        assertEquals(-32700, mapper.readTree(blankResp.body()).path("error").path("code").asInt());

        assertEquals(pendingBefore, mcpConfig.getPendingResponses().size(),
                "Nenhuma entrada deve vazar em pendingResponses após JSON malformado ou em branco");
    }

    @Test
    void ca9_fluxoLegadoGetMcpSseSeguidoDePostMcpMessageContinuaOperacional() throws Exception {
        HttpRequest sseReq = HttpRequest.newBuilder()
                .uri(mcpUri("/mcp/sse"))
                .header("Accept", "text/event-stream")
                .GET()
                .build();

        HttpResponse<InputStream> sseResp = HTTP_CLIENT.send(sseReq, HttpResponse.BodyHandlers.ofInputStream());
        assertEquals(200, sseResp.statusCode());

        String eventLine;
        String dataLine;
        try (InputStream is = sseResp.body();
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            eventLine = reader.readLine();
            dataLine = reader.readLine();

            assertNotNull(eventLine);
            assertNotNull(dataLine);
            assertTrue(eventLine.contains("event: endpoint"), "SSE deve emitir event: endpoint, mas emitiu: " + eventLine);
            assertTrue(dataLine.contains("/mcp/sse"), "SSE deve informar o endpoint MCP em data:, mas emitiu: " + dataLine);

            // Em seguida, envia POST para /mcp/message?sessionId=legado e verifica resposta JSON-RPC no corpo do POST
            HttpResponse<String> postResp = postJson("/mcp/message?sessionId=legacy-client", """
                    {"jsonrpc":"2.0","id":42,"method":"tools/list","params":{}}
                    """);
            assertEquals(200, postResp.statusCode());
            JsonNode json = mapper.readTree(postResp.body());
            assertEquals(42, json.path("id").asInt());
            assertTrue(json.path("result").has("tools"));
        }
    }
}
