package com.cortex.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InMemoryLexicalIndexTest {

    private InMemoryLexicalIndex index;

    @BeforeEach
    void setUp() {
        index = new InMemoryLexicalIndex();
    }

    @Test
    void testBM25Ranking() {
        MemoryPage doc1 = new MemoryPage();
        doc1.setId("page-jackson-overview");
        doc1.setType(MemoryType.NOTE);
        doc1.setTags(List.of("jackson", "json"));
        doc1.setContent("Jackson é uma biblioteca para manipulação de JSON no ecossistema Java.");
        index.indexPage(doc1);

        MemoryPage doc2 = new MemoryPage();
        doc2.setId("rule-jackson-deserialization");
        doc2.setType(MemoryType.RULE);
        doc2.setTags(List.of("jackson", "mcp", "config"));
        doc2.setContent("Sempre configure ObjectMapper com DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES desabilitado para MCP.");
        index.indexPage(doc2);

        List<SearchResult> results = index.search("jackson deserialization", 10);
        assertFalse(results.isEmpty());
        // doc2 deve ter score maior devido a 'deserialization' + tipo RULE
        assertEquals("rule-jackson-deserialization", results.get(0).getPage().getId());
        assertTrue(results.get(0).getScore() > 0);
        assertFalse(results.get(0).getSnippet().isEmpty());
    }

    @Test
    void testFacetedFiltering() {
        MemoryPage doc1 = new MemoryPage();
        doc1.setId("rule-1");
        doc1.setType(MemoryType.RULE);
        doc1.setTags(List.of("spring"));
        doc1.setContent("Regra sobre Spring Boot.");
        index.indexPage(doc1);

        MemoryPage doc2 = new MemoryPage();
        doc2.setId("decision-1");
        doc2.setType(MemoryType.DECISION);
        doc2.setTags(List.of("spring"));
        doc2.setContent("Decisão sobre Spring Boot.");
        index.indexPage(doc2);

        List<SearchResult> ruleOnly = index.search("type:rule spring", 10);
        assertEquals(1, ruleOnly.size());
        assertEquals("rule-1", ruleOnly.get(0).getPage().getId());

        List<SearchResult> decisionOnly = index.search("type:decision spring", 10);
        assertEquals(1, decisionOnly.size());
        assertEquals("decision-1", decisionOnly.get(0).getPage().getId());
    }

    @Test
    void testTagFiltering() {
        MemoryPage doc1 = new MemoryPage();
        doc1.setId("mcp-doc");
        doc1.setType(MemoryType.FACT);
        doc1.setTags(List.of("mcp", "sse"));
        doc1.setContent("Documentação de SSE.");
        index.indexPage(doc1);

        List<SearchResult> results = index.search("tags:mcp", 10);
        assertEquals(1, results.size());
        assertEquals("mcp-doc", results.get(0).getPage().getId());
    }
}
