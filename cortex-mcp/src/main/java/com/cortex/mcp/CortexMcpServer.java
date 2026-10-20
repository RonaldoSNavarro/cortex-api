package com.cortex.mcp;

import com.cortex.core.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolRegistration;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransport;
import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * Stdio MCP server entrypoint for Cortex, sharing the unified {@link ObjectMapper},
 * schemas, and tool handlers from {@link CortexMcpTools}.
 */
@Configuration
@ComponentScan(basePackages = "com.cortex.core")
public class CortexMcpServer {

    public static ObjectMapper createObjectMapper() {
        return CortexMcpTools.createMcpObjectMapper();
    }

    public static List<SyncToolRegistration> buildToolRegistrations(ProjectRepository repo) {
        return CortexMcpTools.buildToolRegistrations(repo);
    }

    public static McpSyncServer createStdioServer(ProjectRepository repo) {
        StdioServerTransport transport = new StdioServerTransport(createObjectMapper());
        return CortexMcpTools.registerAll(
                McpServer.sync(transport).serverInfo("cortex", "1.0.0"),
                repo
        ).build();
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(CortexMcpServer.class);
        app.setBannerMode(Banner.Mode.OFF);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(Map.of("logging.level.root", "ERROR"));
        try (ConfigurableApplicationContext context = app.run(args)) {
            ProjectRepository repo = context.getBean(ProjectRepository.class);
            createStdioServer(repo);
            System.err.println("Cortex MCP Server rodando na versao 1.0.0");
            Thread.currentThread().join();
        } catch (Exception e) {
            e.printStackTrace(System.err);
        }
    }
}
