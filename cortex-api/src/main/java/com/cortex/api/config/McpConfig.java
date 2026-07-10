package com.cortex.api.config;

import com.cortex.core.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collections;

@Configuration
public class McpConfig {

    @Autowired
    private ProjectRepository repo;

    private McpSyncServer mcpServer;

    @Bean
    public ServletRegistrationBean<jakarta.servlet.http.HttpServlet> mcpServlet() {
        HttpServletSseServerTransport transport = new HttpServletSseServerTransport(new ObjectMapper(), "/mcp/sse");

        // (skipping schema strings for brevity - wait I can't skip them, I need to replace only what changed)

        String captureSchema = "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" },\n" +
            "    \"type\": { \"type\": \"string\", \"description\": \"Memory type (fact, decision, rule, etc)\" },\n" +
            "    \"content\": { \"type\": \"string\", \"description\": \"Conteudo bruto\" }\n" +
            "  },\n" +
            "  \"required\": [\"project\", \"type\", \"content\"]\n" +
            "}";

        String querySchema = "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" },\n" +
            "    \"terms\": { \"type\": \"string\", \"description\": \"Termos de busca\" }\n" +
            "  },\n" +
            "  \"required\": [\"project\", \"terms\"]\n" +
            "}";
            
        String lintSchema = "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" }\n" +
            "  },\n" +
            "  \"required\": [\"project\"]\n" +
            "}";

        String writePageSchema = "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" },\n" +
            "    \"type\": { \"type\": \"string\", \"description\": \"Memory type (fact, decision, rule, etc)\" },\n" +
            "    \"content\": { \"type\": \"string\", \"description\": \"Conteudo consolidado em Markdown\" },\n" +
            "    \"tags\": { \"type\": \"array\", \"items\": { \"type\": \"string\" }, \"description\": \"Tags para busca (opcional)\" },\n" +
            "    \"supersedes\": { \"type\": \"string\", \"description\": \"ID da página antiga a ser substituída (opcional)\" },\n" +
            "    \"consumed_raw_ids\": { \"type\": \"array\", \"items\": { \"type\": \"string\" }, \"description\": \"IDs dos arquivos raw consumidos nesta consolidação para exclusão (opcional)\" }\n" +
            "  },\n" +
            "  \"required\": [\"project\", \"type\", \"content\"]\n" +
            "}";

        String promoteSchema = "{\n" +
            "  \"type\": \"object\",\n" +
            "  \"properties\": {\n" +
            "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" },\n" +
            "    \"file\": { \"type\": \"string\", \"description\": \"Target file path (e.g. .agents/AGENTS.md)\" }\n" +
            "  },\n" +
            "  \"required\": [\"project\", \"file\"]\n" +
            "}";

        this.mcpServer = McpServer.sync(transport)
            .serverInfo("cortex", "1.0.0")
            .tool(
                new Tool("capture", "Captura informacoes brutas (raw) para a memoria", captureSchema),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        String type = (String) argsMap.get("type");
                        String content = (String) argsMap.get("content");
                        String id = repo.createRaw(project, type, content);
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Ingestão salva com ID " + id)), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("write_page", "Escreve uma página curada na wiki, consolidando contexto bruto", writePageSchema),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        String type = (String) argsMap.get("type");
                        String content = (String) argsMap.get("content");
                        String supersedes = (String) argsMap.get("supersedes");
                        
                        java.util.List<String> tags = null;
                        if (argsMap.containsKey("tags")) tags = (java.util.List<String>) argsMap.get("tags");
                        
                        java.util.List<String> consumedRawIds = null;
                        if (argsMap.containsKey("consumed_raw_ids")) consumedRawIds = (java.util.List<String>) argsMap.get("consumed_raw_ids");
                        
                        String id = repo.writePage(project, type, tags, supersedes, consumedRawIds, content);
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Página salva com sucesso. ID: " + id)), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro ao escrever página: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("lint", "Verifica a saúde da base do Cortex, detectando referências inválidas.", lintSchema),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        java.util.List<String> warnings = repo.lint(project);
                        if (warnings.isEmpty()) {
                            return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Nenhum problema encontrado. Status: OK")), false);
                        } else {
                            return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Problemas encontrados:\n" + String.join("\n", warnings))), false);
                        }
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro ao executar linter: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("promote_rules", "Extrai as regras ativas da base de memória do Cortex e injeta no contexto da IDE (AGENTS.md)", promoteSchema),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        String file = (String) argsMap.get("file");
                        repo.promoteRules(project, file);
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Regras promovidas com sucesso para " + file)), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro ao promover regras: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("query", "Busca informacoes na memoria do projeto", querySchema),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        String terms = (String) argsMap.get("terms");
                        java.util.List<com.cortex.core.MemoryPage> results = repo.search(project, terms);
                        if (results.isEmpty()) {
                            return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Nenhum resultado encontrado para: " + terms)), false);
                        }
                        StringBuilder sb = new StringBuilder();
                        sb.append("Resultados para '").append(terms).append("':\n\n");
                        for (com.cortex.core.MemoryPage page : results) {
                            sb.append("ID: ").append(page.getId()).append("\n");
                            sb.append("Tipo: ").append(page.getType()).append("\n");
                            sb.append("Conteúdo:\n").append(page.getContent()).append("\n");
                            sb.append("---\n");
                        }
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent(sb.toString())), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro na busca: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("bootstrap", "Inicializa um projeto completo no Cortex com estrutura de diretórios, Skill e registro no config", 
                    "{\n" +
                    "  \"type\": \"object\",\n" +
                    "  \"properties\": {\n" +
                    "    \"project\": { \"type\": \"string\", \"description\": \"Project ID (opcional)\" },\n" +
                    "    \"root_path\": { \"type\": \"string\", \"description\": \"Caminho absoluto do repositório de código\" }\n" +
                    "  },\n" +
                    "  \"required\": [\"root_path\"]\n" +
                    "}"),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        String rootPath = (String) argsMap.get("root_path");
                        String id = repo.bootstrap(project, rootPath);
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Projeto '" + id + "' inicializado com sucesso via bootstrap.")), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro no bootstrap: " + e.getMessage())), true);
                    }
                }
            )
            .tool(
                new Tool("handoff", "Gera um relatório de handoff consolidado do projeto para transferência de contexto entre agentes",
                    "{\n" +
                    "  \"type\": \"object\",\n" +
                    "  \"properties\": {\n" +
                    "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" },\n" +
                    "    \"target_agent\": { \"type\": \"string\", \"description\": \"Nome do agente alvo (ex: claude, antigravity)\" }\n" +
                    "  },\n" +
                    "  \"required\": [\"project\"]\n" +
                    "}"),
                (argsMap) -> {
                    try {
                        String project = (String) argsMap.get("project");
                        String targetAgent = (String) argsMap.get("target_agent");
                        String summary = repo.handoff(project, targetAgent);
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent(summary)), false);
                    } catch (Exception e) {
                        return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro no handoff: " + e.getMessage())), true);
                    }
                }
            )
            .build();

        jakarta.servlet.http.HttpServlet wrapperServlet = new jakarta.servlet.http.HttpServlet() {
            @Override
            protected void doGet(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse resp) throws jakarta.servlet.ServletException, java.io.IOException {
                System.out.println(">>> [GET] URI: " + req.getRequestURI() + " | Query: " + req.getQueryString());
                transport.service(req, resp);
            }
            @Override
            protected void doPost(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse resp) throws jakarta.servlet.ServletException, java.io.IOException {
                System.out.println(">>> [POST] URI: " + req.getRequestURI() + " | Query: " + req.getQueryString());
                jakarta.servlet.http.HttpServletRequestWrapper wrapper = new jakarta.servlet.http.HttpServletRequestWrapper(req) {
                    @Override
                    public String getRequestURI() {
                        return "/mcp/sse";
                    }
                    @Override
                    public String getPathInfo() {
                        return "/mcp/sse";
                    }
                };
                transport.service(wrapper, resp);
            }
        };

        ServletRegistrationBean<jakarta.servlet.http.HttpServlet> registrationBean = new ServletRegistrationBean<>(wrapperServlet, "/mcp/*");
        registrationBean.setName("mcpServlet");
        registrationBean.setLoadOnStartup(1);
        registrationBean.setAsyncSupported(true);
        return registrationBean;
    }
}
