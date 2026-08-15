package com.cortex.core;

import java.util.ArrayList;
import java.util.List;

public class SearchResult {
    private final MemoryPage page;
    private final double score;
    private final String snippet;
    private final List<String> matchedTerms;

    public SearchResult(MemoryPage page, double score, String snippet, List<String> matchedTerms) {
        this.page = page;
        this.score = score;
        this.snippet = snippet != null ? snippet : "";
        this.matchedTerms = matchedTerms != null ? matchedTerms : new ArrayList<>();
    }

    public MemoryPage getPage() {
        return page;
    }

    public double getScore() {
        return score;
    }

    public String getSnippet() {
        return snippet;
    }

    public List<String> getMatchedTerms() {
        return matchedTerms;
    }

    public String toFormattedSummary() {
        String id = page.getId() != null ? page.getId() : "sem-id";
        String type = page.getType() != null ? page.getType().getValue() : "note";
        String tagsStr = page.getTags() != null && !page.getTags().isEmpty() ? String.join(", ", page.getTags()) : "sem tags";
        return String.format("- **[%s]** (score: %.2f | tipo: %s | tags: %s)\n  > %s", id, score, type, tagsStr, snippet);
    }
}
