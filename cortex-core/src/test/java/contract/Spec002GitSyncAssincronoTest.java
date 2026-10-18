package contract;

import com.cortex.core.ProjectRepository;
import com.cortex.core.SearchResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de contrato para specs/002-git-sync-assincrono-e-unificacao-mcp.md
 * Cobre: CA-1, CA-2, CA-3, CA-4
 */
class Spec002GitSyncAssincronoTest {

    @TempDir
    Path tempHome;

    private String originalUserHome;
    private String originalGitSyncProp;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        originalGitSyncProp = System.getProperty("cortex.git.sync-enabled");
        System.setProperty("user.home", tempHome.toString());
        System.clearProperty("cortex.git.sync-enabled");
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
    void ca1_writePageRetornaEmMenosDe500msMesmoComGitSyncLentoEAtualizaIndiceLexico() throws Exception {
        ProjectRepository repo = new ProjectRepository();
        repo.setGitSyncEnabled(true);
        CountDownLatch gitStarted = new CountDownLatch(1);
        CountDownLatch releaseGit = new CountDownLatch(1);

        repo.setGitSyncHandler((proj, commitMsg) -> {
            gitStarted.countDown();
            try {
                releaseGit.await(3, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        repo.init("proj-ca1");

        long startMs = System.nanoTime();
        String result = repo.writePage(
                "proj-ca1",
                "decision",
                List.of("arquitetura", "git"),
                null,
                null,
                "Decisão arquitetural sobre sincronização assíncrona do Git."
        );
        long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startMs);

        try {
            assertTrue(result.contains("Página salva com sucesso"), "Deve confirmar gravação da página");
            assertTrue(elapsedMs < 500, "writePage deve retornar em menos de 500ms, mas levou " + elapsedMs + "ms");

            // Arquivo .md deve existir em disco imediatamente
            Path pagesDir = tempHome.resolve(".cortex").resolve("projects").resolve("proj-ca1").resolve("pages");
            try (var files = Files.list(pagesDir)) {
                assertEquals(1, files.filter(p -> p.toString().endsWith(".md")).count());
            }

            // Índice léxico deve retornar a página imediatamente via query
            List<SearchResult> searchResults = repo.searchLexical("proj-ca1", "sincronização assíncrona", 5);
            assertFalse(searchResults.isEmpty(), "Página recém-gravada deve estar imediatamente disponível no índice léxico");
            assertTrue(gitStarted.await(1, TimeUnit.SECONDS), "Tarefa de sincronização Git deve ter iniciado em background");
        } finally {
            releaseGit.countDown();
            repo.getPendingGitSyncFuture("proj-ca1").get(3, TimeUnit.SECONDS);
        }
    }

    @Test
    void ca2_cincoChamadasConcorrentesWritePageExecutamGitSyncDeFormaSerializada() throws Exception {
        ProjectRepository repo = new ProjectRepository();
        repo.setGitSyncEnabled(true);
        repo.init("proj-ca2");

        AtomicInteger activeGitTasks = new AtomicInteger(0);
        AtomicInteger maxConcurrentGitTasks = new AtomicInteger(0);
        List<String> executionOrder = new CopyOnWriteArrayList<>();

        repo.setGitSyncHandler((proj, commitMsg) -> {
            int current = activeGitTasks.incrementAndGet();
            maxConcurrentGitTasks.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(40);
                executionOrder.add(commitMsg);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                activeGitTasks.decrementAndGet();
            }
        });

        List<CompletableFuture<String>> writeFutures = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            final int idx = i;
            writeFutures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return repo.writePage(
                            "proj-ca2",
                            "fact",
                            List.of("concorrencia"),
                            null,
                            null,
                            "Conteúdo concorrente número " + idx
                    );
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
        }

        CompletableFuture.allOf(writeFutures.toArray(new CompletableFuture[0])).get(5, TimeUnit.SECONDS);
        repo.getPendingGitSyncFuture("proj-ca2").get(5, TimeUnit.SECONDS);

        assertEquals(5, executionOrder.size(), "Todas as 5 sincronizações Git devem ter sido executadas");
        assertEquals(1, maxConcurrentGitTasks.get(), "As sincronizações Git do mesmo projeto nunca devem rodar em paralelo");

        Path pagesDir = tempHome.resolve(".cortex").resolve("projects").resolve("proj-ca2").resolve("pages");
        try (var files = Files.list(pagesDir)) {
            assertEquals(5, files.filter(p -> p.toString().endsWith(".md")).count(), "Todos os 5 arquivos .md devem estar persistidos");
        }
    }

    @Test
    void ca3_falhaOuTimeoutNoSubprocessoGitEmBackgroundMantemArquivoMdEIndiceIntactos() throws Exception {
        ProjectRepository repo = new ProjectRepository();
        repo.setGitSyncEnabled(true);
        repo.setGitCommandTimeoutSeconds(1);
        repo.init("proj-ca3");

        repo.setGitSyncHandler((proj, commitMsg) -> {
            throw new RuntimeException("Simulated git push SSH failure / timeout");
        });

        String response = assertDoesNotThrow(() -> repo.writePage(
                "proj-ca3",
                "gotcha",
                List.of("git", "resiliencia"),
                null,
                null,
                "Mesmo com falha no git push remoto, esta página permanece intacta."
        ));

        repo.getPendingGitSyncFuture("proj-ca3").get(3, TimeUnit.SECONDS);

        assertTrue(response.contains("Página salva com sucesso"));
        List<SearchResult> results = repo.searchLexical("proj-ca3", "permanece intacta", 5);
        assertEquals(1, results.size(), "Página deve permanecer indexada após falha no Git em background");

        Path pagesDir = tempHome.resolve(".cortex").resolve("projects").resolve("proj-ca3").resolve("pages");
        try (var files = Files.list(pagesDir)) {
            assertEquals(1, files.filter(p -> p.toString().endsWith(".md")).count(), "Arquivo .md deve permanecer intacto em disco");
        }
    }

    @Test
    void ca4_gitSyncDesabilitadoPorConfiguracaoPersisteMdSemInvocarGit() throws Exception {
        System.setProperty("cortex.git.sync-enabled", "false");
        ProjectRepository repo = new ProjectRepository();
        repo.init("proj-ca4");

        AtomicInteger gitInvocations = new AtomicInteger(0);
        repo.setGitSyncHandler((proj, commitMsg) -> gitInvocations.incrementAndGet());

        String response = repo.writePage(
                "proj-ca4",
                "rule",
                List.of("config"),
                null,
                null,
                "Regra persistida com git sync desabilitado."
        );

        repo.getPendingGitSyncFuture("proj-ca4").get(2, TimeUnit.SECONDS);

        assertTrue(response.contains("Página salva com sucesso"));
        assertEquals(0, gitInvocations.get(), "Nenhum subprocesso/handler Git deve ser invocado quando cortex.git.sync-enabled=false");
        assertEquals(1, repo.searchLexical("proj-ca4", "git sync desabilitado", 5).size());
    }
}
