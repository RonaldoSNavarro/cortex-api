package com.cortex.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.databind.DeserializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilitário para parsing de documentos Markdown enriquecidos com YAML Front-Matter.
 * Extrai metadados estruturados e preserva o corpo textual íntegro da nota.
 */
public class MarkdownParser {
    private static final ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile(
            "^(?:\\uFEFF)?\\s*---\\R(.*?)(\\R---\\R|\\R---$)(.*)",
            Pattern.DOTALL
    );

    public static MemoryPage parse(Path filePath) throws IOException {
        String content = Files.readString(filePath);
        return parseString(content);
    }
    
    public static MemoryPage parseString(String content) throws IOException {
        if (content == null) {
            throw new IllegalArgumentException("No content found");
        }

        Matcher matcher = FRONTMATTER_PATTERN.matcher(content);
        if (!matcher.find()) {
            throw new IllegalArgumentException("No valid YAML front-matter found");
        }

        String yamlBlock = matcher.group(1).trim();
        String markdownContent = matcher.group(3).trim();

        MemoryPage page = yamlMapper.readValue(yamlBlock, MemoryPage.class);
        page.setContent(markdownContent);

        return page;
    }
}
