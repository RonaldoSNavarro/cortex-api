package com.cortex.core;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

import lombok.Data;

@Data
public class MemoryPage {
    private String id;
    private MemoryType type;
    private String project;
    private List<String> tags;
    private String status;
    private String supersedes;
    private String source;
    
    @JsonProperty("created_at")
    private String createdAt;
    
    @JsonProperty("updated_at")
    private String updatedAt;

    // Content of the markdown (not part of yaml front-matter)
    private String content;
}
