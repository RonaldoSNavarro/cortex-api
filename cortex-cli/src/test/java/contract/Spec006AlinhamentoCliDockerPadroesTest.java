package contract;

import com.cortex.cli.BootstrapCommand;
import com.cortex.cli.ConsolidateCommand;
import com.cortex.cli.CortexCommand;
import com.cortex.core.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Spec 006 — Alinhamento CLI, Hardening Docker e Padrões Spring/Javadoc")
public class Spec006AlinhamentoCliDockerPadroesTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("CA-1: Dockerfile utiliza imagem base eclipse-temurin:25-jre")
    void testCa1_dockerfileUsesJre() throws IOException {
        Path dockerfilePath = Paths.get("../Dockerfile").toAbsolutePath().normalize();
        if (!Files.exists(dockerfilePath)) {
            dockerfilePath = Paths.get("Dockerfile").toAbsolutePath().normalize();
        }
        assertTrue(Files.exists(dockerfilePath), "Dockerfile deve existir no repositório");

        String content = Files.readString(dockerfilePath);
        assertTrue(content.contains("FROM eclipse-temurin:25-jre"), "Dockerfile deve utilizar eclipse-temurin:25-jre");
        assertFalse(content.contains("FROM eclipse-temurin:25-jdk"), "Dockerfile não deve utilizar eclipse-temurin:25-jdk");
    }

    @Test
    @DisplayName("CA-2: docker-compose.yml declara init: true e bloco de healthcheck para cortex-api")
    void testCa2_dockerComposeInitAndHealthcheck() throws IOException {
        Path composePath = Paths.get("../docker-compose.yml").toAbsolutePath().normalize();
        if (!Files.exists(composePath)) {
            composePath = Paths.get("docker-compose.yml").toAbsolutePath().normalize();
        }
        assertTrue(Files.exists(composePath), "docker-compose.yml deve existir no repositório");

        String content = Files.readString(composePath);
        assertTrue(content.contains("init: true"), "docker-compose.yml deve conter 'init: true'");
        assertTrue(content.contains("healthcheck:"), "docker-compose.yml deve conter 'healthcheck:'");
    }

    @Test
    @DisplayName("CA-3: BootstrapCommand picocli aceita apenas --path sem --project obrigatório")
    void testCa3_bootstrapCommandOptionalProject() throws IOException {
        Path projectRoot = tempDir.resolve("inferred-project");
        Files.createDirectories(projectRoot);

        ProjectRepository repo = new ProjectRepository(tempDir.resolve(".cortex"));
        repo.setGitSyncEnabled(false);

        BootstrapCommand cmd = new BootstrapCommand(repo);
        CommandLine cl = new CommandLine(cmd);

        int exitCode = cl.execute("--path", projectRoot.toString());
        assertEquals(0, exitCode, "Execução deve retornar 0 sem necessidade de informar --project");
        assertTrue(Files.exists(projectRoot.resolve(".agents")), "Estrutura do projeto deve ter sido criada");
    }

    @Test
    @DisplayName("CA-4: ConsolidateCommand executa e imprime o relatório retornado por consolidateReport")
    void testCa4_consolidateCommandPrintsReport() throws IOException {
        String pId = "consolidate-cli-test";
        Path projectRoot = tempDir.resolve(pId);
        Files.createDirectories(projectRoot);

        ProjectRepository repo = new ProjectRepository(tempDir.resolve(".cortex"));
        repo.setGitSyncEnabled(false);
        repo.bootstrap(pId, projectRoot.toString());
        repo.createRaw(pId, "decision", "Decisão tomada para o projeto CLI.");

        ConsolidateCommand cmd = new ConsolidateCommand(repo);
        CommandLine cl = new CommandLine(cmd);

        StringWriter sw = new StringWriter();
        cl.setOut(new java.io.PrintWriter(sw));

        int exitCode = cl.execute("--project", pId);
        assertEquals(0, exitCode);
        String output = sw.toString();
        // Deve conter menção ao relatório de notas brutas
        assertTrue(output.contains("raw") || output.contains("Consolidação") || output.contains("Decisão") || output.contains(pId),
                "Saída deve conter o relatório de consolidação do repositório");
    }

    @Test
    @DisplayName("CA-5: CortexCommand reconhece aliases 'capture' e 'promote_rules'")
    void testCa5_commandAliasesRecognized() {
        CommandLine cl = new CommandLine(new CortexCommand());

        CommandLine.Model.CommandSpec spec = cl.getCommandSpec();
        // Verificar que subcomandos ou aliases são reconhecidos
        assertNotNull(cl.getSubcommands().get("ingest"), "Subcomando 'ingest' deve existir");
        assertNotNull(cl.getSubcommands().get("capture"), "Alias 'capture' deve ser reconhecido");

        assertNotNull(cl.getSubcommands().get("promote_rule"), "Subcomando 'promote_rule' deve existir");
        assertNotNull(cl.getSubcommands().get("promote_rules"), "Alias 'promote_rules' deve ser reconhecido");
    }

    @Test
    @DisplayName("CA-6: Nenhuma classe do projeto utiliza @Autowired em campos (apenas construtores)")
    void testCa6_noFieldAutowiredInMainClasses() throws Exception {
        List<Class<?>> classes = findClassesInPackage("com.cortex");
        assertFalse(classes.isEmpty(), "Deve encontrar classes em com.cortex");

        List<String> violations = new ArrayList<>();
        for (Class<?> clazz : classes) {
            for (Field field : clazz.getDeclaredFields()) {
                if (field.isAnnotationPresent(Autowired.class)) {
                    violations.add(clazz.getName() + "." + field.getName());
                }
            }
        }

        assertTrue(violations.isEmpty(),
                "Violação de injeção por construtor detectada! Os seguintes campos usam @Autowired: " + violations);
    }

    @Test
    @DisplayName("CA-7: Classes públicas possuem Javadoc de cabeçalho nos arquivos fonte")
    void testCa7_publicClassesHaveJavadoc() throws IOException {
        Path rootDir = Paths.get("..").toAbsolutePath().normalize();
        if (!Files.exists(rootDir.resolve("cortex-core"))) {
            rootDir = Paths.get(".").toAbsolutePath().normalize();
        }

        List<Path> javaFiles = new ArrayList<>();
        List<String> modules = List.of("cortex-core", "cortex-mcp", "cortex-api", "cortex-cli");
        for (String mod : modules) {
            Path srcDir = rootDir.resolve(mod).resolve("src").resolve("main").resolve("java");
            if (Files.exists(srcDir)) {
                try (var stream = Files.walk(srcDir)) {
                    stream.filter(p -> p.toString().endsWith(".java"))
                          .filter(Files::isRegularFile)
                          .forEach(javaFiles::add);
                }
            }
        }

        assertFalse(javaFiles.isEmpty(), "Deve encontrar arquivos .java em src/main/java");

        List<String> missingJavadoc = new ArrayList<>();
        for (Path file : javaFiles) {
            String content = Files.readString(file);
            // Verifica se a classe pública possui Javadoc antes de 'public class' ou 'public record' ou 'public enum'
            if (content.contains("public class ") || content.contains("public record ") || content.contains("public enum ") || content.contains("public interface ")) {
                if (!content.contains("/**")) {
                    missingJavadoc.add(file.getFileName().toString());
                }
            }
        }

        assertTrue(missingJavadoc.isEmpty(),
                "As seguintes classes públicas não possuem Javadoc de cabeçalho: " + missingJavadoc);
    }

    private static List<Class<?>> findClassesInPackage(String packageName) throws Exception {
        List<Class<?>> classes = new ArrayList<>();
        String path = packageName.replace('.', '/');
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        Enumeration<URL> resources = cl.getResources(path);
        while (resources.hasMoreElements()) {
            URL resource = resources.nextElement();
            File directory = new File(resource.getFile());
            if (directory.exists() && directory.isDirectory()) {
                findClasses(directory, packageName, classes);
            }
        }
        return classes;
    }

    private static void findClasses(File directory, String packageName, List<Class<?>> classes) {
        File[] files = directory.listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                findClasses(file, packageName + "." + file.getName(), classes);
            } else if (file.getName().endsWith(".class") && !file.getName().contains("$") && !file.getName().contains("Test")) {
                try {
                    String className = packageName + '.' + file.getName().substring(0, file.getName().length() - 6);
                    Class<?> clazz = Class.forName(className);
                    // Ignora classes de teste se porventura estiverem no classpath
                    if (!clazz.getName().contains("Test") && !clazz.getName().startsWith("contract")) {
                        classes.add(clazz);
                    }
                } catch (ClassNotFoundException | NoClassDefFoundError ignored) {
                }
            }
        }
    }
}
