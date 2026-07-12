package com.cortex.mcp;

import com.cortex.core.ProjectRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Autowired;


@SpringBootApplication(scanBasePackages = "com.cortex")
public class CortexMcpServer implements CommandLineRunner {

    @Autowired
    private ProjectRepository repo;

    
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(CortexMcpServer.class);
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        app.setDefaultProperties(java.util.Collections.singletonMap("logging.level.root", "ERROR"));
        app.run(args);
    }

    @Override
    public void run(String... args) throws Exception {

        try {
            StdioServerTransport transport = new StdioServerTransport(new ObjectMapper());
            
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

            String consolidateSchema = "{\n" +
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

            McpSyncServer server = McpServer.sync(transport)
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
                            if (argsMap.containsKey("tags")) {
                                tags = (java.util.List<String>) argsMap.get("tags");
                            }
                            
                            java.util.List<String> consumedRawIds = null;
                            if (argsMap.containsKey("consumed_raw_ids")) {
                                consumedRawIds = (java.util.List<String>) argsMap.get("consumed_raw_ids");
                            }

                            
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
                    new Tool("consolidate", "Retorna a lista de observações brutas ainda não consolidadas para revisão. A consolidação deve ser acionada automaticamente ao fim de cada sessão e os resultados consolidados devem ser versionados via Git.", consolidateSchema),
                    (argsMap) -> {
                        try {
                            String project = (String) argsMap.get("project");
                            java.util.List<com.cortex.core.MemoryPage> raws = repo.getPendingRaws(project);
                            
                            if (raws.isEmpty()) {
                                return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Nenhum arquivo bruto pendente de consolidação.")), false);
                            }
                            
                            StringBuilder sb = new StringBuilder();
                            sb.append("Arquivos brutos pendentes (").append(raws.size()).append("):\n\n");
                            for (com.cortex.core.MemoryPage page : raws) {
                                sb.append("ID: ").append(page.getId()).append("\n");
                                sb.append("Tipo: ").append(page.getType()).append("\n");
                                sb.append("Data: ").append(page.getCreatedAt()).append("\n");
                                sb.append("Conteúdo:\n").append(page.getContent()).append("\n");
                                sb.append("---\n");
                            }
                            
                            return new CallToolResult(Collections.singletonList(new McpSchema.TextContent(sb.toString())), false);
                        } catch (Exception e) {
                            return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Erro na consolidação: " + e.getMessage())), true);
                        }
                    }
                )

                .tool(
                    new Tool("bootstrap", "Inicializa um projeto completo no Cortex com estrutura de diretórios, Skill e registro no config", 
                        "{\n" +
                        "  \"type\": \"object\",\n" +
                        "  \"properties\": {\n" +
                        "    \"project\": { \"type\": \"string\", \"description\": \"Project ID\" },\n" +
                        "    \"root_path\": { \"type\": \"string\", \"description\": \"Caminho absoluto do repositório de código\" }\n" +
                        "  },\n" +
                        "  \"required\": [\"project\", \"root_path\"]\n" +
                        "}"),
                    (argsMap) -> {
                        try {
                            String project = (String) argsMap.get("project");
                            String rootPath = (String) argsMap.get("root_path");
                            
                            String id = repo.bootstrap(project, rootPath);
                            
                            return new CallToolResult(Collections.singletonList(new McpSchema.TextContent("Projeto '" + id + "' inicializado com sucesso via bootstrap. Estrutura criada em ~/.cortex/projects/" + id)), false);
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
                
            System.err.println("Cortex MCP Server rodando na versao 1.0.0");
            
            // Previne a finalizacao da JVM enquanto o transporte estiver ativo
            Thread.currentThread().join();

        } catch (Exception e) {
            e.printStackTrace(System.err);
            
        }
    }
}
