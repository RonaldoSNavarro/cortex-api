package com.cortex.core;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * In-memory directed knowledge graph tracking wikilinks ([[target]]) and supersedes relations.
 * Provides outlink/backlink indexes and dependency impact analysis.
 */
public class KnowledgeGraph {

    private static final Pattern WIKILINK_PATTERN = Pattern.compile("\\[\\[([a-zA-Z0-9_\\-\\s]+)\\]\\]");

    // pageId -> Set of page IDs referenced by this page (outlinks)
    private final Map<String, Set<String>> outlinks = new ConcurrentHashMap<>();

    // targetId -> Set of page IDs referencing target (backlinks)
    private final Map<String, Set<String>> backlinks = new ConcurrentHashMap<>();

    // titleOrTag -> Set of page IDs matching that title or tag
    private final Map<String, Set<String>> aliasToPageIds = new ConcurrentHashMap<>();

    public synchronized void indexPage(MemoryPage page) {
        if (page == null || page.getId() == null) return;
        String pageId = page.getId();
        removePage(pageId);

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
    }

    public synchronized void removePage(String pageId) {
        if (pageId == null) return;
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

    public synchronized void clear() {
        outlinks.clear();
        backlinks.clear();
        aliasToPageIds.clear();
    }

    public Set<String> getBacklinks(String pageIdOrAlias) {
        if (pageIdOrAlias == null) return Collections.emptySet();
        return new HashSet<>(backlinks.getOrDefault(pageIdOrAlias.toLowerCase(), Collections.emptySet()));
    }

    public Set<String> getOutlinks(String pageId) {
        if (pageId == null) return Collections.emptySet();
        return new HashSet<>(outlinks.getOrDefault(pageId, Collections.emptySet()));
    }

    /**
     * Resolves impacted pages when a page is superseded or modified.
     */
    public Set<String> getImpactedPagesOnSupersede(String supersededId) {
        if (supersededId == null) return Collections.emptySet();
        Set<String> impacted = new HashSet<>();
        impacted.addAll(getBacklinks(supersededId));

        // Checar também aliases
        Set<String> resolvedIds = aliasToPageIds.getOrDefault(supersededId.toLowerCase(), Collections.emptySet());
        for (String resolvedId : resolvedIds) {
            impacted.addAll(getBacklinks(resolvedId));
        }

        // Remove self-reference if present
        impacted.remove(supersededId);
        return impacted;
    }

    public int getTotalNodes() {
        return outlinks.size();
    }

    public int getTotalEdges() {
        return outlinks.values().stream().mapToInt(Set::size).sum();
    }

    public Map<String, Integer> getTopReferenced(int limit) {
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
    }
}
