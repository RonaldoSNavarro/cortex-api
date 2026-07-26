package com.cortex.api.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class McpConfigTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void negotiateProtocolVersion_shouldAdvertiseCodexVersion_whenLegacySdkDowngradesHandshake()
            throws Exception {
        String legacyResponse = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "result": {
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "serverInfo": {"name": "cortex", "version": "1.0.0"}
              }
            }
            """;

        String negotiatedResponse = McpConfig.negotiateProtocolVersion(
            mapper,
            legacyResponse,
            McpConfig.CODEX_PROTOCOL_VERSION
        );

        JsonNode result = mapper.readTree(negotiatedResponse).path("result");
        assertEquals(McpConfig.CODEX_PROTOCOL_VERSION, result.path("protocolVersion").asText());
    }

    @Test
    void negotiateProtocolVersion_shouldPreserveLegacyVersion_forOtherClients() throws Exception {
        String legacyResponse = """
            {
              "jsonrpc": "2.0",
              "id": 1,
              "result": {"protocolVersion": "2024-11-05"}
            }
            """;

        String negotiatedResponse = McpConfig.negotiateProtocolVersion(
            mapper,
            legacyResponse,
            McpConfig.LEGACY_PROTOCOL_VERSION
        );

        JsonNode result = mapper.readTree(negotiatedResponse).path("result");
        assertEquals(McpConfig.LEGACY_PROTOCOL_VERSION, result.path("protocolVersion").asText());
    }
}
