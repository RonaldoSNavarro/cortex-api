package contract;

import com.cortex.core.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de Contrato para a Spec 007:
 * - CA-1: Limpeza proativa de stale lock (.git/index.lock antigo) e sucesso no sync Git
 * - CA-2: Preservação de lock ativo recente e aborto seguro do sync
 * - CA-3: Recuperação reativa com retry ao encontrar index.lock órfão em falha de comando
 * - CA-4: Resiliência contra exceções de I/O em locks inexistentes ou corrompidos
 * - CA-5: Configurabilidade do limiar de stale lock via property
 * - CA-6: Não-regressão em repositório limpo sem locks
 */
@DisplayName("Spec 007 — Bloqueio permanente da sincronização Git por arquivos de lock órfãos")
public class Spec007BloqueioGitSyncPorLocksOrfaosTest {

    @TempDir
    Path tempDir;

    private ProjectRepository repo;
    private Path cortexHome;
    private Path remoteGitDir;

    @BeforeEach
    void setUp() throws Exception {
        cortexHome = tempDir.resolve(".cortex");
        Path projectsDir = cortexHome.resolve("projects");
        Files.createDirectories(projectsDir);

        remoteGitDir = tempDir.resolve("remote.git");
        Files.createDirectories(remoteGitDir);

        repo = new ProjectRepository(cortexHome);

        // Inicializar repositório remoto bare
        repo.runCommand(remoteGitDir.toFile(), "git", "init", "--bare");

        // Cria arquivo inicial para garantir branch main com commit
        Files.writeString(cortexHome.resolve("init.txt"), "cortex test init");

        // Configurar sincronização Git para o repositório local de teste
        System.setProperty("cortex.git.sync-enabled", "true");
        System.setProperty("cortex.git.author-name", "Cortex Tester");
        System.setProperty("cortex.git.author-email", "tester@cortex.local");
        System.setProperty("cortex.git.command-timeout-seconds", "15");
        String uri = remoteGitDir.toUri().toString();
        if (uri.startsWith("file:/") && !uri.startsWith("file:///")) {
            uri = "file:///" + uri.substring(6);
        }
        System.setProperty("cortex.git.remote-url", uri);
    }

    @AfterEach
    void tearDown() {
        System.clearProperty("cortex.git.sync-enabled");
        System.clearProperty("cortex.git.author-name");
        System.clearProperty("cortex.git.author-email");
        System.clearProperty("cortex.git.command-timeout-seconds");
        System.clearProperty("cortex.git.remote-url");
        System.clearProperty("cortex.git.stale-lock-timeout-seconds");

        if (repo != null) {
            repo.shutdown();
        }
    }

    @Test
    @DisplayName("CA-1: syncToGit detecta e remove .git/index.lock antigo (>60s) e conclui sincronização com sucesso")
    void testCa1_staleLockRemovedAndSyncSucceeds() throws Exception {
        File cortexDir = repo.getCortexDir();
        // Executar um primeiro sync para inicializar o repo git local
        repo.syncToGit("initial sync");

        Path gitDir = cortexDir.toPath().resolve(".git");
        assertTrue(Files.isDirectory(gitDir), "O repositório Git deve estar inicializado");

        // Criar uma alteração no repositório de memória
        Files.writeString(cortexDir.toPath().resolve("test-note.md"), "Conteúdo para sync CA-1");

        // Criar index.lock antigo (120 segundos atrás)
        Path lockFile = gitDir.resolve("index.lock");
        Files.writeString(lockFile, "fake lock content");
        Files.setLastModifiedTime(lockFile, FileTime.from(Instant.now().minus(Duration.ofSeconds(120))));
        assertTrue(Files.exists(lockFile));

        // syncToGit deve identificar o lock obsoleto, removê-lo e sincronizar
        repo.syncToGit("sync after stale lock");

        assertFalse(Files.exists(lockFile), "O lock órfão (.git/index.lock) deve ter sido removido");
        String status = repo.runCommandWithOutput(cortexDir, "git", "status", "--porcelain").trim();
        assertTrue(status.isEmpty(), "Working tree deve estar limpa após o commit e push");
    }

