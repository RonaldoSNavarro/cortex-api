package contract;

import com.cortex.core.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de contrato para specs/003-alinhamento-docker-e-config-clientes.md
 * Cobre: CA-1, CA-2, CA-3, CA-4, CA-5
 */
class Spec003AlinhamentoDockerCaminhosTest {

    @TempDir
    Path tempDir;

    private String originalUserHome;
    private String originalGitSyncProp;

    @BeforeEach
    void setUp() {
        originalUserHome = System.getProperty("user.home");
        originalGitSyncProp = System.getProperty("cortex.git.sync-enabled");
        System.setProperty("user.home", tempDir.resolve("home").toString());
        System.setProperty("cortex.git.sync-enabled", "false");
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

    private Path findRepoRoot() {
        Path current = Paths.get("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve("docker-compose.yml")) && Files.exists(current.resolve("specs"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Não foi possível localizar a raiz do repositório cortex");
    }

    @Test
    void ca1_dockerComposeMapeiaGDevProjetosESemReferenciaAFDevProjetos() throws Exception {
        Path composeFile = findRepoRoot().resolve("docker-compose.yml");
        String content = Files.readString(composeFile);

        assertTrue(content.contains("- G:\\Dev\\Projetos:/projetos"),
                "docker-compose.yml deve declarar '- G:\\Dev\\Projetos:/projetos'");
        assertFalse(content.toLowerCase().contains("f:\\dev\\projetos") || content.toLowerCase().contains("f:/dev/projetos"),
                "docker-compose.yml não deve conter referências a F:\\Dev\\Projetos");
    }

    @Test
    void ca2_caminhosDoHostGDevProjetosEMntGDevProjetosResolvemParaRaizDoContainerEmBootstrapEPromoteRules() throws Exception {
        Path containerProjetosDir = tempDir.resolve("projetos");
        Files.createDirectories(containerProjetosDir);

        ProjectRepository repo = new ProjectRepository();
        repo.setContainerProjectsRoot(containerProjetosDir);

        Path expectedProjectDir = containerProjetosDir.resolve("cortex");

        assertEquals(expectedProjectDir, repo.resolveProjectPath("G:\\Dev\\Projetos\\cortex"));
        assertEquals(expectedProjectDir, repo.resolveProjectPath("g:/Dev/Projetos/cortex"));
        assertEquals(expectedProjectDir, repo.resolveProjectPath("/mnt/g/Dev/Projetos/cortex"));

        // Verifica bootstrap com caminho Windows G:\Dev\Projetos\cortex traduzindo para /projetos/cortex
        String bootstrappedId = repo.bootstrap("cortex", "G:\\Dev\\Projetos\\cortex");
        assertEquals("cortex", bootstrappedId);
        assertTrue(Files.exists(expectedProjectDir.resolve(".agents/skills/cortex-conventions/SKILL.md")),
                "bootstrap deve criar a skill dentro do diretório traduzido /projetos/cortex");

        // Verifica promoteRules com caminho WSL /mnt/g/Dev/Projetos/cortex/.agents/AGENTS.md traduzindo para /projetos/cortex
        repo.writePage("cortex", "rule", List.of("docker"), null, null, "Usar exclusivamente G:\\Dev\\Projetos.");
        repo.promoteRules("cortex", "/mnt/g/Dev/Projetos/cortex/.agents/AGENTS.md");

        Path expectedAgentsMd = expectedProjectDir.resolve(".agents").resolve("AGENTS.md");
        assertTrue(Files.exists(expectedAgentsMd), "promoteRules deve gravar em /projetos/cortex/.agents/AGENTS.md");
        assertTrue(Files.readString(expectedAgentsMd).contains("Usar exclusivamente G:\\Dev\\Projetos."));
    }

    @Test
    void ca3_caminhoEmDriveNaoMapeadoFRecusadoComIllegalArgumentExceptionSemCriarPastasInvalidas() throws Exception {
        Path containerProjetosDir = tempDir.resolve("projetos");
        Files.createDirectories(containerProjetosDir);

        ProjectRepository repo = new ProjectRepository();
        repo.setContainerProjectsRoot(containerProjetosDir);

        assertThrows(IllegalArgumentException.class,
                () -> repo.bootstrap("cortex", "F:\\Dev\\Projetos\\cortex"),
                "Caminho F:\\Dev\\Projetos\\cortex deve ser rejeitado com IllegalArgumentException");

        assertThrows(IllegalArgumentException.class,
                () -> repo.bootstrap("cortex", "/mnt/f/Dev/Projetos/cortex"),
                "Caminho /mnt/f/Dev/Projetos/cortex deve ser rejeitado com IllegalArgumentException");

        repo.init("cortex");
        repo.writePage("cortex", "rule", List.of("seguranca"), null, null, "Regra de teste CA-3");

        assertThrows(IllegalArgumentException.class,
                () -> repo.promoteRules("cortex", "F:\\Dev\\Projetos\\cortex\\.agents\\AGENTS.md"),
                "promoteRules deve rejeitar caminho em F:\\");

        assertThrows(IllegalArgumentException.class,
                () -> repo.promoteRules("cortex", "/mnt/f/Dev/Projetos/cortex/.agents/AGENTS.md"),
                "promoteRules deve rejeitar caminho em /mnt/f/");

        assertFalse(Files.exists(containerProjetosDir.resolve("cortex")),
                "Nenhuma pasta deve ter sido traduzida ou criada em /projetos/cortex para o drive F:");
    }

    @Test
    void ca4_execucaoForaDoContainerOperaDiretamenteNoCaminhoLocalSemExigirBarraProjetos() throws Exception {
        Path nonExistentContainerRoot = tempDir.resolve("nao-existe-projetos");
        Path localProjectDir = tempDir.resolve("local-workspace").resolve("meu-app");
        Files.createDirectories(localProjectDir);

        ProjectRepository repo = new ProjectRepository();
        repo.setContainerProjectsRoot(nonExistentContainerRoot);

        String id = repo.bootstrap("meu-app", localProjectDir.toString());
        assertEquals("meu-app", id);
        assertTrue(Files.exists(localProjectDir.resolve(".agents/skills/cortex-conventions/SKILL.md")));

        repo.writePage("meu-app", "rule", List.of("local"), null, null, "Regra local fora do container.");
        Path localAgentsMd = localProjectDir.resolve(".agents").resolve("AGENTS.md");
        repo.promoteRules("meu-app", localAgentsMd.toString());

        assertTrue(Files.exists(localAgentsMd));
        assertTrue(Files.readString(localAgentsMd).contains("Regra local fora do container."));
    }

    @Test
    void ca5_promoteRulesComCaminhoRelativoResolveAPartirDoRootPathTraduzidoDoProjetoNoConfig() throws Exception {
        Path containerProjetosDir = tempDir.resolve("projetos");
        Files.createDirectories(containerProjetosDir);

        ProjectRepository repo = new ProjectRepository();
        repo.setContainerProjectsRoot(containerProjetosDir);

        // Registra projeto via bootstrap informando caminho do host Windows
        repo.bootstrap("cortex", "G:\\Dev\\Projetos\\cortex");
        repo.writePage("cortex", "rule", List.of("relativo"), null, null, "Regra promovida via caminho relativo.");

        // Chama promoteRules passando apenas o caminho relativo ".agents/AGENTS.md"
        repo.promoteRules("cortex", ".agents/AGENTS.md");

        Path expectedAgentsMd = containerProjetosDir.resolve("cortex").resolve(".agents").resolve("AGENTS.md");
        assertTrue(Files.exists(expectedAgentsMd),
                "Caminho relativo .agents/AGENTS.md deve ser resolvido contra /projetos/cortex registrado no config");
        assertTrue(Files.readString(expectedAgentsMd).contains("Regra promovida via caminho relativo."));
    }
}
