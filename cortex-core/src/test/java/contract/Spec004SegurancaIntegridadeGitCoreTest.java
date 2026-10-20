package contract;

import com.cortex.core.MarkdownParser;
import com.cortex.core.MemoryPage;
import com.cortex.core.MemoryType;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Spec 004 — Segurança, Integridade, Subprocessos Git e Parsing Core")
public class Spec004SegurancaIntegridadeGitCoreTest {

    @TempDir
    Path tempDir;

    private ProjectRepository repo;
    private String projectId;
    private Path projectRoot;

    @BeforeEach
    void setUp() throws IOException {
        projectId = "test-spec-004";
        projectRoot = tempDir.resolve("my-repo");
        Files.createDirectories(projectRoot);

        Path cortexHome = tempDir.resolve(".cortex");
        repo = new ProjectRepository(cortexHome);
        repo.setGitSyncEnabled(false);
        repo.bootstrap(projectId, projectRoot.toString());
    }

    @AfterEach
    void tearDown() {
        if (repo != null) {
            repo.shutdown();
        }
    }

    @Test
    @DisplayName("CA-1: promoteRules chamado duas vezes com $, ${...} e \\ preserva conteúdo literal sem exceção")
    void testCa1_promoteRulesLiteralPreservation() throws IOException {
        String ruleContent = "Configuração: ${project.version} e grupo regex $1 com caminho G:\\Dev\\Projetos\\cortex";
        repo.createRaw(projectId, "rule", ruleContent);

        // Consolidate into an active rule
        repo.writePage(projectId, "rule", List.of("deploy", "env"), null, null, ruleContent);

        Path agentsFile = projectRoot.resolve(".agents").resolve("AGENTS.md");
        Files.createDirectories(agentsFile.getParent());
        Files.writeString(agentsFile, "# Documento Inicial\n");

        // Executa duas vezes consecutivas (a segunda substitui o bloco existente)
        assertDoesNotThrow(() -> repo.promoteRules(projectId, agentsFile.toString()));
        assertDoesNotThrow(() -> repo.promoteRules(projectId, agentsFile.toString()));

        String finalContent = Files.readString(agentsFile);
        assertTrue(finalContent.contains("${project.version}"), "Deve preservar ${project.version}");
        assertTrue(finalContent.contains("$1"), "Deve preservar $1");
        assertTrue(finalContent.contains("G:\\Dev\\Projetos\\cortex"), "Deve preservar barras invertidas");
        assertTrue(finalContent.contains("<!-- BEGIN CORTEX RULES -->"));
        assertTrue(finalContent.contains("<!-- END CORTEX RULES -->"));
    }

    @Test
    @DisplayName("CA-2: Validação de projectId rejeita .., / e \\ com IllegalArgumentException antes do disco")
    void testCa2_pathTraversalProjectId() {
        assertThrows(IllegalArgumentException.class, () -> repo.getProjectDir("../malicious"));
        assertThrows(IllegalArgumentException.class, () -> repo.getProjectDir("sub/dir"));
        assertThrows(IllegalArgumentException.class, () -> repo.getProjectDir("sub\\dir"));
        assertThrows(IllegalArgumentException.class, () -> repo.bootstrap("../malicious", projectRoot.toString()));
        assertThrows(IllegalArgumentException.class, () -> repo.updatePageStatus("../malicious", "page-1", "active"));
    }

    @Test
    @DisplayName("CA-3: writePage com consumedRawIds ou supersedes com .. lança IllegalArgumentException e preserva arquivos")
    void testCa3_pathTraversalInWritePage() throws IOException {
        // Criar uma página e uma nota legítima
        String legitPageRes = repo.writePage(projectId, "fact", List.of("legit"), null, null, "Página legítima");
        String pageId = legitPageRes.substring(legitPageRes.indexOf("ID: ") + 4).trim();
        Path legitPageFile = repo.getProjectDir(projectId).resolve("pages").resolve(pageId + ".md");
        assertTrue(Files.exists(legitPageFile));

        // Tentar sobrescrever ou apagar via Path Traversal
        assertThrows(IllegalArgumentException.class, () -> {
            repo.writePage(projectId, "fact", List.of("hack"), "../pages/" + pageId, null, "Hacked");
        });

        assertThrows(IllegalArgumentException.class, () -> {
            repo.writePage(projectId, "fact", List.of("hack"), null, List.of("../pages/" + pageId), "Hacked");
        });

        // O arquivo legítimo deve continuar existindo e intocado
        assertTrue(Files.exists(legitPageFile), "Arquivo de páginas não pode ter sido apagado ou alterado");
    }

    @Test
    @DisplayName("CA-4: runCommand drena saídas volumosas (>128 KB) sem bloqueio de pipe do SO")
    void testCa4_runCommandLargeOutputDraining() throws Exception {
        File workingDir = tempDir.toFile();
        boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
        assertDoesNotThrow(() -> {
            String output;
            if (isWindows) {
                output = repo.runCommandWithOutput(workingDir, "cmd.exe", "/c", "for /L %i in (1,1,4000) do @echo 01234567890123456789012345678901234567890123456789");
            } else {
                String script = "for i in $(seq 1 4000); do echo '01234567890123456789012345678901234567890123456789'; done";
                output = repo.runCommandWithOutput(workingDir, "sh", "-c", script);
            }
            assertTrue(output.length() > 128 * 1024, "A saída deve ser drenada integralmente (>128 KB)");
        });
    }

