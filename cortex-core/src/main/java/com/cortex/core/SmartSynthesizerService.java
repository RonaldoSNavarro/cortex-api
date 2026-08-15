package com.cortex.core;

import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Clustering and smart synthesis service for raw memories.
 * Groups disjoint raw observations into cohesive topic clusters to guide LLM consolidation.
 */
@Service
public class SmartSynthesizerService {

    public static class MemoryCluster {
        private final String topic;
        private final List<MemoryPage> notes;
        private final Set<String> commonTags;

        public MemoryCluster(String topic, List<MemoryPage> notes, Set<String> commonTags) {
            this.topic = topic;
            this.notes = notes != null ? notes : new ArrayList<>();
            this.commonTags = commonTags != null ? commonTags : new HashSet<>();
        }

        public String getTopic() {
            return topic;
        }

        public List<MemoryPage> getNotes() {
            return notes;
        }

        public Set<String> getCommonTags() {
            return commonTags;
        }
    }

    public List<MemoryCluster> clusterPendingRaws(List<MemoryPage> pendingRaws) {
        if (pendingRaws == null || pendingRaws.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, List<MemoryPage>> tagToPages = new HashMap<>();
        List<MemoryPage> untaggedPages = new ArrayList<>();

        for (MemoryPage page : pendingRaws) {
            if (page.getTags() != null && !page.getTags().isEmpty()) {
                String primaryTag = page.getTags().get(0).toLowerCase();
                tagToPages.computeIfAbsent(primaryTag, k -> new ArrayList<>()).add(page);
            } else {
                untaggedPages.add(page);
            }
        }

        List<MemoryCluster> clusters = new ArrayList<>();

        // 1. Clusters por tags principais
        for (Map.Entry<String, List<MemoryPage>> entry : tagToPages.entrySet()) {
            Set<String> allTags = new HashSet<>();
            entry.getValue().forEach(p -> {
                if (p.getTags() != null) allTags.addAll(p.getTags());
            });
            clusters.add(new MemoryCluster(entry.getKey(), entry.getValue(), allTags));
        }

        // 2. Tenta agrupar não taggeadas por termos do conteúdo
        if (!untaggedPages.isEmpty()) {
            Map<String, List<MemoryPage>> termClusters = new HashMap<>();
            for (MemoryPage page : untaggedPages) {
                List<String> tokens = InMemoryLexicalIndex.tokenize(page.getContent());
                String dominantTerm = tokens.isEmpty() ? "geral" : tokens.get(0);
                termClusters.computeIfAbsent(dominantTerm, k -> new ArrayList<>()).add(page);
            }

            for (Map.Entry<String, List<MemoryPage>> entry : termClusters.entrySet()) {
                clusters.add(new MemoryCluster("topico-" + entry.getKey(), entry.getValue(), Set.of(entry.getKey())));
            }
        }

        // Ordenar clusters por quantidade de notas decrescente
        clusters.sort(Comparator.comparingInt((MemoryCluster c) -> c.getNotes().size()).reversed());
        return clusters;
    }

    public List<MemoryPage> filterByTopic(List<MemoryPage> pendingRaws, String topic) {
        if (topic == null || topic.trim().isEmpty() || pendingRaws == null) {
            return pendingRaws;
        }
        String cleanTopic = topic.toLowerCase().trim();
        return pendingRaws.stream().filter(p -> {
            if (p.getTags() != null && p.getTags().stream().anyMatch(t -> t.toLowerCase().contains(cleanTopic))) {
                return true;
            }
            if (p.getContent() != null && p.getContent().toLowerCase().contains(cleanTopic)) {
                return true;
            }
            return false;
        }).collect(Collectors.toList());
    }

    public String formatConsolidationReport(List<MemoryCluster> clusters, int totalRawCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 🧠 Relatório de Síntese Inteligente (Cortex Consolidate)\n\n");
        sb.append(String.format("Existem **%d** notas brutas pendentes agrupadas em **%d** clusters temáticos:\n\n",
                totalRawCount, clusters.size()));

        for (int i = 0; i < clusters.size(); i++) {
            MemoryCluster cluster = clusters.get(i);
            sb.append(String.format("### Cluster %d: `%s` (%d notas)\n", i + 1, cluster.getTopic(), cluster.getNotes().size()));
            if (!cluster.getCommonTags().isEmpty()) {
                sb.append("**Tags sugeridas:** `").append(String.join("`, `", cluster.getCommonTags())).append("`\n\n");
            }
            sb.append("**Notas a sintetizar:**\n");
            for (MemoryPage page : cluster.getNotes()) {
                String id = page.getId();
                String type = page.getType() != null ? page.getType().getValue() : "note";
                String snippet = page.getContent() != null
                        ? (page.getContent().length() > 100 ? page.getContent().substring(0, 100).replace("\n", " ") + "..." : page.getContent().replace("\n", " "))
                        : "(vazio)";
                sb.append(String.format("- `[%s]` *(%s)*: %s\n", id, type, snippet));
            }
            sb.append("\n**IDs para consumir:** `").append(
                    cluster.getNotes().stream().map(MemoryPage::getId).collect(Collectors.joining(", "))
            ).append("`\n\n");
            sb.append("---\n\n");
        }

        sb.append("💡 **Instrução para o Agente**: Para consolidar um cluster, invoque `write_page` com o conteúdo curado em Markdown, definindo `consumed_raw_ids` com a lista de IDs do cluster correspondente.\n");
        return sb.toString();
    }
}
