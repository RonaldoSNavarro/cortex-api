package com.cortex.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MarkdownParser {
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    public static MemoryPage parse(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        return parseString(content);
    }
    
    public static MemoryPage parseString(String content) throws IOException {
        if (content == null) {
            throw new IllegalArgumentException("No content found");
        }
        
        int startOfYaml = content.indexOf("---");
        if (startOfYaml == -1) {
            throw new IllegalArgumentException("No YAML front-matter found");
        }
        
        int endOfYaml = content.indexOf("---", startOfYaml + 3);
        
        if (endOfYaml == -1) {
            throw new IllegalArgumentException("YAML front-matter is not closed properly");
        }
        
        String yamlBlock = content.substring(startOfYaml + 3, endOfYaml).trim();
        String markdownContent = content.substring(endOfYaml + 3).trim();
        
        MemoryPage page = yamlMapper.readValue(yamlBlock, MemoryPage.class);
        page.setContent(markdownContent);
        
        return page;
    }
}
