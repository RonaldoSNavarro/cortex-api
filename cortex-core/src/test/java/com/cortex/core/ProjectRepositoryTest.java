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
        // Mock user.home to use our TempDir so tests don't affect real projects
        System.setProperty("user.home", tempDir.toAbsolutePath().toString());
        repository = new ProjectRepository();
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
    void testGetProjectDirThrowsExceptionWhenEmpty() {
        Exception exception = assertThrows(IllegalArgumentException.class, () -> {
            repository.init("   ");
        });
        assertEquals("projectId cannot be null or empty", exception.getMessage());
    }

    @Test
    void testInitCreatesProjectStructure() throws IOException {
        String projectId = "test-project";
        repository.init(projectId);

        Path projectDir = Path.of(System.getProperty("user.home"), ".cortex", "projects", projectId);
        assertTrue(Files.exists(projectDir), "Project dir should exist");
        assertTrue(Files.exists(projectDir.resolve("raw")), "raw dir should exist");
        assertTrue(Files.exists(projectDir.resolve("pages")), "pages dir should exist");
        assertTrue(Files.exists(projectDir.resolve("rules")), "rules dir should exist");
        assertTrue(Files.exists(projectDir.resolve("schema.md")), "schema.md should exist");
    }

    @Test
    void testSearchReturnsEmptyWhenNoFiles() throws IOException {
        String projectId = "empty-project";
        repository.init(projectId);

        List<MemoryPage> results = repository.search(projectId, "cortex");
        assertNotNull(results);
        assertTrue(results.isEmpty(), "Search should return empty list for a new project");
    }
}
