package com.cortex.core;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;



@Service
@Slf4j
public class ProjectRepository {
    private final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());

    // Caching
    private static class CacheEntry {
        List<MemoryPage> pages;
        long lastModified;
        CacheEntry(List<MemoryPage> pages, long lastModified) {
            this.pages = pages;
            this.lastModified = lastModified;
        }
    }
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

private Path getProjectDir(String projectId) {
        return Paths.get(System.getProperty("user.home"), ".cortex", "projects", projectId);
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
    }

    public void saveRaw(String projectId, String fileName, String content) throws IOException {
        Path projectDir = getProjectDir(projectId);
        Path rawPath = projectDir.resolve("raw").resolve(fileName);
        Files.writeString(rawPath, content);
    }

    public String createRaw(String projectId, String typeStr, String content) throws IOException {
        Path projectDir = getProjectDir(projectId);
        MemoryType type = MemoryType.fromValue(typeStr);
        if (type == null) {
            throw new IllegalArgumentException("Tipo de memória inválido: " + typeStr);
        }
        
        String dateStr = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String id = "raw-" + dateStr + "-" + java.util.UUID.randomUUID().toString().substring(0, 4);
        
        Map<String, Object> frontmatter = new LinkedHashMap<>();
        frontmatter.put("id", id);
        frontmatter.put("type", type.getValue());
        frontmatter.put("project", projectId);
        frontmatter.put("created_at", java.time.Instant.now().toString());
        
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
        try (java.util.stream.Stream<Path> stream = Files.walk(rawDir)) {
            return stream.filter(p -> p.toString().endsWith(".md"))
                         .filter(Files::isRegularFile)
                         .count();
        }
    }

    public java.util.List<MemoryPage> getPendingRaws(String projectId) throws IOException {
        Path projectDir = getProjectDir(projectId);
        Path rawDir = projectDir.resolve("raw");
        java.util.List<MemoryPage> rawPages = new java.util.ArrayList<>();
        if (!Files.exists(rawDir)) return rawPages;
        
        try (java.util.stream.Stream<Path> stream = Files.walk(rawDir)) {
            stream.filter(p -> p.toString().endsWith(".md"))
                  .filter(Files::isRegularFile)
                  .forEach(p -> {
                      try {
                          rawPages.add(MarkdownParser.parse(p));
                      } catch (Exception e) {
                          log.error("Erro ao fazer parse do arquivo raw: " + p + " - " + e.getMessage());
                      }
                  });
        }
        return rawPages;
    }

    public void updatePageStatus(String projectId, String pageId, String newStatus) throws IOException {
        Path projectDir = getProjectDir(projectId);
        java.util.List<Path> dirsToSearch = java.util.Arrays.asList(
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
                        log.error("Erro ao carregar página para atualizar status: " + e.getMessage());
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
                    return;
                }
            }
        }
        log.error("Aviso: Página a ser substituída não encontrada (" + pageId + ")");
    }

    public String writePage(String projectId, String typeStr, java.util.List<String> tags, String supersedes, java.util.List<String> consumedRawIds, String content) throws IOException {
        Path projectDir = getProjectDir(projectId);
        MemoryType type = MemoryType.fromValue(typeStr);
        if (type == null) {
            throw new IllegalArgumentException("Tipo de memória inválido: " + typeStr);
        }

        if (supersedes != null && !supersedes.trim().isEmpty()) {
            updatePageStatus(projectId, supersedes.trim(), "superseded");
        }

        String dateStr = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        String id = dateStr + "-" + java.util.UUID.randomUUID().toString().substring(0, 4);
        if (tags != null && !tags.isEmpty()) {
            String mainTag = tags.get(0).toLowerCase().replaceAll("[^a-z0-9]", "-");
            id = dateStr + "-" + mainTag + "-" + java.util.UUID.randomUUID().toString().substring(0, 4);
        }
        
        Map<String, Object> fm = new LinkedHashMap<>();
        fm.put("id", id);
        fm.put("type", type.getValue());
        fm.put("project", projectId);
        fm.put("status", "active");
        if (supersedes != null && !supersedes.trim().isEmpty()) fm.put("supersedes", supersedes.trim());
        if (tags != null && !tags.isEmpty()) fm.put("tags", tags);
        fm.put("created_at", java.time.Instant.now().toString());

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
                }
            }
        }
        
        updateIndex(projectDir, id, typeStr, tags);
        appendLog(projectDir, "write | Página consolidada: " + id + " (substitui: " + supersedes + ")");
        
        syncToGit("Auto-consolidado pelo Cortex: " + id);
        
        return id;
    }

    private void appendLog(Path projectDir, String message) throws IOException {
        Path logPath = projectDir.resolve("log.md");
        String timestamp = java.time.Instant.now().toString();
        String logEntry = "## [" + timestamp + "] " + message + "\n";
        Files.writeString(logPath, logEntry, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
    }

    private void updateIndex(Path projectDir, String id, String typeStr, java.util.List<String> tags) throws IOException {
        Path indexPath = projectDir.resolve("index.md");
        String tagStr = tags != null ? String.join(", ", tags) : "sem tags";
        String entry = "- [" + id + "](" + "pages/" + id + ".md" + ") | Tipo: " + typeStr + " | Tags: " + tagStr + "\n";
        
        if (!Files.exists(indexPath)) {
            String header = "# Índice do Projeto\n\nCatálogo de todas as páginas consolidadas.\n\n";
            Files.writeString(indexPath, header, java.nio.file.StandardOpenOption.CREATE);
        }
        Files.writeString(indexPath, entry, java.nio.file.StandardOpenOption.APPEND);
    }

    public java.util.List<MemoryPage> findAll(String projectId) throws IOException {
        Path projectDir = getProjectDir(projectId);
        
        java.util.List<Path> dirsToSearch = java.util.Arrays.asList(
            projectDir.resolve("raw"),
            projectDir.resolve("pages")
        );
        
        long latestMod = 0;
        for (Path dir : dirsToSearch) {
            if (Files.exists(dir)) {
                long mod = dir.toFile().lastModified();
                if (mod > latestMod) latestMod = mod;
            }
        }
        
        CacheEntry entry = cache.get(projectId);
        if (entry != null && entry.lastModified == latestMod) {
            return entry.pages;
        }

        java.util.List<MemoryPage> pages = new java.util.ArrayList<>();
        
        for (Path dir : dirsToSearch) {
            if (Files.exists(dir)) {
                try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
                    stream.filter(p -> p.toString().endsWith(".md"))
                          .filter(Files::isRegularFile)
                          .forEach(p -> {
                              try {
                                  pages.add(MarkdownParser.parse(p));
                              } catch (Exception e) {
                                  log.error("Erro ao fazer parse do arquivo: " + p + " - " + e.getMessage());
                              }
                          });
                }
            }
        }
        
        cache.put(projectId, new CacheEntry(pages, latestMod));
        return pages;
    }

    public java.util.List<MemoryPage> search(String projectId, String query) throws IOException {
        Path projectDir = getProjectDir(projectId);
        java.util.List<MemoryPage> results = new java.util.ArrayList<>();
        String lowerQuery = query.toLowerCase();

        java.util.List<Path> dirsToSearch = java.util.Arrays.asList(
            projectDir.resolve("raw"),
            projectDir.resolve("pages")
        );
        
        for (Path dir : dirsToSearch) {
            if (Files.exists(dir)) {
                try (java.util.stream.Stream<Path> stream = Files.walk(dir)) {
                    stream.filter(p -> p.toString().endsWith(".md"))
                          .filter(Files::isRegularFile)
                          .map(p -> {
                              try {
                                  return MarkdownParser.parse(p);
                              } catch (Exception e) {
                                  return null;
                              }
                          })
                          .filter(java.util.Objects::nonNull)
                          .filter(page -> {
                              if (page.getTags() != null) {
                                  for (String tag : page.getTags()) {
                                      if (tag.toLowerCase().contains(lowerQuery)) return true;
                                  }
                              }
                              if (page.getContent() != null && page.getContent().toLowerCase().contains(lowerQuery)) return true;
                              if (page.getId() != null && page.getId().toLowerCase().contains(lowerQuery)) return true;
                              if (page.getType() != null && page.getType().toString().toLowerCase().contains(lowerQuery)) return true;
                              return false;
                          })
                          .forEach(results::add);
                }
            }
        }
        
        return results;
    }

    public java.util.List<String> lint(String projectId) throws IOException {
        java.util.List<String> warnings = new java.util.ArrayList<>();
        java.util.List<MemoryPage> allPages = findAll(projectId);
        
        java.util.List<MemoryPage> pendingRaws = getPendingRaws(projectId);
        java.util.Set<String> rawIds = new java.util.HashSet<>();
        for (MemoryPage p : pendingRaws) {
            if (p.getId() != null) rawIds.add(p.getId());
        }
        
        java.util.Set<String> validIds = new java.util.HashSet<>();
        java.util.Map<String, java.util.List<String>> tagsToIds = new java.util.HashMap<>();
        
        Path projectDir = getProjectDir(projectId);
        Path indexPath = projectDir.resolve("index.md");
        String indexContent = Files.exists(indexPath) ? Files.readString(indexPath) : "";

        for (MemoryPage page : allPages) {
            if (page.getId() != null) {
                validIds.add(page.getId());
                if (!"superseded".equals(page.getStatus()) && !rawIds.contains(page.getId())) {
                    if (page.getTags() != null && !page.getTags().isEmpty()) {
                        java.util.List<String> sortedTags = new java.util.ArrayList<>(page.getTags());
                        java.util.Collections.sort(sortedTags);
                        String tagKey = String.join(",", sortedTags).toLowerCase();
                        tagsToIds.computeIfAbsent(tagKey, k -> new java.util.ArrayList<>()).add(page.getId());
                    }
                }
            } else {
                warnings.add("[ESTRUTURAL] Página encontrada sem ID no YAML (conteúdo: " + 
                    (page.getContent() != null && page.getContent().length() > 20 
                    ? page.getContent().substring(0, 20) + "..." : page.getContent()) + ")");
            }
        }
        
        java.util.regex.Pattern linkPattern = java.util.regex.Pattern.compile("\\[([a-zA-Z0-9_\\-]+)\\]");

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
            
            // Verifica Dangling Links
            if (page.getContent() != null) {
                java.util.regex.Matcher m = linkPattern.matcher(page.getContent());
                while (m.find()) {
                    String possibleId = m.group(1);
                    // Ignora se for muito curto, foca no que se parece com ID do Cortex (tem hífen e > 8 chars)
                    if (possibleId.length() > 8 && possibleId.contains("-") && !validIds.contains(possibleId)) {
                        warnings.add("[DANGLING LINK] A página " + page.getId() + " cita um ID inexistente: " + possibleId);
                    }
                }
            }
        }
        
        // Verifica Duplicates
        for (java.util.Map.Entry<String, java.util.List<String>> entry : tagsToIds.entrySet()) {
            if (entry.getValue().size() > 1) {
                warnings.add("[DUPLICATE CONTEXT] As páginas a seguir possuem as mesmas tags exatas (" + entry.getKey() + ") e podem precisar de consolidação: " + String.join(", ", entry.getValue()));
            }
        }
        
        return warnings;
    }

    public void promoteRules(String projectId, String targetPath) throws IOException {
        java.util.List<MemoryPage> allPages = findAll(projectId);
        java.util.List<MemoryPage> activeRules = new java.util.ArrayList<>();
        
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
        // 1. Criar estrutura de diretórios do projeto
        init(projectId);

        // 2. Criar index.md se não existir
        Path indexPath = projectDir.resolve("index.md");
        if (!Files.exists(indexPath)) {
            String indexContent = "---\n" +
                    "title: Índice do Projeto " + projectId + "\n" +
                    "---\n\n" +
                    "# Índice de Páginas\n\n" +
                    "_Nenhuma página consolidada ainda._\n";
            Files.writeString(indexPath, indexContent);
        }

        // 3. Criar log.md se não existir
        Path logPath = projectDir.resolve("log.md");
        if (!Files.exists(logPath)) {
            String logContent = "# Log Cronológico\n\n" +
                    "| Data | Evento | ID |\n" +
                    "|---|---|---|\n" +
                    "| " + java.time.Instant.now().toString() + " | Projeto inicializado via bootstrap | - |\n";
            Files.writeString(logPath, logContent);
        }

        // 4. Registrar no config.yaml
        if (rootPath != null && !rootPath.trim().isEmpty()) {
            CortexConfigManager configManager = new CortexConfigManager();
            CortexConfig config = configManager.loadConfig();
            config.getProjects().put(rootPath, projectId);
            configManager.saveConfig(config);
        }

        // 5. Ejetar Skill de convenções no repositório de código
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
                        "A consolidação deve acontecer de forma automática **sempre ao término de uma sessão** e manual sempre que o usuário solicitar. Chame a ferramenta `consolidate` para ler as notas brutas e transformá-las em páginas curadas usando `write_page`.\n\n" +
                        "### 4. Promoção de Regras\n" +
                        "Se você criar uma regra ouro (`rule`) e consolidá-la usando `write_page`, lembre-se de promover a regra em seguida usando a tool `promote_rules`.\n\n" +
                        "**Atenção:** Siga a filosofia de que o Cortex é o cérebro persistente. Documente decisões no momento em que ocorrerem.\n";
                Files.writeString(skillFile, skillContent);
            }
        }

        return projectId;
    }

    public String handoff(String projectId, String targetAgent) throws IOException {
        java.util.List<MemoryPage> allPages = findAll(projectId);

        // Filtrar apenas memórias ativas
        java.util.List<MemoryPage> activePages = new java.util.ArrayList<>();
        for (MemoryPage page : allPages) {
            if (!"superseded".equalsIgnoreCase(page.getStatus())) {
                activePages.add(page);
            }
        }

        // Agrupar por tipo
        java.util.Map<String, java.util.List<MemoryPage>> byType = new java.util.LinkedHashMap<>();
        String[] typeOrder = {"rule", "decision", "fact", "gotcha", "note"};
        for (String t : typeOrder) {
            byType.put(t, new java.util.ArrayList<>());
        }

        for (MemoryPage page : activePages) {
            String typeKey = page.getType() != null ? page.getType().getValue() : "note";
            byType.computeIfAbsent(typeKey, k -> new java.util.ArrayList<>()).add(page);
        }

        // Construir resumo
        StringBuilder sb = new StringBuilder();
        sb.append("# Handoff Report — Projeto: ").append(projectId).append("\n\n");
        sb.append("**Gerado em:** ").append(java.time.Instant.now().toString()).append("\n");
        if (targetAgent != null && !targetAgent.trim().isEmpty()) {
            sb.append("**Agente alvo:** ").append(targetAgent).append("\n");
        }
        sb.append("\n---\n\n");

        for (java.util.Map.Entry<String, java.util.List<MemoryPage>> entry : byType.entrySet()) {
            java.util.List<MemoryPage> pages = entry.getValue();
            if (pages.isEmpty()) continue;

            String typeName = entry.getKey().substring(0, 1).toUpperCase() + entry.getKey().substring(1);
            sb.append("## ").append(typeName).append("s (").append(pages.size()).append(")\n\n");

            for (MemoryPage page : pages) {
                String snippet = page.getContent() != null
                        ? (page.getContent().length() > 120
                            ? page.getContent().substring(0, 120).replace("\n", " ") + "..."
                            : page.getContent().replace("\n", " "))
                        : "(sem conteúdo)";
                sb.append("- **[").append(page.getId() != null ? page.getId().substring(0, 8) : "?").append("]** ");
                if (page.getCreatedAt() != null) {
                    sb.append("(").append(page.getCreatedAt()).append(") ");
                }
                sb.append(snippet).append("\n");
            }
            sb.append("\n");
        }

        // Estatísticas
        long rawCount = getPendingRawCount(projectId);
        long ruleCount = byType.getOrDefault("rule", java.util.Collections.emptyList()).size();
        sb.append("---\n\n");
        sb.append("## Estatísticas\n\n");
        sb.append("| Métrica | Valor |\n");
        sb.append("|---|---|\n");
        sb.append("| Memórias ativas | ").append(activePages.size()).append(" |\n");
        sb.append("| Pendentes em raw/ | ").append(rawCount).append(" |\n");
        sb.append("| Regras ativas | ").append(ruleCount).append(" |\n");

        return sb.toString();
    }

    private void syncToGit(String commitMsg) {
        String authorName = System.getenv("GIT_AUTHOR_NAME");
        String authorEmail = System.getenv("GIT_AUTHOR_EMAIL");
        String remoteUrl = System.getenv("GIT_REMOTE_URL");
        if (authorName == null || authorEmail == null || remoteUrl == null) {
            log.warn("Git credentials are not fully configured in environment. Skipping git sync.");
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
            log.error("Failed to sync to Git", e);
        }
    }

    private void runCommand(File directory, String... command) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(directory);
        Process p = pb.start();
        int exitCode = p.waitFor();
        if (exitCode != 0) {
            String errorMsg = new String(p.getErrorStream().readAllBytes());
            log.error("Git command failed with exit code " + exitCode + ": " + errorMsg);
            throw new IOException("Git command failed: " + errorMsg);
        }
    }
}