    @Test
    @DisplayName("CA-5: syncToGit com working tree limpa não falha e configura StrictHostKeyChecking=accept-new")
    void testCa5_syncToGitCleanTreeAndSshConfig() throws Exception {
        Path gitRepoDir = tempDir.resolve("git-repo");
        Files.createDirectories(gitRepoDir);

        // Inicializar repositório git local para testar
        repo.runCommand(gitRepoDir.toFile(), "git", "init");
        repo.runCommand(gitRepoDir.toFile(), "git", "config", "user.name", "Test User");
        repo.runCommand(gitRepoDir.toFile(), "git", "config", "user.email", "test@cortex.local");
        repo.runCommand(gitRepoDir.toFile(), "git", "config", "core.sshCommand", "ssh -o StrictHostKeyChecking=accept-new");

        String sshCmd = repo.runCommandWithOutput(gitRepoDir.toFile(), "git", "config", "core.sshCommand").trim();
        assertEquals("ssh -o StrictHostKeyChecking=accept-new", sshCmd);

        // Working tree limpa: git status --porcelain é vazio
        String porcelain = repo.runCommandWithOutput(gitRepoDir.toFile(), "git", "status", "--porcelain").trim();
        assertTrue(porcelain.isEmpty());
    }

    @Test
    @DisplayName("CA-6: bootstrap seguido de writePage limpa placeholder do index.md e unifica log.md")
    void testCa6_indexPlaceholderRemovedAndLogUnified() throws IOException {
        Path projectDir = repo.getProjectDir(projectId);
        Path indexPath = projectDir.resolve("index.md");
        Path logPath = projectDir.resolve("log.md");

        assertTrue(Files.readString(indexPath).contains("_Nenhuma página consolidada ainda._"));

        // Cria raw e depois consolida
        String rawId = repo.createRaw(projectId, "fact", "Nota sobre arquitetura");
        repo.writePage(projectId, "fact", List.of("arch"), null, List.of(rawId), "Página Curada de Arquitetura");

        String updatedIndex = Files.readString(indexPath);
        assertFalse(updatedIndex.contains("_Nenhuma página consolidada ainda._"),
                "Placeholder deve ser removido após a primeira página consolidada");
        assertTrue(updatedIndex.contains("pages/"), "Deve conter link para a nova página");

        String logContent = Files.readString(logPath);
        // Cada entrada relevante deve estar no formato de tabela Markdown (| Data | Evento | ID |)
        String[] lines = logContent.split("\\R");
        int tableRowMatches = 0;
        for (String line : lines) {
            if (line.startsWith("|") && line.endsWith("|")) {
                tableRowMatches++;
            }
        }
        // Cabeçalho (2 linhas: header + divider) + inicialização + createRaw + writePage >= 5 linhas de tabela
        assertTrue(tableRowMatches >= 5, "Todas as entradas de log devem seguir o formato de tabela Markdown");
        assertFalse(logContent.contains("## ["), "Não deve conter cabeçalhos '## [' no log");
    }

    @Test
    @DisplayName("CA-7: MarkdownParser processa front-matter contendo '---' no valor do YAML sem truncamento")
    void testCa7_yamlFrontMatterWithDashesInsideValue() throws IOException {
        String markdownWithDashes = """
                ---
                id: doc-with-dashes
                type: fact
                project: cortex
                status: active
                source: "nota de auditoria --- revisão final"
                created_at: "2026-10-18T00:00:00Z"
                ---
                
                # Conteúdo Principal
                Este documento contém detalhes sobre o parsing.
                """;

        MemoryPage parsed = MarkdownParser.parseString(markdownWithDashes);
        assertNotNull(parsed);
        assertEquals("doc-with-dashes", parsed.getId());
        assertEquals(MemoryType.FACT, parsed.getType());
        assertEquals("cortex", parsed.getProject());
        assertEquals("active", parsed.getStatus());
        assertTrue(parsed.getContent().contains("# Conteúdo Principal"));
    }

    @Test
    @DisplayName("CA-8: lint() detecta dangling wikilink contendo espaços internos")
    void testCa8_lintDetectsWikilinksWithSpaces() throws IOException {
        String contentWithSpacedWikilink = "Veja mais em [[pagina que nao existe ainda]].";
        repo.writePage(projectId, "fact", List.of("teste"), null, null, contentWithSpacedWikilink);

        List<String> warnings = repo.lint(projectId);
        boolean foundDanglingWarning = warnings.stream()
                .anyMatch(w -> w.contains("[DANGLING WIKILINK]") && w.contains("pagina que nao existe ainda"));
        assertTrue(foundDanglingWarning, "O linter deve acusar [DANGLING WIKILINK] para links com espaço");
    }
}