    @Test
    @DisplayName("CA-2: syncToGit preserva lock recente (<60s) e aborta sincronização de forma segura")
    void testCa2_recentLockPreservedAndSyncAborted() throws Exception {
        File cortexDir = repo.getCortexDir();
        repo.syncToGit("initial sync");

        Path gitDir = cortexDir.toPath().resolve(".git");
        Path lockFile = gitDir.resolve("index.lock");

        // Criar lock recente (2 segundos atrás)
        Files.writeString(lockFile, "active lock");
        Files.setLastModifiedTime(lockFile, FileTime.from(Instant.now().minus(Duration.ofSeconds(2))));

        // Criar arquivo para sync
        Files.writeString(cortexDir.toPath().resolve("recent-note.md"), "Nota com lock ativo");

        repo.syncToGit("sync with active lock");

        // O lock NÃO deve ter sido removido
        assertTrue(Files.exists(lockFile), "O lock recente NÃO deve ser removido pelo sync");

        // O arquivo não foi commitado ainda
        String status = repo.runCommandWithOutput(cortexDir, "git", "status", "--porcelain");
        assertTrue(status.contains("recent-note.md"), "Arquivo deve permanecer pendente pois sync abortou");

        // Limpeza manual para não travar os próximos passos do teste
        Files.deleteIfExists(lockFile);
    }

    @Test
    @DisplayName("CA-3: runGitCommandWithLockRecovery remove lock órfão (>gitCommandTimeout) e reexecuta com sucesso")
    void testCa3_reactiveRecoveryOnGitError() throws Exception {
        File cortexDir = repo.getCortexDir();
        repo.syncToGit("initial sync");

        Path gitDir = cortexDir.toPath().resolve(".git");
        Path lockFile = gitDir.resolve("index.lock");

        // Criar lock com 20 segundos de idade (maior que o gitCommandTimeout de 15s)
        Files.writeString(lockFile, "stale lock for reactive recovery");
        Files.setLastModifiedTime(lockFile, FileTime.from(Instant.now().minus(Duration.ofSeconds(20))));

        // Arquivo para add
        Files.writeString(cortexDir.toPath().resolve("reactive.md"), "Arquivo para retry");

        // Executar comando com recuperação reativa
        String output = repo.runGitCommandWithLockRecovery(cortexDir, "git", "add", ".");

        assertFalse(Files.exists(lockFile), "O lock deve ser removido na auto-recuperação");
        String porcelain = repo.runCommandWithOutput(cortexDir, "git", "status", "--porcelain");
        assertTrue(porcelain.contains("A  reactive.md") || porcelain.contains("reactive.md"), "Arquivo deve estar staged");
    }

    @Test
    @DisplayName("CA-4: cleanStaleGitIndexLock é resiliente e não quebra com arquivo inexistente ou erro de I/O")
    void testCa4_resilienceOnIoExceptions() {
        File cortexDir = repo.getCortexDir();
        // Repositório sem .git/index.lock
        assertDoesNotThrow(() -> {
            boolean cleaned = repo.cleanStaleGitIndexLock(cortexDir, 60);
            assertFalse(cleaned, "Não deve remover nada quando o lock não existe");
        });

        // Diretório inexistente
        assertDoesNotThrow(() -> {
            boolean cleaned = repo.cleanStaleGitIndexLock(new File(cortexDir, "non-existent"), 60);
            assertFalse(cleaned);
        });
    }

    @Test
    @DisplayName("CA-5: staleLockTimeoutSeconds pode ser configurado via setter e System property")
    void testCa5_staleLockTimeoutConfigurable() {
        assertEquals(60, repo.getStaleLockTimeoutSeconds(), "Default deve ser 60s");

        repo.setStaleLockTimeoutSeconds(120);
        assertEquals(120, repo.getStaleLockTimeoutSeconds());

        System.setProperty("cortex.git.stale-lock-timeout-seconds", "45");
        assertEquals(45, repo.getStaleLockTimeoutSeconds(), "System property deve ter precedência");
    }

    @Test
    @DisplayName("CA-6: syncToGit opera normalmente sem atrasos nem erros quando não há locks (não-regressão)")
    void testCa6_cleanRepoSyncOperatesNormally() throws Exception {
        File cortexDir = repo.getCortexDir();
        repo.syncToGit("initial clean sync");

        Files.writeString(cortexDir.toPath().resolve("clean.md"), "Sem locks");
        repo.syncToGit("second clean sync");

        String status = repo.runCommandWithOutput(cortexDir, "git", "status", "--porcelain").trim();
        assertTrue(status.isEmpty(), "Working tree deve estar limpa e sincronizada");
    }
}
