package com.cortex.core;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ProjectRepositoryTest {

    private ProjectRepository repository;
    private String originalUserHome;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        originalUserHome = System.getProperty("user.home");
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        repository = new ProjectRepository(null, new SmartSynthesizerService());
    }

    @AfterEach
    void tearDown() {
        if (originalUserHome != null) {
            System.setProperty("user.home", originalUserHome);
        }
    }

    @Test
    void testGetProjectDirThrowsExceptionWhenNull() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            repository.init(null);
        });
        assertEquals("projectId cannot be null or empty", exception.getMessage());
    }

    @Test
    void testInitCreatesProjectStructure() throws IOException {
        String projectId = "test-project";
        repository.init(projectId);

        Path projectDir = Path.of(System.getProperty("user.home"), ".cortex", "projects", projectId);
        assertTrue(Files.exists(projectDir));
        assertTrue(Files.exists(projectDir.resolve("raw")));
        assertTrue(Files.exists(projectDir.resolve("pages")));
        assertTrue(Files.exists(projectDir.resolve("rules")));
        assertTrue(Files.exists(projectDir.resolve("schema.md")));
    }

    @Test
    void testWritePageAndLexicalSearch() throws IOException {
        String projectId = "test-search-project";
        repository.init(projectId);

        String resultMsg = repository.writePage(
                projectId,
                "rule",
                List.of("jackson", "mcp"),
                null,
                null,
                "Sempre use FAIL_ON_UNKNOWN_PROPERTIES=false ao desserializar mensagens MCP [[mcp-protocol]]."
        );

        assertNotNull(resultMsg);
        assertTrue(resultMsg.contains("Página salva"));

        List<SearchResult> searchResults = repository.searchLexical(projectId, "jackson mcp", 10);
        assertFalse(searchResults.isEmpty());
        assertTrue(searchResults.get(0).getScore() > 0);
        assertEquals(MemoryType.RULE, searchResults.get(0).getPage().getType());

        CortexStats stats = repository.getStats(projectId);
        assertEquals(1, stats.getTotalMemories());
        assertEquals(1, stats.getActiveMemories());
        assertTrue(stats.getTopTags().containsKey("jackson"));
    }

    @Test
    void testSupersedeImpactWarning() throws IOException {
        String projectId = "impact-project";
        repository.init(projectId);

        // 1. Cria página base
        String basePageId = "base-rule";
        repository.saveRaw(projectId, basePageId + ".md", "---\nid: " + basePageId + "\ntype: rule\nproject: " + projectId + "\nstatus: active\n---\n\nRegra base de arquitetura.");

        // 2. Cria página dependente com wikilink
        repository.saveRaw(projectId, "dep-page.md", "---\nid: dep-page\ntype: note\nproject: " + projectId + "\nstatus: active\n---\n\nDepende de [[" + basePageId + "]].");

        // Carrega no repositório
        repository.getIndex(projectId);

        // 3. Escreve nova página substituindo base-rule
        String writeResult = repository.writePage(
                projectId,
                "rule",
                List.of("arquitetura"),
                basePageId,
                null,
                "Nova regra de arquitetura modernizada."
        );

        assertTrue(writeResult.contains("Alerta de Impacto") || writeResult.contains("dep-page"));
    }

    @Test
    void testHandoff() throws IOException {
        String projectId = "handoff-project";
        repository.init(projectId);

        repository.writePage(projectId, "rule", List.of("regra1"), null, null, "Regra essencial de compilação.");
        String handoffReport = repository.handoff(projectId, "antigravity");

        assertNotNull(handoffReport);
        assertTrue(handoffReport.contains("Handoff Report"));
        assertTrue(handoffReport.contains("antigravity"));
        assertTrue(handoffReport.contains("Rules"));
    }
}
