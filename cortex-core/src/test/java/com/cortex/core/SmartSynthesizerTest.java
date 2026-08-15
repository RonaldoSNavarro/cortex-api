package com.cortex.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SmartSynthesizerTest {

    private SmartSynthesizerService service;

    @BeforeEach
    void setUp() {
        service = new SmartSynthesizerService();
    }

    @Test
    void testClusterPendingRawsByTag() {
        MemoryPage p1 = new MemoryPage();
        p1.setId("raw-1");
        p1.setTags(List.of("mcp", "sse"));
        p1.setContent("Observação 1 sobre MCP SSE.");

        MemoryPage p2 = new MemoryPage();
        p2.setId("raw-2");
        p2.setTags(List.of("mcp", "jackson"));
        p2.setContent("Observação 2 sobre MCP Jackson.");

        MemoryPage p3 = new MemoryPage();
        p3.setId("raw-3");
        p3.setTags(List.of("consorcio"));
        p3.setContent("Observação sobre cálculo de lance.");

        List<SmartSynthesizerService.MemoryCluster> clusters = service.clusterPendingRaws(List.of(p1, p2, p3));
        assertEquals(2, clusters.size());
        // O primeiro cluster deve ser 'mcp' com 2 notas
        assertEquals("mcp", clusters.get(0).getTopic());
        assertEquals(2, clusters.get(0).getNotes().size());
        assertEquals("consorcio", clusters.get(1).getTopic());
        assertEquals(1, clusters.get(1).getNotes().size());
    }

    @Test
    void testFilterByTopic() {
        MemoryPage p1 = new MemoryPage();
        p1.setId("raw-1");
        p1.setTags(List.of("mcp"));
        p1.setContent("Nota MCP");

        MemoryPage p2 = new MemoryPage();
        p2.setId("raw-2");
        p2.setTags(List.of("spring"));
        p2.setContent("Nota Spring");

        List<MemoryPage> filtered = service.filterByTopic(List.of(p1, p2), "spring");
        assertEquals(1, filtered.size());
        assertEquals("raw-2", filtered.get(0).getId());
    }
}
