package com.cortex.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reactive file-system watcher using Java 25 Virtual Threads and NIO WatchService.
 * Automatically keeps InMemoryLexicalIndex and KnowledgeGraph synchronized in real-time.
 */
@Service
public class ProjectWatcherService {

    private static final Logger log = LoggerFactory.getLogger(ProjectWatcherService.class);

    private final Map<String, WatchService> projectWatchers = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> watchKeyToDir = new ConcurrentHashMap<>();
    private final Map<WatchKey, String> watchKeyToProject = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public void registerProject(String projectId, Path projectDir, InMemoryLexicalIndex index, KnowledgeGraph graph) {
        if (projectId == null || projectDir == null || !Files.exists(projectDir)) return;
        if (projectWatchers.containsKey(projectId)) return;

        try {
            WatchService watchService = FileSystems.getDefault().newWatchService();
            projectWatchers.put(projectId, watchService);

            Path pagesDir = projectDir.resolve("pages");
            Path rawDir = projectDir.resolve("raw");
            Path rulesDir = projectDir.resolve("rules");

            registerDir(watchService, pagesDir, projectId);
            registerDir(watchService, rawDir, projectId);
            registerDir(watchService, rulesDir, projectId);

            // Java 25 Virtual Thread
            Thread.ofVirtual().name("cortex-watcher-" + projectId).start(() -> {
                while (running) {
                    WatchKey key;
                    try {
                        key = watchService.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    } catch (ClosedWatchServiceException e) {
                        break;
                    }

                    Path dir = watchKeyToDir.get(key);
                    String pId = watchKeyToProject.get(key);

                    if (dir != null && pId != null) {
                        for (WatchEvent<?> event : key.pollEvents()) {
                            WatchEvent.Kind<?> kind = event.kind();
                            if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                            @SuppressWarnings("unchecked")
                            WatchEvent<Path> ev = (WatchEvent<Path>) event;
                            Path filename = ev.context();
                            Path fullPath = dir.resolve(filename);

                            if (filename.toString().endsWith(".md")) {
                                if (kind == StandardWatchEventKinds.ENTRY_CREATE || kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                                    handleFileUpdate(fullPath, index, graph);
                                } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                                    String id = filename.toString().replace(".md", "");
                                    index.removePage(id);
                                    graph.removePage(id);
                                    log.debug("Watcher: removida página [{}] do projeto [{}]", id, pId);
                                }
                            }
                        }
                    }

                    boolean valid = key.reset();
                    if (!valid) {
                        watchKeyToDir.remove(key);
                        watchKeyToProject.remove(key);
                    }
                }
            });

            log.info("ProjectWatcherService ativo para o projeto [{}] em thread virtual.", projectId);
        } catch (Exception e) {
            log.warn("Não foi possível registrar WatchService para [{}]: {}", projectId, e.getMessage());
        }
    }

    private void registerDir(WatchService watchService, Path dir, String projectId) {
        if (!Files.exists(dir)) {
            try {
                Files.createDirectories(dir);
            } catch (IOException ignored) {
            }
        }
        if (Files.exists(dir)) {
            try {
                WatchKey key = dir.register(
                        watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE
                );
                watchKeyToDir.put(key, dir);
                watchKeyToProject.put(key, projectId);
            } catch (IOException e) {
                log.warn("Erro ao registrar subdiretório {}: {}", dir, e.getMessage());
            }
        }
    }

    private void handleFileUpdate(Path filePath, InMemoryLexicalIndex index, KnowledgeGraph graph) {
        try {
            // Breve delay para evitar race condition na escrita completa do arquivo
            Thread.sleep(30);
            if (Files.exists(filePath) && Files.isRegularFile(filePath)) {
                MemoryPage page = MarkdownParser.parse(filePath);
                if (page != null && page.getId() != null) {
                    index.indexPage(page);
                    graph.indexPage(page);
                    log.debug("Watcher: indexada página [{}]", page.getId());
                }
            }
        } catch (Exception e) {
            log.debug("Watcher ignorou arquivo não parseável: {} ({})", filePath, e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        for (WatchService ws : projectWatchers.values()) {
            try {
                ws.close();
            } catch (IOException ignored) {
            }
        }
        projectWatchers.clear();
        watchKeyToDir.clear();
        watchKeyToProject.clear();
    }
}
