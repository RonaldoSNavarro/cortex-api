package com.cortex.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeGraphTest {

    private KnowledgeGraph graph;

    @BeforeEach
    void setUp() {
        graph = new KnowledgeGraph();
    }

    @Test
    void testWikilinksAndBacklinks() {
        MemoryPage targetPage = new MemoryPage();
        targetPage.setId("consorcio-regras-gerais");
        targetPage.setContent("Visão geral do sistema de consórcio.");
        graph.indexPage(targetPage);

        MemoryPage citingPage = new MemoryPage();
        citingPage.setId("lance-embutido-apuracao");
        citingPage.setContent("Esta apuração segue as normas de [[consorcio-regras-gerais]] e BACEN.");
        graph.indexPage(citingPage);

        Set<String> backlinks = graph.getBacklinks("consorcio-regras-gerais");
        assertTrue(backlinks.contains("lance-embutido-apuracao"));

        Set<String> outlinks = graph.getOutlinks("lance-embutido-apuracao");
        assertTrue(outlinks.contains("consorcio-regras-gerais"));
    }

    @Test
    void testImpactOnSupersede() {
        MemoryPage oldPage = new MemoryPage();
        oldPage.setId("old-auth-service");
        oldPage.setContent("Configuração antiga de autenticação.");
        graph.indexPage(oldPage);

        MemoryPage dependentPage = new MemoryPage();
        dependentPage.setId("jwt-filter-config");
        dependentPage.setContent("Utiliza o [[old-auth-service]] para tokens.");
        graph.indexPage(dependentPage);

        Set<String> impacted = graph.getImpactedPagesOnSupersede("old-auth-service");
        assertEquals(1, impacted.size());
        assertTrue(impacted.contains("jwt-filter-config"));
    }
}
