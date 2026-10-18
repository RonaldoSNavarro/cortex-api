package com.cortex.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.annotation.PreDestroy;

/**
 * Repositório central de memória do Cortex para armazenamento, indexação, consulta e ciclo de vida de projetos.
 * Gerencia persistência Markdown, sincronização assíncrona Git em Virtual Threads e integridade referencial.
 */
@Service
public class ProjectRepository {

    private static final Logger log = LoggerFactory.getLogger(ProjectRepository.class);
    private static final Pattern WINDOWS_DRIVE_PATTERN = Pattern.compile("^[a-zA-Z]:([/\\\\].*)?$");
    private static final Pattern WSL_MOUNT_PATTERN = Pattern.compile("^/mnt/([a-zA-Z])(/.*)?$");
    private static final Pattern SAFE_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9._-]+$");

    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    private final ProjectWatcherService watcherService;
    private final SmartSynthesizerService synthesizerService;
    private final CortexConfigManager configManager;
    private Path baseProjectsDir;

    @Value("${cortex.git.sync-enabled:true}")
    private boolean gitSyncEnabled = true;

    @Value("${cortex.git.command-timeout-seconds:15}")
    private long gitCommandTimeoutSeconds = 15;

    private Path containerProjectsRoot = Paths.get("/projetos");

    // Per-project in-memory indexes and knowledge graphs
    private final Map<String, InMemoryLexicalIndex> indexes = new ConcurrentHashMap<>();
    private final Map<String, KnowledgeGraph> graphs = new ConcurrentHashMap<>();

    // Per-project serialized background Git sync queues on Java 25 Virtual Threads
    private final ExecutorService gitSyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentHashMap<String, CompletableFuture<Void>> projectGitQueues = new ConcurrentHashMap<>();
    private final ReentrantLock gitRepoLock = new ReentrantLock();
    private BiConsumer<String, String> gitSyncHandler = (projectId, commitMsg) -> syncToGit(commitMsg);

    public ProjectRepository() {
        this(null, null, new CortexConfigManager());
    }

    public ProjectRepository(Path cortexHome) {
        this(null, null, new CortexConfigManager());
        if (cortexHome != null) {
            this.baseProjectsDir = cortexHome.resolve("projects");
        }
    }

    public ProjectRepository(ProjectWatcherService watcherService, SmartSynthesizerService synthesizerService) {
        this(watcherService, synthesizerService, new CortexConfigManager());
    }

    @Autowired
    public ProjectRepository(@Autowired(required = false) ProjectWatcherService watcherService,
                             @Autowired(required = false) SmartSynthesizerService synthesizerService,
                             @Autowired(required = false) CortexConfigManager configManager) {
        this.watcherService = watcherService;
        this.synthesizerService = synthesizerService;
        this.configManager = configManager != null ? configManager : new CortexConfigManager();
        this.baseProjectsDir = Paths.get(System.getProperty("user.home"), ".cortex", "projects");
    }

    public static void validateIdentifier(String id, String fieldName) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException(fieldName + " cannot be null or empty");
        }
        String trimmed = id.trim();
        if (".".equals(trimmed) || "..".equals(trimmed) || !SAFE_ID_PATTERN.matcher(trimmed).matches()) {
            throw new IllegalArgumentException(fieldName + " contém caracteres inválidos ou caminho inseguro: " + id);
        }
    }

    private Path getBaseProjectsDir() {
        if (baseProjectsDir == null) {
            baseProjectsDir = Paths.get(System.getProperty("user.home"), ".cortex", "projects");
        }
        return baseProjectsDir;
    }

    public boolean isGitSyncEnabled() {
        String sysProp = System.getProperty("cortex.git.sync-enabled");
        if (sysProp != null && !sysProp.isBlank()) {
            return Boolean.parseBoolean(sysProp);
        }
        return gitSyncEnabled;
    }

    public void setGitSyncEnabled(boolean gitSyncEnabled) {
        this.gitSyncEnabled = gitSyncEnabled;
    }

    public long getGitCommandTimeoutSeconds() {
        return gitCommandTimeoutSeconds;
    }

    public void setGitCommandTimeoutSeconds(long gitCommandTimeoutSeconds) {
        this.gitCommandTimeoutSeconds = gitCommandTimeoutSeconds;
    }

    public void setGitSyncHandler(BiConsumer<String, String> gitSyncHandler) {
        this.gitSyncHandler = Objects.requireNonNull(gitSyncHandler);
    }

    public CompletableFuture<Void> getPendingGitSyncFuture(String projectId) {
        return projectGitQueues.getOrDefault(projectId, CompletableFuture.completedFuture(null));
    }

    public Path getContainerProjectsRoot() {
        return containerProjectsRoot;
    }

    public void setContainerProjectsRoot(Path containerProjectsRoot) {
        this.containerProjectsRoot = Objects.requireNonNull(containerProjectsRoot);
    }

    /**
     * Resolves host paths from Windows (G:\Dev\Projetos\...) or WSL (/mnt/g/Dev/Projetos/...)
     * to the container volume mount (/projetos/...) when present, or validates local paths.
     * Rejects unmapped drives (such as F:\ or /mnt/f/) with IllegalArgumentException.
     */
    public Path resolveProjectPath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new IllegalArgumentException("Caminho não pode ser nulo ou vazio.");
        }
        String trimmed = rawPath.trim();
        String normalized = trimmed.replace('\\', '/');
        String lower = normalized.toLowerCase(Locale.ROOT);

        boolean isMappedWindowsG = lower.equals("g:/dev/projetos") || lower.startsWith("g:/dev/projetos/");
        boolean isMappedWslG = lower.equals("/mnt/g/dev/projetos") || lower.startsWith("/mnt/g/dev/projetos/");

        if (isMappedWindowsG || isMappedWslG) {
            String suffix = isMappedWindowsG
                    ? normalized.substring("g:/dev/projetos".length())
                    : normalized.substring("/mnt/g/dev/projetos".length());
            while (suffix.startsWith("/")) {
                suffix = suffix.substring(1);
            }

            if (containerProjectsRoot != null && Files.isDirectory(containerProjectsRoot)) {
                return resolveSegments(containerProjectsRoot, suffix);
            }

            // Outside container: if exact path exists locally, use it directly
            try {
                if ( !(File.separatorChar == '/' && trimmed.contains("\\")) ) {
                    Path direct = Paths.get(trimmed);
                    if (Files.exists(direct)) {
                        return direct;
                    }
                }
            } catch (InvalidPathException ignored) {
            }

            if (File.separatorChar == '/') {
                Path wslBase = Paths.get("/mnt/g/Dev/Projetos");
                if (Files.isDirectory(wslBase)) {
                    return resolveSegments(wslBase, suffix);
                }
                if (trimmed.contains("\\") || WINDOWS_DRIVE_PATTERN.matcher(trimmed).matches()) {
                    throw new IllegalArgumentException("Caminho Windows sem volume /projetos montado: " + rawPath);
                }
            } else {
                Path winBase = Paths.get("G:\\Dev\\Projetos");
                if (Files.isDirectory(winBase)) {
                    return resolveSegments(winBase, suffix);
                }
            }
            return Paths.get(trimmed);
        }

        // Reject unmapped /mnt/<drive> paths (e.g. /mnt/f/Dev/Projetos/...)
        Matcher wslMatcher = WSL_MOUNT_PATTERN.matcher(normalized);
        if (wslMatcher.matches()) {
            String driveLetter = wslMatcher.group(1).toLowerCase(Locale.ROOT);
            if (!"g".equals(driveLetter)) {
                throw new IllegalArgumentException("Drive WSL não mapeado (apenas G:\\Dev\\Projetos é suportado): " + rawPath);
            }
        }

        // Check Windows drive paths (e.g. F:\Dev\Projetos\...)
        if (WINDOWS_DRIVE_PATTERN.matcher(trimmed).matches()) {
            char driveLetter = Character.toLowerCase(trimmed.charAt(0));
            if (driveLetter == 'f') {
                throw new IllegalArgumentException("Drive F:\\ não é suportado (utilize G:\\Dev\\Projetos): " + rawPath);
            }
            if (File.separatorChar == '/') {
                throw new IllegalArgumentException("Caminho Windows inválido em ambiente Linux: " + rawPath);
            }
            Path driveRoot = Paths.get(trimmed.substring(0, 2) + "\\");
            if (!Files.exists(driveRoot)) {
                throw new IllegalArgumentException("Drive inexistente no sistema local: " + rawPath);
            }
            return Paths.get(trimmed);
        }

        // Reject paths containing backslashes on Linux to avoid creating directories with '\' in their name
        if (File.separatorChar == '/' && trimmed.contains("\\")) {
            throw new IllegalArgumentException("Caminho contém barras invertidas inválidas em ambiente Linux: " + rawPath);
        }

        return Paths.get(trimmed);
    }

    private static Path resolveSegments(Path base, String relativeSuffix) {
        Path current = base;
        if (relativeSuffix == null || relativeSuffix.isEmpty()) {
            return current;
        }
        for (String part : relativeSuffix.split("/")) {
            if (!part.isEmpty()) {
                current = current.resolve(part);
            }
        }
        return current;
    }

    public Path getProjectDir(String projectId) {
        validateIdentifier(projectId, "projectId");
        Path resolved = getBaseProjectsDir().resolve(projectId).normalize();
        if (!resolved.startsWith(getBaseProjectsDir().normalize())) {
            throw new IllegalArgumentException("Path traversal detectado para projectId: " + projectId);
        }
        return resolved;
    }

    public InMemoryLexicalIndex getIndex(String projectId) {
        return indexes.computeIfAbsent(projectId, pId -> {
            InMemoryLexicalIndex index = new InMemoryLexicalIndex();
            KnowledgeGraph graph = getGraph(pId);
            loadAndIndexAll(pId, index, graph);
            return index;
        });
    }

    public KnowledgeGraph getGraph(String projectId) {
        return graphs.computeIfAbsent(projectId, pId -> {
            KnowledgeGraph graph = new KnowledgeGraph();
            Path projectDir = getProjectDir(pId);
            if (watcherService != null && Files.exists(projectDir)) {
                InMemoryLexicalIndex index = indexes.get(pId);
                if (index != null) {
                    watcherService.registerProject(pId, projectDir, index, graph);
                }
            }
            return graph;
        });
    }

    private synchronized void loadAndIndexAll(String projectId, InMemoryLexicalIndex index, KnowledgeGraph graph) {
        try {
            Path projectDir = getProjectDir(projectId);
            List<Path> dirs = List.of(projectDir.resolve("raw"), projectDir.resolve("pages"));
            for (Path dir : dirs) {
                if (Files.exists(dir)) {
                    try (var stream = Files.walk(dir)) {
                        stream.filter(p -> p.toString().endsWith(".md"))
                              .filter(Files::isRegularFile)
                              .forEach(p -> {
                                  try {
                                      MemoryPage page = MarkdownParser.parse(p);
                                      if (page != null && page.getId() != null) {
                                          index.indexPage(page);
                                          graph.indexPage(page);
                                      }
                                  } catch (Exception e) {
                                      log.debug("Erro ao fazer parse inicial de {}: {}", p, e.getMessage());
                                  }
                              });
                    }
                }
            }
            if (watcherService != null && Files.exists(projectDir)) {
                watcherService.registerProject(projectId, projectDir, index, graph);
            }
        } catch (Exception e) {
            log.error("Erro ao carregar e indexar projeto [{}]: {}", projectId, e.getMessage());
        }
    }

    public void init(String projectId) throws IOException {
        Path projectDir = getProjectDir(projectId);
        Files.createDirectories(projectDir.resolve("raw"));
        Files.createDirectories(projectDir.resolve("pages"));
        Files.createDirectories(projectDir.resolve("rules"));

        Path schemaPath = projectDir.resolve("schema.md");
        if (!Files.exists(schemaPath)) {
            String defaultSchema = "---\n" +
                    "name: cortex-conventions\n" +
                    "description: Convenções de leitura e escrita da wiki pessoal de memória (Cortex).\n" +
                    "---\n\n" +
                    "# Convenções do Cortex\n\n" +
                    "## Tipos de memória\n" +
                    "- `fact`: algo verificável e estável\n" +
                    "- `decision`: uma escolha e o motivo\n" +
                    "- `rule`: algo que deve sempre ser seguido (candidato a promote_rule)\n" +
                    "- `gotcha`: uma armadilha já descoberta\n" +
                    "- `note`: conhecimento geral sem um tipo específico\n";
            Files.writeString(schemaPath, defaultSchema);
        }

        // Register watcher and init in-memory index
        getIndex(projectId);
    }

    public void saveRaw(String projectId, String fileName, String content) throws IOException {
        validateIdentifier(projectId, "projectId");
        Path projectDir = getProjectDir(projectId);
        Path rawDir = projectDir.resolve("raw").normalize();
        Path rawPath = rawDir.resolve(fileName).normalize();
        if (!rawPath.startsWith(rawDir)) {
            throw new IllegalArgumentException("Path traversal detectado em fileName: " + fileName);
        }
        Files.writeString(rawPath, content);
        try {
            MemoryPage page = MarkdownParser.parse(rawPath);
            if (page != null) {
                getIndex(projectId).indexPage(page);
                getGraph(projectId).indexPage(page);
            }
        } catch (Exception ignored) {
        }
    }

    public String createRaw(String projectId, String typeStr, String content) throws IOException {
        validateIdentifier(projectId, "projectId");
        Path projectDir = getProjectDir(projectId);
        MemoryType type = MemoryType.fromValue(typeStr);
        if (type == null) {
            throw new IllegalArgumentException("Tipo de memória inválido: " + typeStr);
        }

        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String id = "raw-" + dateStr + "-" + UUID.randomUUID().toString().substring(0, 4);

        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("id", id);
        frontmatter.put("type", type.getValue());
        frontmatter.put("project", projectId);
        frontmatter.put("created_at", Instant.now().toString());

        String yamlString = yamlMapper.writeValueAsString(frontmatter);
        if (!yamlString.startsWith("---")) yamlString = "---\n" + yamlString;
        yamlString = yamlString + "---\n\n" + content;

        saveRaw(projectId, id + ".md", yamlString);
        appendLog(projectDir, "ingest", id);
        return id;
    }

    public long getPendingRawCount(String projectId) throws IOException {
        Path projectDir = getProjectDir(projectId);
        Path rawDir = projectDir.resolve("raw");
        if (!Files.exists(rawDir)) return 0;
        try (var stream = Files.walk(rawDir)) {
            return stream.filter(p -> p.toString().endsWith(".md"))
                         .filter(Files::isRegularFile)
                         .count();
        }
    }

    public List<MemoryPage> getPendingRaws(String projectId) throws IOException {
        Path projectDir = getProjectDir(projectId);
        Path rawDir = projectDir.resolve("raw");
        List<MemoryPage> rawPages = new ArrayList<>();
        if (!Files.exists(rawDir)) return rawPages;

        try (var stream = Files.walk(rawDir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                  .filter(Files::isRegularFile)
                  .forEach(p -> {
                      try {
                          rawPages.add(MarkdownParser.parse(p));
                      } catch (Exception e) {
                          log.error("Erro ao fazer parse do arquivo raw: {} - {}", p, e.getMessage());
                      }
                  });
        }
        return rawPages;
    }

    public void updatePageStatus(String projectId, String pageId, String newStatus) throws IOException {
        validateIdentifier(projectId, "projectId");
        validateIdentifier(pageId, "pageId");
        Path projectDir = getProjectDir(projectId);
        List<Path> dirsToSearch = List.of(
                projectDir.resolve("pages"),
                projectDir.resolve("raw")
        );
        for (Path dir : dirsToSearch) {
            if (Files.exists(dir)) {
                Path file = dir.resolve(pageId + ".md").normalize();
                if (!file.startsWith(dir.normalize())) {
                    throw new IllegalArgumentException("Path traversal detectado em pageId: " + pageId);
                }
                if (Files.exists(file)) {
                    MemoryPage page;
                    try {
                        page = MarkdownParser.parse(file);
                    } catch (Exception e) {
                        log.error("Erro ao carregar página para atualizar status: {}", e.getMessage());
                        return;
                    }

                    page.setStatus(newStatus);

                    Map<String, Object> fm = new LinkedHashMap<>();
                    fm.put("id", page.getId());
                    if (page.getType() != null) fm.put("type", page.getType().getValue());
                    if (page.getProject() != null) fm.put("project", page.getProject());
                    fm.put("status", page.getStatus());
                    if (page.getSupersedes() != null && !page.getSupersedes().isEmpty()) fm.put("supersedes", page.getSupersedes());
                    if (page.getTags() != null && !page.getTags().isEmpty()) fm.put("tags", page.getTags());
                    if (page.getCreatedAt() != null) fm.put("created_at", page.getCreatedAt());

                    String yamlString = yamlMapper.writeValueAsString(fm);
                    if (!yamlString.startsWith("---")) yamlString = "---\n" + yamlString;
                    yamlString = yamlString + "---\n\n" + (page.getContent() != null ? page.getContent() : "");

                    Files.writeString(file, yamlString);

                    // Re-index
                    getIndex(projectId).indexPage(page);
                    getGraph(projectId).indexPage(page);
                    return;
                }
            }
        }
        log.warn("Aviso: Página a ser substituída não encontrada ({})", pageId);
    }

    public String writePage(String projectId, String typeStr, List<String> tags, String supersedes, List<String> consumedRawIds, String content) throws IOException {
        validateIdentifier(projectId, "projectId");
        Path projectDir = getProjectDir(projectId);
        MemoryType type = MemoryType.fromValue(typeStr);
        if (type == null) {
            throw new IllegalArgumentException("Tipo de memória inválido: " + typeStr);
        }

        Set<String> impactedPages = Collections.emptySet();
        if (supersedes != null && !supersedes.trim().isEmpty()) {
            validateIdentifier(supersedes.trim(), "supersedes");
            Path pagesDir = projectDir.resolve("pages").normalize();
            Path rawDir = projectDir.resolve("raw").normalize();
            Path supFileInPages = pagesDir.resolve(supersedes.trim() + ".md").normalize();
            Path supFileInRaw = rawDir.resolve(supersedes.trim() + ".md").normalize();
            if (!supFileInPages.startsWith(pagesDir) && !supFileInRaw.startsWith(rawDir)) {
                throw new IllegalArgumentException("Path traversal detectado em supersedes: " + supersedes);
            }
            impactedPages = getGraph(projectId).getImpactedPagesOnSupersede(supersedes.trim());
            updatePageStatus(projectId, supersedes.trim(), "superseded");
        }

        if (consumedRawIds != null && !consumedRawIds.isEmpty()) {
            Path rawDir = projectDir.resolve("raw").normalize();
            for (String rawId : consumedRawIds) {
                validateIdentifier(rawId, "consumedRawId");
                Path rawFile = rawDir.resolve(rawId + ".md").normalize();
                if (!rawFile.startsWith(rawDir)) {
                    throw new IllegalArgumentException("Path traversal detectado em consumedRawIds: " + rawId);
                }
            }
        }

        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String id = dateStr + "-" + UUID.randomUUID().toString().substring(0, 4);
        if (tags != null && !tags.isEmpty()) {
            String mainTag = tags.getFirst().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "-");
            id = dateStr + "-" + mainTag + "-" + UUID.randomUUID().toString().substring(0, 4);
        }

        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("id", id);
        fm.put("type", type.getValue());
        fm.put("project", projectId);
        fm.put("status", "active");
        if (supersedes != null && !supersedes.trim().isEmpty()) fm.put("supersedes", supersedes.trim());
        if (tags != null && !tags.isEmpty()) fm.put("tags", tags);
        fm.put("created_at", Instant.now().toString());

        String yamlString = yamlMapper.writeValueAsString(fm);
        if (!yamlString.startsWith("---")) yamlString = "---\n" + yamlString;
        yamlString = yamlString + "---\n\n" + content;

        Files.createDirectories(projectDir.resolve("pages"));
        Path pagesPath = projectDir.resolve("pages").resolve(id + ".md");
        Files.writeString(pagesPath, yamlString);

        if (consumedRawIds != null && !consumedRawIds.isEmpty()) {
            Path rawDir = projectDir.resolve("raw");
            for (String rawId : consumedRawIds) {
                Path rawFile = rawDir.resolve(rawId + ".md");
                if (Files.exists(rawFile)) {
                    Files.deleteIfExists(rawFile);
                    getIndex(projectId).removePage(rawId);
                    getGraph(projectId).removePage(rawId);
                }
            }
        }

        updateIndex(projectDir, id, typeStr, tags);
        appendLog(projectDir, "write", id);

        // Index new page synchronously before returning
        try {
            MemoryPage newPage = MarkdownParser.parse(pagesPath);
            getIndex(projectId).indexPage(newPage);
            getGraph(projectId).indexPage(newPage);
        } catch (Exception ignored) {
        }

        scheduleGitSync(projectId, "Auto-consolidado pelo Cortex: " + id);

        StringBuilder res = new StringBuilder();
        res.append("Página salva com sucesso. ID: ").append(id);
        if (!impactedPages.isEmpty()) {
            res.append("\n⚠️ **Alerta de Impacto**: As seguintes páginas continham referências à página substituída [")
               .append(supersedes).append("] e podem precisar de revisão: `")
               .append(String.join("`, `", impactedPages)).append("`");
        }
        return res.toString();
    }

    private synchronized void appendLog(Path projectDir, String event, String id) throws IOException {
        Path logPath = projectDir.resolve("log.md");
        String timestamp = Instant.now().toString();
        if (!Files.exists(logPath)) {
            String logContent = "# Log Cronológico\n\n" +
                    "| Data | Evento | ID |\n" +
                    "|---|---|---|\n";
            Files.writeString(logPath, logContent, StandardOpenOption.CREATE);
        }
        String cleanEvent = event.replace("|", "/").trim();
        String cleanId = (id != null && !id.isBlank()) ? id.replace("|", "/").trim() : "-";
        String logEntry = "| " + timestamp + " | " + cleanEvent + " | " + cleanId + " |\n";
        Files.writeString(logPath, logEntry, StandardOpenOption.APPEND);
    }

    private synchronized void appendLog(Path projectDir, String message) throws IOException {
        if (message != null && message.contains(" | ")) {
            String[] parts = message.split(" \\| ", 2);
            appendLog(projectDir, parts[0].trim(), parts[1].trim());
        } else {
            appendLog(projectDir, message != null ? message.trim() : "", "-");
        }
    }

    private synchronized void updateIndex(Path projectDir, String id, String typeStr, List<String> tags) throws IOException {
        Path indexPath = projectDir.resolve("index.md");
        String tagStr = (tags != null && !tags.isEmpty()) ? String.join(", ", tags) : "sem tags";
        String entry = "- [" + id + "](pages/" + id + ".md) | Tipo: " + typeStr + " | Tags: " + tagStr;

        if (!Files.exists(indexPath)) {
            String header = "---\n" +
                    "title: Índice do Projeto\n" +
                    "---\n\n" +
                    "# Índice de Páginas\n\n";
            Files.writeString(indexPath, header + entry + "\n", StandardOpenOption.CREATE);
            return;
        }

        String content = Files.readString(indexPath);
        content = content.replace("_Nenhuma página consolidada ainda._\n", "");
        content = content.replace("_Nenhuma página consolidada ainda._", "");

        if (!content.contains("[" + id + "](")) {
            if (!content.endsWith("\n")) {
                content += "\n";
            }
            content += entry + "\n";
        }
        Files.writeString(indexPath, content);
    }

    public List<MemoryPage> findAll(String projectId) throws IOException {
        Path projectDir = getProjectDir(projectId);
        List<MemoryPage> pages = new ArrayList<>();
        List<Path> dirsToSearch = List.of(projectDir.resolve("raw"), projectDir.resolve("pages"));

        for (Path dir : dirsToSearch) {
            if (Files.exists(dir)) {
                try (var stream = Files.walk(dir)) {
                    stream.filter(p -> p.toString().endsWith(".md"))
                          .filter(Files::isRegularFile)
                          .forEach(p -> {
                              try {
                                  pages.add(MarkdownParser.parse(p));
                              } catch (Exception e) {
                                  log.error("Erro ao fazer parse do arquivo: {} - {}", p, e.getMessage());
                              }
                          });
                }
            }
        }
        return pages;
    }

    /**
     * Lexical search with BM25 ranking, snippet extraction and facet filtering.
     */
    public List<SearchResult> searchLexical(String projectId, String query, int limit) {
        return getIndex(projectId).search(query, limit);
    }

    /**
     * Legacy search returning MemoryPage list for backwards compatibility.
     */
    public List<MemoryPage> search(String projectId, String query) {
        List<SearchResult> results = searchLexical(projectId, query, 20);
        return results.stream().map(SearchResult::getPage).toList();
    }

    public List<String> lint(String projectId) throws IOException {
        List<String> warnings = new ArrayList<>();
        List<MemoryPage> allPages = findAll(projectId);

        List<MemoryPage> pendingRaws = getPendingRaws(projectId);
        Set<String> rawIds = new HashSet<>();
        for (MemoryPage p : pendingRaws) {
            if (p.getId() != null) rawIds.add(p.getId());
        }

        Set<String> validIds = new HashSet<>();
        Map<String, List<String>> tagsToIds = new HashMap<>();

        Path projectDir = getProjectDir(projectId);
        Path indexPath = projectDir.resolve("index.md");
        String indexContent = Files.exists(indexPath) ? Files.readString(indexPath) : "";

        for (MemoryPage page : allPages) {
            if (page.getId() != null) {
                validIds.add(page.getId());
                if (!"superseded".equals(page.getStatus()) && !rawIds.contains(page.getId())) {
                    if (page.getTags() != null && !page.getTags().isEmpty()) {
                        List<String> sortedTags = new ArrayList<>(page.getTags());
                        Collections.sort(sortedTags);
                        String tagKey = String.join(",", sortedTags).toLowerCase();
                        tagsToIds.computeIfAbsent(tagKey, k -> new ArrayList<>()).add(page.getId());
                    }
                }
            } else {
                warnings.add("[ESTRUTURAL] Página encontrada sem ID no YAML (conteúdo: " +
                        (page.getContent() != null && page.getContent().length() > 20
                                ? page.getContent().substring(0, 20) + "..." : page.getContent()) + ")");
            }
        }

        Pattern wikilinkPattern = Pattern.compile("\\[\\[([a-zA-Z0-9_\\-\\s]+)\\]\\]");

        for (MemoryPage page : allPages) {
            if (page.getId() == null) continue;

            // Verifica supersedes
            String supersedes = page.getSupersedes();
            if (supersedes != null && !supersedes.trim().isEmpty()) {
                if (!validIds.contains(supersedes.trim())) {
                    warnings.add("[ORPHAN/SUPERSEDE] A página ID " + page.getId() + " substitui uma página inexistente: " + supersedes);
                }
            }

            if ("superseded".equals(page.getStatus())) continue;

            // Verifica Orphans (não está no index.md)
            if (!indexContent.contains(page.getId()) && !rawIds.contains(page.getId())) {
                warnings.add("[ORPHAN] A página ativa " + page.getId() + " não consta no index.md");
            }

            // Verifica Wikilinks quebrados
            if (page.getContent() != null) {
                Matcher m = wikilinkPattern.matcher(page.getContent());
                while (m.find()) {
                    String targetId = m.group(1).trim();
                    if (!validIds.contains(targetId)) {
                        warnings.add("[DANGLING WIKILINK] A página " + page.getId() + " cita o ID inexistente: [[" + targetId + "]]");
                    }
                }
            }
        }

        // Verifica Duplicates
        for (Map.Entry<String, List<String>> entry : tagsToIds.entrySet()) {
            if (entry.getValue().size() > 1) {
                warnings.add("[DUPLICATE CONTEXT] As páginas a seguir possuem as mesmas tags exatas (" + entry.getKey() + ") e podem precisar de consolidação: " + String.join(", ", entry.getValue()));
            }
        }

        // Alerta de Débito de Consolidação em raw/
        if (pendingRaws.size() > 10) {
            warnings.add(String.format("[DÉBITO DE CONSOLIDAÇÃO] Existem %d notas brutas em raw/ aguardando consolidação via `consolidate`", pendingRaws.size()));
        }

        return warnings;
    }

    public void promoteRules(String projectId, String targetPath) throws IOException {
        Path target = resolveTargetFileForProject(projectId, targetPath);

        List<MemoryPage> allPages = findAll(projectId);
        List<MemoryPage> activeRules = new ArrayList<>();

        for (MemoryPage page : allPages) {
            if (page.getType() == MemoryType.RULE && "active".equalsIgnoreCase(page.getStatus())) {
                activeRules.add(page);
            }
        }

        if (activeRules.isEmpty()) {
            log.info("Nenhuma regra ativa para promover.");
            return;
        }

        StringBuilder rulesBlock = new StringBuilder();
        rulesBlock.append("\n<!-- BEGIN CORTEX RULES -->\n");
        rulesBlock.append("# Regras Promovidas do Projeto Cortex\n\n");
        for (MemoryPage rule : activeRules) {
            rulesBlock.append("## Rule [").append(rule.getId()).append("]\n");
            rulesBlock.append(rule.getContent()).append("\n\n");
        }
        rulesBlock.append("<!-- END CORTEX RULES -->\n");

        if (Files.exists(target)) {
            String existingContent = Files.readString(target);
            if (existingContent.contains("<!-- BEGIN CORTEX RULES -->") && existingContent.contains("<!-- END CORTEX RULES -->")) {
                existingContent = existingContent.replaceAll("(?s)<!-- BEGIN CORTEX RULES -->.*?<!-- END CORTEX RULES -->\n?", Matcher.quoteReplacement(rulesBlock.toString()));
                Files.writeString(target, existingContent);
            } else {
                Files.writeString(target, existingContent + rulesBlock.toString());
            }
        } else {
            if (target.getParent() != null) {
                Files.createDirectories(target.getParent());
            }
            Files.writeString(target, rulesBlock.toString());
        }
    }

    private Path resolveTargetFileForProject(String projectId, String targetPath) throws IOException {
        if (targetPath == null || targetPath.isBlank()) {
            throw new IllegalArgumentException("targetPath não pode ser nulo ou vazio.");
        }
        String trimmed = targetPath.trim();
        boolean isAbsoluteOrDrive = trimmed.startsWith("/") || WINDOWS_DRIVE_PATTERN.matcher(trimmed).matches();
        if (isAbsoluteOrDrive) {
            return resolveProjectPath(trimmed);
        }
        if (File.separatorChar == '/' && trimmed.contains("\\")) {
            throw new IllegalArgumentException("Caminho contém barras invertidas inválidas em ambiente Linux: " + targetPath);
        }

        // Relative path (e.g. .agents/AGENTS.md): check if project has a registered root_path in CortexConfig
        try {
            CortexConfig config = configManager.loadConfig();
            String registeredRoot = null;
            for (Map.Entry<String, String> entry : config.getProjects().entrySet()) {
                if (projectId != null && projectId.equals(entry.getValue())) {
                    registeredRoot = entry.getKey();
                }
            }
            if (registeredRoot != null && !registeredRoot.isBlank()) {
                Path projectRoot = resolveProjectPath(registeredRoot);
                return resolveSegments(projectRoot, trimmed.replace('\\', '/'));
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception ignored) {
        }

        return Paths.get(trimmed);
    }

    public String bootstrap(String projectId, String rootPath) throws IOException {
        Path resolvedRoot = null;
        if (rootPath != null && !rootPath.trim().isEmpty()) {
            resolvedRoot = resolveProjectPath(rootPath);
        }

        if (projectId == null || projectId.trim().isEmpty()) {
            if (resolvedRoot != null && resolvedRoot.getFileName() != null) {
                projectId = resolvedRoot.getFileName().toString();
            } else if (rootPath != null && !rootPath.trim().isEmpty()) {
                String clean = rootPath.trim().replace('\\', '/');
                while (clean.endsWith("/")) {
                    clean = clean.substring(0, clean.length() - 1);
                }
                int idx = clean.lastIndexOf('/');
                projectId = idx >= 0 ? clean.substring(idx + 1) : clean;
            } else {
                throw new IllegalArgumentException("projectId cannot be null or empty");
            }
        }
        validateIdentifier(projectId, "projectId");

        Path projectDir = getProjectDir(projectId);
        init(projectId);

        Path indexPath = projectDir.resolve("index.md");
        if (!Files.exists(indexPath)) {
            String indexContent = "---\n" +
                    "title: Índice do Projeto " + projectId + "\n" +
                    "---\n\n" +
                    "# Índice de Páginas\n\n" +
                    "_Nenhuma página consolidada ainda._\n";
            Files.writeString(indexPath, indexContent);
        }

        Path logPath = projectDir.resolve("log.md");
        if (!Files.exists(logPath)) {
            String logContent = "# Log Cronológico\n\n" +
                    "| Data | Evento | ID |\n" +
                    "|---|---|---|\n" +
                    "| " + Instant.now().toString() + " | Projeto inicializado via bootstrap | - |\n";
            Files.writeString(logPath, logContent);
        }

        if (resolvedRoot != null) {
            configManager.registerProject(resolvedRoot.toString(), projectId);

            Path skillDir = resolvedRoot.resolve(".agents").resolve("skills").resolve("cortex-conventions");
            Path skillFile = skillDir.resolve("SKILL.md");
            if (!Files.exists(skillFile)) {
                Files.createDirectories(skillDir);
                String skillContent = "---\n" +
                        "name: cortex-conventions\n" +
                        "description: Regras globais de memória para o projeto Cortex. Envolve leitura de contexto no começo e escrita de descobertas/regras no final do trabalho usando Cortex MCP.\n" +
                        "---\n\n" +
                        "# Cortex Conventions (Skill Automática)\n\n" +
                        "Você está trabalhando em um projeto governado pelo **Cortex**, um sistema de memória e wiki com base em Markdown.\n\n" +
                        "## Regras de Execução de Tarefa\n\n" +
                        "### 1. Inicialização (Read Context)\n" +
                        "Antes de começar a editar arquivos ou fazer planos, use a ferramenta `query` para buscar conhecimento ativo relevante ao seu objetivo.\n" +
                        "- Busque por páginas do tipo `rule` ou `gotcha` se achar que pode haver convenções específicas da base.\n\n" +
                        "### 2. Ao Concluir (Capture Context)\n" +
                        "No fim da sua tarefa, se você tropeçou em um bug não documentado (Gotcha), tomou uma decisão técnica arquitetural (Decision) ou descobriu um fato novo importante para agentes futuros (Fact), use a ferramenta `capture`.\n\n" +
                        "### 3. Consolidação Automática\n" +
                        "A consolidação deve acontecer de forma automática **sempre ao término de uma sessão** e manual sempre que o usuário solicitar. Chame a ferramenta `consolidate` para ler as notas brutas agrupadas por tópicos e transformá-las em páginas curadas usando `write_page`.\n\n" +
                        "### 4. Promoção de Regras\n" +
                        "Se você criar uma regra ouro (`rule`) e consolidá-la usando `write_page`, lembre-se de promover a regra em seguida usando a tool `promote_rules`.\n\n" +
                        "**Atenção:** Siga a filosofia de que o Cortex é o cérebro persistente. Documente decisões no momento em que ocorrerem.\n";
                Files.writeString(skillFile, skillContent);
            }
        }

        return projectId;
    }

    public String consolidateReport(String projectId, String topic) throws IOException {
        List<MemoryPage> pendingRaws = getPendingRaws(projectId);
        if (topic != null && !topic.trim().isEmpty() && synthesizerService != null) {
            pendingRaws = synthesizerService.filterByTopic(pendingRaws, topic);
        }

        if (pendingRaws.isEmpty()) {
            return "Nenhuma memória raw pendente para consolidar" + (topic != null ? " no tópico: " + topic : "") + ".";
        }

        if (synthesizerService != null) {
            List<SmartSynthesizerService.MemoryCluster> clusters = synthesizerService.clusterPendingRaws(pendingRaws);
            return synthesizerService.formatConsolidationReport(clusters, pendingRaws.size());
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Existem ").append(pendingRaws.size()).append(" memórias raw para consolidar:\n\n");
        for (MemoryPage raw : pendingRaws) {
            sb.append("- **[").append(raw.getId()).append("]** (Tipo: ").append(raw.getType()).append(")\n");
            sb.append("  ").append(raw.getContent().replace("\n", " ")).append("\n\n");
        }
        return sb.toString();
    }

    public CortexStats getStats(String projectId) throws IOException {
        List<MemoryPage> allPages = findAll(projectId);
        long rawCount = getPendingRawCount(projectId);

        int activeCount = 0;
        int supersededCount = 0;
        Map<String, Integer> byType = new LinkedHashMap<>();
        Map<String, Integer> tagCounts = new HashMap<>();

        for (MemoryPage p : allPages) {
            if ("superseded".equalsIgnoreCase(p.getStatus())) {
                supersededCount++;
            } else {
                activeCount++;
                String typeStr = p.getType() != null ? p.getType().getValue() : "note";
                byType.put(typeStr, byType.getOrDefault(typeStr, 0) + 1);

                if (p.getTags() != null) {
                    for (String t : p.getTags()) {
                        tagCounts.put(t.toLowerCase(), tagCounts.getOrDefault(t.toLowerCase(), 0) + 1);
                    }
                }
            }
        }

        KnowledgeGraph graph = getGraph(projectId);
        int nodes = graph.getTotalNodes();
        int edges = graph.getTotalEdges();
        Map<String, Integer> topReferenced = graph.getTopReferenced(5);

        Map<String, Integer> topTags = tagCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(8)
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (e1, e2) -> e1,
                        LinkedHashMap::new
                ));

        return new CortexStats(
                projectId,
                allPages.size(),
                activeCount,
                supersededCount,
                rawCount,
                byType,
                topTags,
                nodes,
                edges,
                topReferenced
        );
    }

    public String handoff(String projectId, String targetAgent) throws IOException {
        List<MemoryPage> allPages = findAll(projectId);

        List<MemoryPage> activePages = new ArrayList<>();
        for (MemoryPage page : allPages) {
            if (!"superseded".equalsIgnoreCase(page.getStatus())) {
                activePages.add(page);
            }
        }

        Map<String, List<MemoryPage>> byType = new LinkedHashMap<>();
        String[] typeOrder = {"rule", "decision", "fact", "gotcha", "note"};
        for (String t : typeOrder) {
            byType.put(t, new ArrayList<>());
        }

        for (MemoryPage page : activePages) {
            String typeKey = page.getType() != null ? page.getType().getValue() : "note";
            byType.computeIfAbsent(typeKey, k -> new ArrayList<>()).add(page);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("# Handoff Report — Projeto: `").append(projectId).append("`\n\n");
        sb.append("**Gerado em:** ").append(Instant.now().toString()).append("\n");
        if (targetAgent != null && !targetAgent.trim().isEmpty()) {
            sb.append("**Agente alvo:** `").append(targetAgent).append("`\n");
        }
        sb.append("\n---\n\n");

        for (Map.Entry<String, List<MemoryPage>> entry : byType.entrySet()) {
            List<MemoryPage> pages = entry.getValue();
            if (pages.isEmpty()) continue;

            String typeName = entry.getKey().substring(0, 1).toUpperCase() + entry.getKey().substring(1);
            sb.append("## ").append(typeName).append("s (").append(pages.size()).append(")\n\n");

            for (MemoryPage page : pages) {
                String snippet = page.getContent() != null
                        ? (page.getContent().length() > 140
                            ? page.getContent().substring(0, 140).replace("\n", " ") + "..."
                            : page.getContent().replace("\n", " "))
                        : "(sem conteúdo)";
                sb.append("- **[").append(page.getId() != null ? page.getId() : "?").append("]** ");
                if (page.getCreatedAt() != null) {
                    sb.append("(").append(page.getCreatedAt()).append(") ");
                }
                sb.append(snippet).append("\n");
            }
            sb.append("\n");
        }

        CortexStats stats = getStats(projectId);
        sb.append("---\n\n");
        sb.append("## 📈 Saúde e Métricas\n\n");
        sb.append(stats.toMarkdownSummary());

        return sb.toString();
    }

    private CompletableFuture<Void> scheduleGitSync(String projectId, String commitMsg) {
        if (!isGitSyncEnabled()) {
            return CompletableFuture.completedFuture(null);
        }
        return projectGitQueues.compute(projectId, (proj, prev) -> {
            CompletableFuture<Void> base = (prev == null)
                    ? CompletableFuture.completedFuture(null)
                    : prev.exceptionally(ex -> null);
            return base.thenRunAsync(() -> {
                gitRepoLock.lock();
                try {
                    gitSyncHandler.accept(proj, commitMsg);
                } catch (Throwable t) {
                    log.error("Failed background Git sync for project [{}]: {}", proj, t.getMessage());
                } finally {
                    gitRepoLock.unlock();
                }
            }, gitSyncExecutor);
        });
    }

    private static String getGitConfigValue(String envKey, String sysPropKey) {
        String prop = System.getProperty(sysPropKey);
        if (prop != null && !prop.isBlank()) {
            return prop;
        }
        return System.getenv(envKey);
    }

    void syncToGit(String commitMsg) {
        if (!isGitSyncEnabled()) {
            return;
        }
        String authorName = getGitConfigValue("GIT_AUTHOR_NAME", "cortex.git.author-name");
        String authorEmail = getGitConfigValue("GIT_AUTHOR_EMAIL", "cortex.git.author-email");
        String remoteUrl = getGitConfigValue("GIT_REMOTE_URL", "cortex.git.remote-url");
        if (authorName == null || authorEmail == null || remoteUrl == null) {
            return;
        }

        try {
            File cortexDir = Paths.get(System.getProperty("user.home"), ".cortex").toFile();
            if (!new File(cortexDir, ".git").exists()) {
                runCommand(cortexDir, "git", "init");
                runCommand(cortexDir, "git", "config", "user.name", authorName);
                runCommand(cortexDir, "git", "config", "user.email", authorEmail);
                runCommand(cortexDir, "git", "config", "core.sshCommand", "ssh -o StrictHostKeyChecking=accept-new");
                runCommand(cortexDir, "git", "remote", "add", "origin", remoteUrl);
            }

            runCommand(cortexDir, "git", "add", ".");
            String status = runCommandWithOutput(cortexDir, "git", "status", "--porcelain");
            if (!status.trim().isEmpty()) {
                runCommand(cortexDir, "git", "commit", "-m", commitMsg);
            }
            runCommand(cortexDir, "git", "branch", "-M", "main");
            runCommand(cortexDir, "git", "push", "-u", "origin", "main");
            log.info("Successfully synced to Git.");
        } catch (Exception e) {
            log.error("Failed to sync to Git: {}", e.getMessage());
        }
    }

    public String runCommandWithOutput(File directory, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory);
        pb.redirectErrorStream(true);
        Process p = pb.start();

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        Thread readerThread = Thread.ofVirtual().start(() -> {
            try {
                p.getInputStream().transferTo(buffer);
            } catch (IOException ignored) {
            }
        });

        boolean completed = p.waitFor(gitCommandTimeoutSeconds, TimeUnit.SECONDS);
        if (!completed) {
            p.destroyForcibly();
            readerThread.interrupt();
            throw new IOException("Git command timed out after " + gitCommandTimeoutSeconds + "s: " + String.join(" ", command));
        }
        readerThread.join(2000);

        int exitCode = p.exitValue();
        String output = buffer.toString(StandardCharsets.UTF_8);
        if (exitCode != 0) {
            log.error("Git command failed with exit code {}: {}", exitCode, output);
            throw new IOException("Git command failed with exit code " + exitCode + ": " + output);
        }
        return output;
    }

    public void runCommand(File directory, String... command) throws IOException, InterruptedException {
        runCommandWithOutput(directory, command);
    }

    @PreDestroy
    public void shutdown() {
        gitSyncExecutor.shutdown();
        try {
            if (!gitSyncExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                gitSyncExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            gitSyncExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    public ExecutorService getGitSyncExecutor() {
        return gitSyncExecutor;
    }
}
