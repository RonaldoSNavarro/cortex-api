package com.cortex.core;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@JsonIgnoreProperties(ignoreUnknown = true)
public class MemoryPage {
    private String id;
    private MemoryType type;
    private String project;
    private List<String> tags = new ArrayList<>();
    private String status = "active";
    private String supersedes;
    private String source;
    
    @JsonProperty("created_at")
    private String createdAt;
    
    @JsonProperty("updated_at")
    private String updatedAt;

    // Content of the markdown (not part of yaml front-matter)
    private String content = "";

    // In-memory graph references
    private List<String> links = new ArrayList<>();
    private List<String> backlinks = new ArrayList<>();

    // Search ranking score (transient)
    private double score;

    public MemoryPage() {
    }

    public MemoryPage(String id, MemoryType type, String project, List<String> tags, String status, String supersedes, String source, String createdAt, String updatedAt, String content) {
        this.id = id;
        this.type = type;
        this.project = project;
        if (tags != null) this.tags = new ArrayList<>(tags);
        this.status = status != null ? status : "active";
        this.supersedes = supersedes;
        this.source = source;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.content = content != null ? content : "";
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public MemoryType getType() {
        return type;
    }

    public void setType(MemoryType type) {
        this.type = type;
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public List<String> getTags() {
        if (tags == null) tags = new ArrayList<>();
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSupersedes() {
        return supersedes;
    }

    public void setSupersedes(String supersedes) {
        this.supersedes = supersedes;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(String updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getContent() {
        return content != null ? content : "";
    }

    public void setContent(String content) {
        this.content = content != null ? content : "";
    }

    public List<String> getLinks() {
        if (links == null) links = new ArrayList<>();
        return links;
    }

    public void setLinks(List<String> links) {
        this.links = links != null ? new ArrayList<>(links) : new ArrayList<>();
    }

    public List<String> getBacklinks() {
        if (backlinks == null) backlinks = new ArrayList<>();
        return backlinks;
    }

    public void setBacklinks(List<String> backlinks) {
        this.backlinks = backlinks != null ? new ArrayList<>(backlinks) : new ArrayList<>();
    }

    public double getScore() {
        return score;
    }

    public void setScore(double score) {
        this.score = score;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MemoryPage that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "MemoryPage{" +
                "id='" + id + '\'' +
                ", type=" + type +
                ", project='" + project + '\'' +
                ", status='" + status + '\'' +
                ", tags=" + tags +
                ", supersedes='" + supersedes + '\'' +
                '}';
    }
}
