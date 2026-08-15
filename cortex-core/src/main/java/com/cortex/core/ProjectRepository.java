package com.cortex.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ProjectRepository {

    private static final Logger log = LoggerFactory.getLogger(ProjectRepository.class);
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    @Autowired(required = false)
    private ProjectWatcherService watcherService;

    @Autowired(required = false)
    private SmartSynthesizerService synthesizerService;

    // Per-project in-memory indexes and knowledge graphs
    private final Map<String, InMemoryLexicalIndex> indexes = new ConcurrentHashMap<>();
    private final Map<String, KnowledgeGraph> graphs = new ConcurrentHashMap<>();

    public ProjectRepository() {
    }

    public ProjectRepository(ProjectWatcherService watcherService, SmartSynthesizerService synthesizerService) {
        this.watcherService = watcherService;
        this.synthesizerService = synthesizerService;
    }

    private Path getProjectDir(String projectId) {
        if (projectId == null || projectId.trim().isEmpty()) {
            throw new IllegalArgumentException("projectId cannot be null or empty");
        }
        return Paths.get(System.getProperty("user.home"), ".cortex", "projects", projectId);
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
        Path projectDir = getProjectDir(projectId);
        Path rawPath = projectDir.resolve("raw").resolve(fileName);
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
        appendLog(projectDir, "ingest | Raw memory criada: " + id);
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
        Path projectDir = getProjectDir(projectId);
        List<Path> dirsToSearch = List.of(
                projectDir.resolve("pages"),
                projectDir.resolve("raw")
        );
        for (Path dir : dirsToSearch) {
            if (Files.exists(dir)) {
                Path file = dir.resolve(pageId + ".md");
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
        Path projectDir = getProjectDir(projectId);
        MemoryType type = MemoryType.fromValue(typeStr);
        if (type == null) {
            throw new IllegalArgumentException("Tipo de memória inválido: " + typeStr);
        }

        Set<String> impactedPages = Collections.emptySet();
        if (supersedes != null && !supersedes.trim().isEmpty()) {
            impactedPages = getGraph(projectId).getImpactedPagesOnSupersede(supersedes.trim());
            updatePageStatus(projectId, supersedes.trim(), "superseded");
        }

        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String id = dateStr + "-" + UUID.randomUUID().toString().substring(0, 4);
        if (tags != null && !tags.isEmpty()) {
            String mainTag = tags.get(0).toLowerCase().replaceAll("[^a-z0-9]", "-");
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

        Path pagesPath = projectDir.resolve("pages").resolve(id + ".md");
        Files.writeString(pagesPath, yamlString);

        if (consumedRawIds != null && !consumedRawIds.isEmpty()) {
            Path rawDir = projectDir.resolve("raw");
            for (String rawId : consumedRawIds) {
                Path rawFile = rawDir.resolve(rawId + ".md");
                if (Files.exists(rawFile)) {
                    Files.delete(rawFile);
                    getIndex(projectId).removePage(rawId);
                    getGraph(projectId).removePage(rawId);
                }
            }
        }

        updateIndex(projectDir, id, typeStr, tags);
        appendLog(projectDir, "write | Página consolidada: " + id + " (substitui: " + supersedes + ")");

        // Index new page
        try {
            MemoryPage newPage = MarkdownParser.parse(pagesPath);
            getIndex(projectId).indexPage(newPage);
            getGraph(projectId).indexPage(newPage);
        } catch (Exception ignored) {
        }

        syncToGit("Auto-consolidado pelo Cortex: " + id);

        StringBuilder res = new StringBuilder();
        res.append("Página salva com sucesso. ID: ").append(id);
        if (!impactedPages.isEmpty()) {
            res.append("\n⚠️ **Alerta de Impacto**: As seguintes páginas continham referências à página substituída [")
               .append(supersedes).append("] e podem precisar de revisão: `")
               .append(String.join("`, `", impactedPages)).append("`");
        }
        return res.toString();
    }

    private void appendLog(Path projectDir, String message) throws IOException {
        Path logPath = projectDir.resolve("log.md");
        String timestamp = Instant.now().toString();
        String logEntry = "## [" + timestamp + "] " + message + "\n";
        Files.writeString(logPath, logEntry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private void updateIndex(Path projectDir, String id, String typeStr, List<String> tags) throws IOException {
        Path indexPath = projectDir.resolve("index.md");
        String tagStr = tags != null ? String.join(", ", tags) : "sem tags";
        String entry = "- [" + id + "](" + "pages/" + id + ".md" + ") | Tipo: " + typeStr + " | Tags: " + tagStr + "\n";

        if (!Files.exists(indexPath)) {
            String header = "# Índice do Projeto\n\nCatálogo de todas as páginas consolidadas.\n\n";
            Files.writeString(indexPath, header, StandardOpenOption.CREATE);
        }
        Files.writeString(indexPath, entry, StandardOpenOption.APPEND);
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

        Pattern wikilinkPattern = Pattern.compile("\\[\\[([a-zA-Z0-9_\\-]+)\\]\\]");

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
                    String targetId = m.group(1);
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

        Path target = Paths.get(targetPath);
        if (Files.exists(target)) {
            String existingContent = Files.readString(target);
            if (existingContent.contains("<!-- BEGIN CORTEX RULES -->") && existingContent.contains("<!-- END CORTEX RULES -->")) {
                existingContent = existingContent.replaceAll("(?s)<!-- BEGIN CORTEX RULES -->.*?<!-- END CORTEX RULES -->\n?", rulesBlock.toString());
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

    public String bootstrap(String projectId, String rootPath) throws IOException {
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

        if (rootPath != null && !rootPath.trim().isEmpty()) {
            CortexConfigManager configManager = new CortexConfigManager();
            CortexConfig config = configManager.loadConfig();
            config.getProjects().put(rootPath, projectId);
            configManager.saveConfig(config);
        }

        if (rootPath != null && !rootPath.trim().isEmpty()) {
            Path skillDir = Paths.get(rootPath, ".agents", "skills", "cortex-conventions");
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

    private void syncToGit(String commitMsg) {
        String authorName = System.getenv("GIT_AUTHOR_NAME");
        String authorEmail = System.getenv("GIT_AUTHOR_EMAIL");
        String remoteUrl = System.getenv("GIT_REMOTE_URL");
        if (authorName == null || authorEmail == null || remoteUrl == null) {
            return;
        }

        try {
            File cortexDir = Paths.get(System.getProperty("user.home"), ".cortex").toFile();
            if (!new File(cortexDir, ".git").exists()) {
                runCommand(cortexDir, "git", "init");
                runCommand(cortexDir, "git", "config", "user.name", authorName);
                runCommand(cortexDir, "git", "config", "user.email", authorEmail);
                runCommand(cortexDir, "git", "config", "core.sshCommand", "ssh -o StrictHostKeyChecking=no");
                runCommand(cortexDir, "git", "remote", "add", "origin", remoteUrl);
            }

            runCommand(cortexDir, "git", "add", ".");
            runCommand(cortexDir, "git", "commit", "-m", commitMsg);
            runCommand(cortexDir, "git", "branch", "-M", "main");
            runCommand(cortexDir, "git", "push", "-u", "origin", "main");
            log.info("Successfully synced to Git.");
        } catch (Exception e) {
            log.error("Failed to sync to Git: {}", e.getMessage());
        }
    }

    private void runCommand(File directory, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory);
        Process p = pb.start();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            String errorMsg = new String(p.getErrorStream().readAllBytes());
            log.error("Git command failed with exit code {}: {}", exitCode, errorMsg);
            throw new IOException("Git command failed: " + errorMsg);
        }
    }
}
