package com.cortex.core;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Índice léxico invertido em memória com ranqueamento BM25 e filtros facetados (type:, tags:, status:).
 * Garante consistência e isolamento atômico em operações concorrentes através de ReentrantReadWriteLock.
 */
public class InMemoryLexicalIndex {

    private static final double K1 = 1.2;
    private static final double B = 0.75;

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "a", "o", "as", "os", "um", "uma", "uns", "umas", "de", "do", "da", "dos", "das",
            "em", "no", "na", "nos", "nas", "por", "pelo", "pela", "pelos", "pelas", "para",
            "com", "sem", "sob", "sobre", "e", "ou", "que", "se", "como", "mas", "mais",
            "the", "an", "and", "or", "but", "in", "on", "at", "to", "for", "with", "by", "of"
    ));

    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final Map<String, MemoryPage> pagesById = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> invertedIndex = new ConcurrentHashMap<>();
    private final Map<String, Map<String, Integer>> docTermFrequencies = new ConcurrentHashMap<>();
    private final Map<String, Integer> docLengths = new ConcurrentHashMap<>();

    public void indexPage(MemoryPage page) {
        if (page == null || page.getId() == null) return;
        rwLock.writeLock().lock();
        try {
            removePageInternal(page.getId());

            pagesById.put(page.getId(), page);
            Map<String, Integer> termFreqs = new HashMap<>();

            // 1. Index ID (High boost)
            List<String> idTokens = tokenize(page.getId());
            for (String t : idTokens) {
                termFreqs.put(t, termFreqs.getOrDefault(t, 0) + 3);
            }

            // 2. Index Tags (High boost)
            if (page.getTags() != null) {
                for (String tag : page.getTags()) {
                    for (String t : tokenize(tag)) {
                        termFreqs.put(t, termFreqs.getOrDefault(t, 0) + 2);
                    }
                }
            }

            // 3. Index Type & Status
            if (page.getType() != null) {
                termFreqs.put(page.getType().getValue().toLowerCase(), termFreqs.getOrDefault(page.getType().getValue().toLowerCase(), 0) + 1);
            }
            if (page.getStatus() != null) {
                termFreqs.put(page.getStatus().toLowerCase(), termFreqs.getOrDefault(page.getStatus().toLowerCase(), 0) + 1);
            }

            // 4. Index Content
            List<String> contentTokens = tokenize(page.getContent());
            for (String t : contentTokens) {
                termFreqs.put(t, termFreqs.getOrDefault(t, 0) + 1);
            }

            int totalLength = contentTokens.size() + idTokens.size() * 3 + (page.getTags() != null ? page.getTags().size() * 2 : 0);
            docLengths.put(page.getId(), Math.max(1, totalLength));
            docTermFrequencies.put(page.getId(), termFreqs);

            for (String term : termFreqs.keySet()) {
                invertedIndex.computeIfAbsent(term, k -> ConcurrentHashMap.newKeySet()).add(page.getId());
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
        pagesById.remove(pageId);
        docLengths.remove(pageId);
        Map<String, Integer> freqs = docTermFrequencies.remove(pageId);
        if (freqs != null) {
            for (String term : freqs.keySet()) {
                Set<String> docSet = invertedIndex.get(term);
                if (docSet != null) {
                    docSet.remove(pageId);
                    if (docSet.isEmpty()) {
                        invertedIndex.remove(term);
                    }
                }
            }
        }
    }

    public void clear() {
        rwLock.writeLock().lock();
        try {
            pagesById.clear();
            invertedIndex.clear();
            docTermFrequencies.clear();
            docLengths.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public Map<String, MemoryPage> getAllPages() {
        rwLock.readLock().lock();
        try {
            return new HashMap<>(pagesById);
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public List<com.cortex.core.SearchResult> search(String rawQuery, int limit) {
        if (rawQuery == null || rawQuery.trim().isEmpty()) {
            return Collections.emptyList();
        }

        rwLock.readLock().lock();
        try {
            ParsedQuery query = parseQuery(rawQuery);
            List<String> searchTerms = query.terms;

            int totalDocs = pagesById.size();
            if (totalDocs == 0) return Collections.emptyList();

            double avgDl = docLengths.values().stream().mapToInt(Integer::intValue).average().orElse(100.0);

            Map<String, Double> scores = new HashMap<>();
            Map<String, List<String>> matchedTermsPerDoc = new HashMap<>();

            // Filter candidate documents
            Collection<MemoryPage> candidates = pagesById.values().stream()
                    .filter(page -> matchesFilters(page, query))
                    .toList();

            if (searchTerms.isEmpty()) {
                return candidates.stream()
                        .map(p -> new com.cortex.core.SearchResult(p, computeRecencyBoost(p), generateSnippet(p.getContent(), List.of()), List.of()))
                        .sorted(Comparator.comparingDouble(com.cortex.core.SearchResult::getScore).reversed())
                        .limit(limit > 0 ? limit : 20)
                        .toList();
            }

            for (String term : searchTerms) {
                Set<String> matchingDocIds = invertedIndex.getOrDefault(term, Collections.emptySet());
                int docFreq = matchingDocIds.size();
                double idf = Math.log(1.0 + (totalDocs - docFreq + 0.5) / (docFreq + 0.5));

                for (MemoryPage page : candidates) {
                    String docId = page.getId();
                    Map<String, Integer> freqs = docTermFrequencies.get(docId);
                    if (freqs != null && freqs.containsKey(term)) {
                        int tf = freqs.get(term);
                        int dl = docLengths.getOrDefault(docId, 1);
                        double bm25 = idf * ((tf * (K1 + 1.0)) / (tf + K1 * (1.0 - B + B * (dl / avgDl))));

                        scores.put(docId, scores.getOrDefault(docId, 0.0) + bm25);
                        matchedTermsPerDoc.computeIfAbsent(docId, k -> new ArrayList<>()).add(term);
                    }
                }
            }

            List<com.cortex.core.SearchResult> results = new ArrayList<>();
            for (Map.Entry<String, Double> entry : scores.entrySet()) {
                String docId = entry.getKey();
                MemoryPage page = pagesById.get(docId);
                if (page == null) continue;

                double finalScore = entry.getValue();

                // Multi-term coverage boost
                List<String> matched = matchedTermsPerDoc.getOrDefault(docId, List.of());
                long distinctMatched = matched.stream().distinct().count();
                double coverageBoost = 1.0 + 0.5 * ((double) distinctMatched / searchTerms.size());
                finalScore *= coverageBoost;

                // Status weighting
                if ("superseded".equalsIgnoreCase(page.getStatus())) {
                    finalScore *= 0.3;
                }

                // Type boost
                if (page.getType() == MemoryType.RULE || page.getType() == MemoryType.GOTCHA) {
                    finalScore *= 1.3;
                }

                // Recency boost
                finalScore += computeRecencyBoost(page);

                String snippet = generateSnippet(page.getContent(), matched);

                results.add(new com.cortex.core.SearchResult(page, finalScore, snippet, matched));
            }

            results.sort(Comparator.comparingDouble(com.cortex.core.SearchResult::getScore).reversed());
            return results.stream().limit(limit > 0 ? limit : 20).toList();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    private boolean matchesFilters(MemoryPage page, ParsedQuery query) {
        if (query.typeFilter != null && (page.getType() == null || !page.getType().getValue().equalsIgnoreCase(query.typeFilter))) {
            return false;
        }
        if (query.statusFilter != null && (page.getStatus() == null || !page.getStatus().equalsIgnoreCase(query.statusFilter))) {
            return false;
        }
        if (query.tagFilter != null) {
            boolean hasTag = page.getTags() != null && page.getTags().stream().anyMatch(t -> t.equalsIgnoreCase(query.tagFilter));
            if (!hasTag) return false;
        }
        return true;
    }

    private double computeRecencyBoost(MemoryPage page) {
        if (page.getCreatedAt() == null) return 0.0;
        try {
            Instant created = Instant.parse(page.getCreatedAt());
            long daysOld = ChronoUnit.DAYS.between(created, Instant.now());
            if (daysOld <= 2) return 0.5;
            if (daysOld <= 7) return 0.3;
            if (daysOld <= 30) return 0.1;
        } catch (Exception ignored) {
        }
        return 0.0;
    }

    private String generateSnippet(String content, List<String> terms) {
        if (content == null || content.trim().isEmpty()) return "";
        String clean = content.replace("\r\n", "\n").trim();
        if (terms.isEmpty()) {
            return clean.length() > 180 ? clean.substring(0, 180) + "..." : clean;
        }

        String[] lines = clean.split("\n");
        String bestLine = lines[0];
        int maxMatches = -1;

        for (String line : lines) {
            String lowerLine = line.toLowerCase();
            int matches = 0;
            for (String term : terms) {
                if (lowerLine.contains(term)) matches++;
            }
            if (matches > maxMatches) {
                maxMatches = matches;
                bestLine = line;
            }
        }

        String snippet = bestLine.trim();
        if (snippet.length() > 200) {
            snippet = snippet.substring(0, 200) + "...";
        }
        return snippet;
    }

    public static List<String> tokenize(String text) {
        if (text == null || text.trim().isEmpty()) return Collections.emptyList();
        List<String> tokens = new ArrayList<>();
        
        Matcher m = Pattern.compile("[a-zA-Z0-9_\\-áéíóúãõâêîôûçÁÉÍÓÚÃÕÂÊÎÔÛÇ]{2,}").matcher(text);
        while (m.find()) {
            String rawToken = m.group();
            String lower = rawToken.toLowerCase();
            if (!STOP_WORDS.contains(lower)) {
                tokens.add(lower);
            }

            String[] camelParts = rawToken.split("(?<!(^|[A-Z]))(?=[A-Z])|(?<!^)(?=[A-Z][a-z])");
            if (camelParts.length > 1) {
                for (String part : camelParts) {
                    String partLower = part.toLowerCase();
                    if (partLower.length() >= 2 && !STOP_WORDS.contains(partLower)) {
                        tokens.add(partLower);
                    }
                }
            }

            if (rawToken.contains("-") || rawToken.contains("_")) {
                String[] subParts = rawToken.split("[\\-_]");
                for (String part : subParts) {
                    String partLower = part.toLowerCase();
                    if (partLower.length() >= 2 && !STOP_WORDS.contains(partLower)) {
                        tokens.add(partLower);
                    }
                }
            }
        }
        return tokens;
    }

    private ParsedQuery parseQuery(String raw) {
        ParsedQuery q = new ParsedQuery();
        String[] parts = raw.trim().split("\\s+");
        List<String> remaining = new ArrayList<>();

        for (String part : parts) {
            if (part.startsWith("type:")) {
                q.typeFilter = part.substring(5).trim();
            } else if (part.startsWith("tag:") || part.startsWith("tags:")) {
                int idx = part.indexOf(':');
                q.tagFilter = part.substring(idx + 1).trim();
            } else if (part.startsWith("status:")) {
                q.statusFilter = part.substring(7).trim();
            } else {
                remaining.addAll(tokenize(part));
            }
        }
        q.terms = remaining;
        return q;
    }

    private static class ParsedQuery {
        String typeFilter;
        String tagFilter;
        String statusFilter;
        List<String> terms = new ArrayList<>();
    }

    public static class SearchResult extends com.cortex.core.SearchResult {
        public SearchResult(MemoryPage page, double score, String snippet, List<String> matchedTerms) {
            super(page, score, snippet, matchedTerms);
        }
    }
}
