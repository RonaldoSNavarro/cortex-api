package com.cortex.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Grafo de conhecimento direcionado em memória para rastreamento de wikilinks ([[alvo]]) e relações de supersedes.
 * Fornece índices de outlinks, backlinks e análise de impacto em tempo real com garantia de consistência via ReentrantReadWriteLock.
 */
public class KnowledgeGraph {

    private static final Pattern WIKILINK_PATTERN = Pattern.compile("\\[\\[([a-zA-Z0-9_\\-\\s]+)\\]\\]");

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    // pageId -> Set of page IDs referenced by this page (outlinks)
    private final Map<String, Set<String>> outlinks = new ConcurrentHashMap<>();

    // targetId -> Set of page IDs referencing target (backlinks)
    private final Map<String, Set<String>> backlinks = new ConcurrentHashMap<>();

    // titleOrTag -> Set of page IDs matching that title or tag
    private final Map<String, Set<String>> aliasToPageIds = new ConcurrentHashMap<>();

    public void indexPage(MemoryPage page) {
        if (page == null || page.getId() == null) return;
        rwLock.writeLock().lock();
        try {
            String pageId = page.getId();
            removePageInternal(pageId);

            // Index aliases (ID, tags, main title if any)
            aliasToPageIds.computeIfAbsent(pageId.toLowerCase(), k -> ConcurrentHashMap.newKeySet()).add(pageId);
            if (page.getTags() != null) {
                for (String tag : page.getTags()) {
                    aliasToPageIds.computeIfAbsent(tag.toLowerCase(), k -> ConcurrentHashMap.newKeySet()).add(pageId);
                }
            }

            Set<String> pageOutlinks = new HashSet<>();

            // 1. Wikilinks in markdown content [[link]]
            if (page.getContent() != null) {
                Matcher m = WIKILINK_PATTERN.matcher(page.getContent());
                while (m.find()) {
                    String rawTarget = m.group(1).trim().toLowerCase();
                    pageOutlinks.add(rawTarget);
                }
            }

            // 2. Supersedes relation
            if (page.getSupersedes() != null && !page.getSupersedes().trim().isEmpty()) {
                pageOutlinks.add(page.getSupersedes().trim().toLowerCase());
            }

            outlinks.put(pageId, pageOutlinks);
            page.setLinks(new ArrayList<>(pageOutlinks));

            for (String target : pageOutlinks) {
                backlinks.computeIfAbsent(target, k -> ConcurrentHashMap.newKeySet()).add(pageId);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void removePage(String pageId) {
        if (pageId == null) return;
        rwLock.writeLock().lock();
        try {
            removePageInternal(pageId);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void removePageInternal(String pageId) {
        Set<String> targets = outlinks.remove(pageId);
        if (targets != null) {
            for (String target : targets) {
                Set<String> inSet = backlinks.get(target);
                if (inSet != null) {
                    inSet.remove(pageId);
                    if (inSet.isEmpty()) backlinks.remove(target);
                }
            }
        }

        // Remove from aliases
        for (Set<String> ids : aliasToPageIds.values()) {
            ids.remove(pageId);
        }

        // Remove direct backlink entries where pageId was the target
        backlinks.remove(pageId.toLowerCase());
    }

    public void clear() {
        rwLock.writeLock().lock();
        try {
            outlinks.clear();
            backlinks.clear();
            aliasToPageIds.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public Set<String> getBacklinks(String pageIdOrAlias) {
        if (pageIdOrAlias == null) return Collections.emptySet();
        rwLock.readLock().lock();
        try {
            return new HashSet<>(backlinks.getOrDefault(pageIdOrAlias.toLowerCase(), Collections.emptySet()));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Set<String> getOutlinks(String pageId) {
        if (pageId == null) return Collections.emptySet();
        rwLock.readLock().lock();
        try {
            return new HashSet<>(outlinks.getOrDefault(pageId, Collections.emptySet()));
        } finally {
            rwLock.readLock().unlock();
        }
    }

    /**
     * Resolve páginas impactadas quando uma página é substituída ou alterada.
     */
    public Set<String> getImpactedPagesOnSupersede(String supersededId) {
        if (supersededId == null) return Collections.emptySet();
        rwLock.readLock().lock();
        try {
            Set<String> impacted = new HashSet<>(getBacklinks(supersededId));

            // Checar também aliases
            Set<String> resolvedIds = aliasToPageIds.getOrDefault(supersededId.toLowerCase(), Collections.emptySet());
            for (String resolvedId : resolvedIds) {
                impacted.addAll(getBacklinks(resolvedId));
            }

            // Remove self-reference se presente
            impacted.remove(supersededId);
            return impacted;
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int getTotalNodes() {
        rwLock.readLock().lock();
        try {
            return outlinks.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public int getTotalEdges() {
        rwLock.readLock().lock();
        try {
            return outlinks.values().stream().mapToInt(Set::size).sum();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Map<String, Integer> getTopReferenced(int limit) {
        rwLock.readLock().lock();
        try {
            Map<String, Integer> counts = new HashMap<>();
            for (Map.Entry<String, Set<String>> entry : backlinks.entrySet()) {
                counts.put(entry.getKey(), entry.getValue().size());
            }
            return counts.entrySet().stream()
                    .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                    .limit(limit > 0 ? limit : 10)
                    .collect(Collectors.toMap(
                            Map.Entry::getKey,
                            Map.Entry::getValue,
                            (e1, e2) -> e1,
                            LinkedHashMap::new
                    ));
        } finally {
            rwLock.readLock().unlock();
        }
    }
}
