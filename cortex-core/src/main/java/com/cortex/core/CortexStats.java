package com.cortex.core;

import java.util.LinkedHashMap;
import java.util.Map;

public class CortexStats {
    private final String projectId;
    private final int totalMemories;
    private final int activeMemories;
    private final int supersededMemories;
    private final long pendingRawCount;
    private final Map<String, Integer> memoriesByType;
    private final Map<String, Integer> topTags;
    private final int graphNodes;
    private final int graphEdges;
    private final Map<String, Integer> topReferencedPages;

    public CortexStats(String projectId, int totalMemories, int activeMemories, int supersededMemories,
                       long pendingRawCount, Map<String, Integer> memoriesByType, Map<String, Integer> topTags,
                       int graphNodes, int graphEdges, Map<String, Integer> topReferencedPages) {
        this.projectId = projectId;
        this.totalMemories = totalMemories;
        this.activeMemories = activeMemories;
        this.supersededMemories = supersededMemories;
        this.pendingRawCount = pendingRawCount;
        this.memoriesByType = memoriesByType != null ? memoriesByType : new LinkedHashMap<>();
        this.topTags = topTags != null ? topTags : new LinkedHashMap<>();
        this.graphNodes = graphNodes;
        this.graphEdges = graphEdges;
        this.topReferencedPages = topReferencedPages != null ? topReferencedPages : new LinkedHashMap<>();
    }

    public String getProjectId() {
        return projectId;
    }

    public int getTotalMemories() {
        return totalMemories;
    }

    public int getActiveMemories() {
        return activeMemories;
    }

    public int getSupersededMemories() {
        return supersededMemories;
    }

    public long getPendingRawCount() {
        return pendingRawCount;
    }

    public Map<String, Integer> getMemoriesByType() {
        return memoriesByType;
    }

    public Map<String, Integer> getTopTags() {
        return topTags;
    }

    public int getGraphNodes() {
        return graphNodes;
    }

    public int getGraphEdges() {
        return graphEdges;
    }

    public Map<String, Integer> getTopReferencedPages() {
        return topReferencedPages;
    }

    public double getConsolidationRate() {
        long totalRawIngested = totalMemories + pendingRawCount;
        if (totalRawIngested == 0) return 100.0;
        return (double) totalMemories / totalRawIngested * 100.0;
    }

    public String toMarkdownSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("# 📊 Métricas e Saúde do Cérebro — Projeto: `").append(projectId).append("`\n\n");
        sb.append("| Métrica | Valor |\n");
        sb.append("|---|---|\n");
        sb.append("| Total de Páginas Curadas | ").append(totalMemories).append(" |\n");
        sb.append("| Páginas Ativas | ").append(activeMemories).append(" |\n");
        sb.append("| Páginas Substituídas (Superseded) | ").append(supersededMemories).append(" |\n");
        sb.append("| Observações Pendentes em `raw/` | ").append(pendingRawCount).append(" |\n");
        sb.append(String.format("| Taxa de Consolidação | %.1f%% |\n", getConsolidationRate()));
        sb.append("| Nós do Grafo de Conhecimento | ").append(graphNodes).append(" |\n");
        sb.append("| Conexões (Wikilinks / Backlinks) | ").append(graphEdges).append(" |\n\n");

        sb.append("### 🏷️ Distribuição por Tipo\n\n");
        for (Map.Entry<String, Integer> entry : memoriesByType.entrySet()) {
            sb.append("- **").append(entry.getKey()).append("**: ").append(entry.getValue()).append("\n");
        }

        if (!topTags.isEmpty()) {
            sb.append("\n### 📌 Top Tags\n\n");
            for (Map.Entry<String, Integer> entry : topTags.entrySet()) {
                sb.append("- `").append(entry.getKey()).append("`: ").append(entry.getValue()).append(" páginas\n");
            }
        }

        if (!topReferencedPages.isEmpty()) {
            sb.append("\n### 🔗 Páginas Mais Referenciadas (Backlinks)\n\n");
            for (Map.Entry<String, Integer> entry : topReferencedPages.entrySet()) {
                sb.append("- `[[").append(entry.getKey()).append("]]`: ").append(entry.getValue()).append(" referências\n");
            }
        }

        return sb.toString();
    }
}
