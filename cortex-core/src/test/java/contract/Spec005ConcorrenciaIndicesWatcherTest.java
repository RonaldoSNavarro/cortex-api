package contract;

import com.cortex.core.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Spec 005 — Concorrência em CortexConfigManager, Watcher, Índices e Shutdown")
public class Spec005ConcorrenciaIndicesWatcherTest {

    @TempDir
    Path tempDir;

    private Path cortexHome;
    private ProjectRepository repo;

    @BeforeEach
    void setUp() throws IOException {
        cortexHome = tempDir.resolve(".cortex");
        Files.createDirectories(cortexHome);
        System.setProperty("user.home", tempDir.toString());
        repo = new ProjectRepository(cortexHome);
        repo.setGitSyncEnabled(false);
    }

    @AfterEach
    void tearDown() {
        if (repo != null) {
            repo.shutdown();
        }
    }

    @Test
    @DisplayName("CA-1: Dez chamadas concorrentes a bootstrap persistem 10 projetos sem perda de dados")
    void testCa1_concurrentBootstrapTenProjects() throws Exception {
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);
        List<Future<String>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            futures.add(executor.submit(() -> {
                latch.countDown();
                latch.await();
                String pId = "proj-" + idx;
                Path pRoot = tempDir.resolve("repos").resolve(pId);
                Files.createDirectories(pRoot);
                return repo.bootstrap(pId, pRoot.toString());
            }));
        }

        for (Future<String> f : futures) {
            f.get(10, TimeUnit.SECONDS);
        }
        executor.shutdown();

        CortexConfigManager cfgManager = new CortexConfigManager();
        CortexConfig config = cfgManager.loadConfig();
        assertNotNull(config.getProjects(), "Projetos não podem ser nulos");

        // Todos os 10 projetos devem estar mapeados no config.yaml
        Collection<String> registeredIds = config.getProjects().values();
        for (int i = 0; i < threads; i++) {
            assertTrue(registeredIds.contains("proj-" + i), "Projeto proj-" + i + " deve estar no config.yaml");
        }
        assertEquals(10, registeredIds.size(), "Devem existir exatamente 10 registros");
    }

    @Test
    @DisplayName("CA-2: bootstrap com mesmo projectId e novo caminho substitui entrada anterior (mapeamento 1:1)")
    void testCa2_bootstrapReplacesOldPathForSameProjectId() throws Exception {
        String pId = "same-project";
        Path oldPath = tempDir.resolve("old-location");
        Path newPath = tempDir.resolve("new-location");
        Files.createDirectories(oldPath);
        Files.createDirectories(newPath);

        repo.bootstrap(pId, oldPath.toString());
        repo.bootstrap(pId, newPath.toString());

        CortexConfigManager cfgManager = new CortexConfigManager();
        CortexConfig config = cfgManager.loadConfig();

        long count = config.getProjects().values().stream().filter(id -> id.equals(pId)).count();
        assertEquals(1, count, "Deve existir exatamente 1 entrada mapeada para o projeto");
        assertEquals(pId, config.getProjects().get(newPath.toRealPath().toString()));
        assertNull(config.getProjects().get(oldPath.toRealPath().toString()), "Caminho antigo deve ter sido removido");
    }

    @Test
    @DisplayName("CA-3: Vinte chamadas concorrentes a registerProject criam exatamente um WatchService ativo")
    void testCa3_concurrentRegisterProjectAtomic() throws Exception {
        ProjectWatcherService watcher = new ProjectWatcherService();
        String pId = "watcher-project";
        Path pDir = tempDir.resolve("watcher-pdir");
        Files.createDirectories(pDir);

        InMemoryLexicalIndex index = new InMemoryLexicalIndex();
        KnowledgeGraph graph = new KnowledgeGraph();

        int threads = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(threads);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                try {
                    latch.countDown();
                    latch.await();
                    watcher.registerProject(pId, pDir, index, graph);
                } catch (Exception ignored) {
                }
            });
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));

        assertEquals(1, watcher.getActiveWatcherCount(), "Deve haver exatamente 1 WatchService criado para o projeto");
        watcher.shutdown();
    }

    @Test
    @DisplayName("CA-4: Remoção de arquivo com '.md' no meio do identificador desindexa ID correto (remove apenas sufixo final)")
    void testCa4_removePageSuffixPrecision() {
        InMemoryLexicalIndex index = new InMemoryLexicalIndex();
        KnowledgeGraph graph = new KnowledgeGraph();

        String filenameWithDots = "nota.md.v1.md";
        String expectedId = "nota.md.v1";

        MemoryPage page = new MemoryPage();
        page.setId(expectedId);
        page.setContent("Teste de remoção de sufixo.");
        index.indexPage(page);
        graph.indexPage(page);

        assertNotNull(index.getAllPages().get(expectedId));

        // Simula a lógica corrigida de extração do ID
        String extractedId = ProjectWatcherService.extractPageIdFromFilename(filenameWithDots);
        assertEquals(expectedId, extractedId);

        index.removePage(extractedId);
        graph.removePage(extractedId);

        assertNull(index.getAllPages().get(expectedId));
    }

    @Test
    @DisplayName("CA-5: 100 updates paralelos a 100 buscas no InMemoryLexicalIndex nunca enxergam página ausente")
    void testCa5_concurrentSearchAndIndexPage() throws Exception {
        InMemoryLexicalIndex index = new InMemoryLexicalIndex();
        String docId = "concurrent-page";

        MemoryPage initial = new MemoryPage();
        initial.setId(docId);
        initial.setType(MemoryType.FACT);
        initial.setStatus("active");
        initial.setContent("TermoEspecificoInconfundivel para teste de isolamento concorrente.");
        index.indexPage(initial);

        int operations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        AtomicInteger successfulSearches = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(operations * 2);

        // 100 indexPage concorrentes
        for (int i = 0; i < operations; i++) {
            final int revision = i;
            executor.submit(() -> {
                try {
                    MemoryPage updated = new MemoryPage();
                    updated.setId(docId);
                    updated.setType(MemoryType.FACT);
                    updated.setStatus("active");
                    updated.setContent("TermoEspecificoInconfundivel revisão " + revision);
                    index.indexPage(updated);
                } finally {
                    latch.countDown();
                }
            });
        }

        // 100 searches concorrentes
        for (int i = 0; i < operations; i++) {
            executor.submit(() -> {
                try {
                    List<SearchResult> results = index.search("TermoEspecificoInconfundivel", 10);
                    if (!results.isEmpty() && results.getFirst().getPage().getId().equals(docId)) {
                        successfulSearches.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(operations, successfulSearches.get(),
                "100% das buscas concorrentes devem encontrar a página sem nunca observar o estado intermediário de remoção");
    }

    @Test
    @DisplayName("CA-6: 100 updates paralelos a 100 leituras no KnowledgeGraph retornam grafo consistente sem exceção")
    void testCa6_concurrentIndexAndReadKnowledgeGraph() throws Exception {
        KnowledgeGraph graph = new KnowledgeGraph();
        String pageId = "origin-node";

        MemoryPage initial = new MemoryPage();
        initial.setId(pageId);
        initial.setContent("Referência para [[alvo-central]].");
        graph.indexPage(initial);

        int operations = 100;
        ExecutorService executor = Executors.newFixedThreadPool(8);
        AtomicInteger successfulReads = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(operations * 2);

        for (int i = 0; i < operations; i++) {
            final int rev = i;
            executor.submit(() -> {
                try {
                    MemoryPage p = new MemoryPage();
                    p.setId(pageId);
                    p.setContent("Referência para [[alvo-central]] revisão " + rev);
                    graph.indexPage(p);
                } finally {
                    latch.countDown();
                }
            });
        }

        for (int i = 0; i < operations; i++) {
            executor.submit(() -> {
                try {
                    Set<String> backlinks = graph.getBacklinks("alvo-central");
                    Set<String> outlinks = graph.getOutlinks(pageId);
                    if (backlinks.contains(pageId) && outlinks.contains("alvo-central")) {
                        successfulReads.incrementAndGet();
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertEquals(operations, successfulReads.get(),
                "100% das leituras concorrentes devem observar o link [[alvo-central]] sem exceções de concorrência");
    }

    @Test
    @DisplayName("CA-7: @PreDestroy em ProjectRepository encerra os executores sem lançar exceção")
    void testCa7_preDestroyGracefulShutdown() {
        assertFalse(repo.getGitSyncExecutor().isShutdown());
        assertDoesNotThrow(() -> repo.shutdown());
        assertTrue(repo.getGitSyncExecutor().isShutdown());
    }
}
