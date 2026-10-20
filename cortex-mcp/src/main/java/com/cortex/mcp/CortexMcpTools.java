package com.cortex.mcp;

import com.cortex.core.CortexStats;
import com.cortex.core.ProjectRepository;
import com.cortex.core.SearchResult;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures.SyncToolRegistration;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * Factory and registry that unifies the MCP Jackson {@link ObjectMapper}, JSON schemas,
 * and tool handlers between the Stdio server ({@link CortexMcpServer}) and the HTTP/SSE
 * server ({@code McpConfig} in {@code cortex-api}).
 */
public final class CortexMcpTools {

    /**
     * MixIn that suppresses the duplicate {@code "type"} discriminator property emitted by
     * {@link McpSchema.Content}'s {@code @JsonTypeInfo(include = As.PROPERTY)} when serializing
     * {@link McpSchema.TextContent} (which already declares {@code @JsonProperty("type")}).
     */
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXISTING_PROPERTY,
            property = "type",
            visible = true
    )
    public interface McpContentMixIn {
    }

    public static final String CAPTURE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": { "type": "string", "description": "Project ID" },
                "type": { "type": "string", "description": "Memory type (fact, decision, rule, etc)" },
                "content": { "type": "string", "description": "Conteudo bruto" }
              },
              "required": ["project", "type", "content"]
            }""";

    public static final String WRITE_PAGE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": { "type": "string", "description": "Project ID" },
                "type": { "type": "string", "description": "Memory type (fact, decision, rule, etc)" },
                "content": { "type": "string", "description": "Conteudo consolidado em Markdown (suporta [[wikilinks]])" },
                "tags": { "type": "array", "items": { "type": "string" }, "description": "Tags para busca (opcional)" },
                "supersedes": { "type": "string", "description": "ID da página antiga a ser substituída (opcional)" },
                "consumed_raw_ids": { "type": "array", "items": { "type": "string" }, "description": "IDs dos arquivos raw consumidos nesta consolidação para exclusão (opcional)" }
              },
              "required": ["project", "type", "content"]
            }""";

    public static final String LINT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": { "type": "string", "description": "Project ID" }
              },
              "required": ["project"]
            }""";

    public static final String PROMOTE_RULES_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": { "type": "string", "description": "Project ID" },
                "file": { "type": "string", "description": "Target file path (e.g. .agents/AGENTS.md)" }
              },
              "required": ["project", "file"]
            }""";

    public static final String QUERY_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": { "type": "string", "description": "Project ID" },
                "terms": { "type": "string", "description": "Termos de busca (suporta filtros facetados: type:rule tags:mcp status:active)" },
                "limit": { "type": "integer", "description": "Limite maximo de resultados (padrao 10)" }
              },
              "required": ["project", "terms"]
            }""";

    public static final String CONSOLIDATE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": { "type": "string", "description": "Project ID" },
                "topic": { "type": "string", "description": "Topico ou tag opcional para consolidar apenas um cluster especifico" }
              },
              "required": ["project"]
            }""";

    public static final String STATS_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": { "type": "string", "description": "Project ID" }
              },
              "required": ["project"]
            }""";

    public static final String BOOTSTRAP_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": { "type": "string", "description": "Project ID (opcional)" },
                "root_path": { "type": "string", "description": "Caminho absoluto do repositório de código" }
              },
              "required": ["root_path"]
            }""";

    public static final String HANDOFF_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "project": { "type": "string", "description": "Project ID" },
                "target_agent": { "type": "string", "description": "Nome do agente alvo (ex: claude, antigravity)" }
              },
              "required": ["project"]
            }""";

    private CortexMcpTools() {
    }

    /**
     * Creates a Jackson {@link ObjectMapper} configured for MCP messages:
     * <ul>
     *   <li>{@code FAIL_ON_UNKNOWN_PROPERTIES = false} (AGENTS.md Rule #1)</li>
     *   <li>Custom {@link McpSchema.JSONRPCMessage} deserializer</li>
     *   <li>MixIn on {@link McpSchema.Content} and {@link McpSchema.TextContent} to avoid duplicate {@code "type"} keys</li>
     * </ul>
     */
    public static ObjectMapper createMcpObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        configureMcpObjectMapper(mapper);

        final ObjectMapper internalMapper = new ObjectMapper();
        configureMcpObjectMapper(internalMapper);

        SimpleModule mcpModule = new SimpleModule("McpJsonRpc");
        mcpModule.addDeserializer(
                McpSchema.JSONRPCMessage.class,
                new StdDeserializer<McpSchema.JSONRPCMessage>(McpSchema.JSONRPCMessage.class) {
                    @Override
                    public McpSchema.JSONRPCMessage deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
                        JsonNode node = p.getCodec().readTree(p);
                        return McpSchema.deserializeJsonRpcMessage(internalMapper, node.toString());
                    }
                }
        );
        mapper.registerModule(mcpModule);
        return mapper;
    }

    private static void configureMcpObjectMapper(ObjectMapper mapper) {
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        mapper.addMixIn(McpSchema.Content.class, McpContentMixIn.class);
        mapper.addMixIn(McpSchema.TextContent.class, McpContentMixIn.class);
        mapper.addMixIn(McpSchema.ImageContent.class, McpContentMixIn.class);
        mapper.addMixIn(McpSchema.EmbeddedResource.class, McpContentMixIn.class);
    }

    /**
     * Builds the canonical list of 9 MCP tool registrations backed by {@link ProjectRepository}.
     */
    public static List<SyncToolRegistration> buildToolRegistrations(ProjectRepository repo) {
        return List.of(
                new SyncToolRegistration(
                        new Tool("capture", "Captura informacoes brutas (raw) para a memoria", CAPTURE_SCHEMA),
                        argsMap -> {
                            try {
                                String project = (String) argsMap.get("project");
                                String type = (String) argsMap.get("type");
                                String content = (String) argsMap.get("content");
                                String id = repo.createRaw(project, type, content);
                                return new CallToolResult(List.of(new McpSchema.TextContent("Ingestão salva com ID " + id)), false);
                            } catch (Exception e) {
                                return new CallToolResult(List.of(new McpSchema.TextContent("Erro: " + e.getMessage())), true);
                            }
                        }
                ),
                new SyncToolRegistration(
                        new Tool("write_page", "Escreve uma página curada na wiki, consolidando contexto bruto com suporte a [[wikilinks]]", WRITE_PAGE_SCHEMA),
                        argsMap -> {
                            try {
                                String project = (String) argsMap.get("project");
                                String type = (String) argsMap.get("type");
                                String content = (String) argsMap.get("content");
                                String supersedes = (String) argsMap.get("supersedes");

                                @SuppressWarnings("unchecked")
                                List<String> tags = argsMap.get("tags") instanceof List<?> list ? (List<String>) list : null;

                                @SuppressWarnings("unchecked")
                                List<String> consumedRawIds = argsMap.get("consumed_raw_ids") instanceof List<?> list ? (List<String>) list : null;

                                String message = repo.writePage(project, type, tags, supersedes, consumedRawIds, content);
                                return new CallToolResult(List.of(new McpSchema.TextContent(message)), false);
                            } catch (Exception e) {
                                return new CallToolResult(List.of(new McpSchema.TextContent("Erro ao escrever página: " + e.getMessage())), true);
                            }
                        }
                ),
                new SyncToolRegistration(
                        new Tool("lint", "Verifica a saúde da base do Cortex, detectando referências inválidas, links quebrados e conflitos.", LINT_SCHEMA),
                        argsMap -> {
                            try {
                                String project = (String) argsMap.get("project");
                                List<String> warnings = repo.lint(project);
                                if (warnings.isEmpty()) {
                                    return new CallToolResult(List.of(new McpSchema.TextContent("Nenhum problema encontrado. Status: OK")), false);
                                } else {
                                    return new CallToolResult(List.of(new McpSchema.TextContent("Problemas encontrados:\n" + String.join("\n", warnings))), false);
                                }
                            } catch (Exception e) {
                                return new CallToolResult(List.of(new McpSchema.TextContent("Erro ao executar linter: " + e.getMessage())), true);
                            }
                        }
                ),
                new SyncToolRegistration(
                        new Tool("promote_rules", "Extrai as regras ativas da base de memória do Cortex e injeta no contexto da IDE (AGENTS.md)", PROMOTE_RULES_SCHEMA),
                        argsMap -> {
                            try {
                                String project = (String) argsMap.get("project");
                                String file = (String) argsMap.get("file");
                                repo.promoteRules(project, file);
                                return new CallToolResult(List.of(new McpSchema.TextContent("Regras promovidas com sucesso para " + file)), false);
                            } catch (Exception e) {
                                return new CallToolResult(List.of(new McpSchema.TextContent("Erro ao promover regras: " + e.getMessage())), true);
                            }
                        }
                ),
                new SyncToolRegistration(
                        new Tool("query", "Busca informacoes na memoria do projeto usando ranking BM25 e filtros facetados (type:, tags:, status:)", QUERY_SCHEMA),
                        argsMap -> {
                            try {
                                String project = (String) argsMap.get("project");
                                String terms = (String) argsMap.get("terms");
                                int limit = argsMap.get("limit") instanceof Number n ? n.intValue() : 10;

                                List<SearchResult> results = repo.searchLexical(project, terms, limit);
                                if (results.isEmpty()) {
                                    return new CallToolResult(List.of(new McpSchema.TextContent("Nenhum resultado encontrado para: " + terms)), false);
                                }
                                StringBuilder sb = new StringBuilder();
                                sb.append(String.format("Resultados da busca lexical (BM25) para '%s' (%d encontrados):\n\n", terms, results.size()));
                                for (SearchResult res : results) {
                                    sb.append(res.toFormattedSummary()).append("\n\n");
                                }
                                return new CallToolResult(List.of(new McpSchema.TextContent(sb.toString())), false);
                            } catch (Exception e) {
                                return new CallToolResult(List.of(new McpSchema.TextContent("Erro na busca: " + e.getMessage())), true);
                            }
                        }
                ),
                new SyncToolRegistration(
                        new Tool("consolidate", "Retorna notas brutas pendentes agrupadas em clusters temáticos para guiar a síntese curada via write_page.", CONSOLIDATE_SCHEMA),
                        argsMap -> {
                            try {
                                String project = (String) argsMap.get("project");
                                String topic = (String) argsMap.get("topic");
                                String report = repo.consolidateReport(project, topic);
                                return new CallToolResult(List.of(new McpSchema.TextContent(report)), false);
                            } catch (Exception e) {
                                return new CallToolResult(List.of(new McpSchema.TextContent("Erro na consolidação: " + e.getMessage())), true);
                            }
                        }
                ),
                new SyncToolRegistration(
                        new Tool("stats", "Retorna métricas de saúde, taxa de consolidação, distribuição de tags e densidade do grafo do projeto", STATS_SCHEMA),
                        argsMap -> {
                            try {
                                String project = (String) argsMap.get("project");
                                CortexStats stats = repo.getStats(project);
                                return new CallToolResult(List.of(new McpSchema.TextContent(stats.toMarkdownSummary())), false);
                            } catch (Exception e) {
                                return new CallToolResult(List.of(new McpSchema.TextContent("Erro ao obter estatísticas: " + e.getMessage())), true);
                            }
                        }
                ),
                new SyncToolRegistration(
                        new Tool("bootstrap", "Inicializa um projeto completo no Cortex com estrutura de diretórios, Skill e registro no config", BOOTSTRAP_SCHEMA),
                        argsMap -> {
                            try {
                                String project = (String) argsMap.get("project");
                                String rootPath = (String) argsMap.get("root_path");
                                String id = repo.bootstrap(project, rootPath);
                                return new CallToolResult(List.of(new McpSchema.TextContent("Projeto '" + id + "' inicializado com sucesso via bootstrap.")), false);
                            } catch (Exception e) {
                                return new CallToolResult(List.of(new McpSchema.TextContent("Erro no bootstrap: " + e.getMessage())), true);
                            }
                        }
                ),
                new SyncToolRegistration(
                        new Tool("handoff", "Gera um relatório de handoff consolidado do projeto para transferência de contexto entre agentes", HANDOFF_SCHEMA),
                        argsMap -> {
                            try {
                                String project = (String) argsMap.get("project");
                                String targetAgent = (String) argsMap.get("target_agent");
                                String summary = repo.handoff(project, targetAgent);
                                return new CallToolResult(List.of(new McpSchema.TextContent(summary)), false);
                            } catch (Exception e) {
                                return new CallToolResult(List.of(new McpSchema.TextContent("Erro no handoff: " + e.getMessage())), true);
                            }
                        }
                )
        );
    }

    /**
     * Registers all 9 Cortex MCP tools onto the given {@link McpServer.SyncSpec}.
     */
    public static McpServer.SyncSpec registerAll(McpServer.SyncSpec spec, ProjectRepository repo) {
        spec.tools(buildToolRegistrations(repo));
        return spec;
    }

    /**
     * Finds a tool registration by name.
     */
    public static SyncToolRegistration findToolRegistration(ProjectRepository repo, String toolName) {
        return buildToolRegistrations(repo).stream()
                .filter(reg -> reg.tool().name().equals(toolName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown MCP tool: " + toolName));
    }

    /**
     * Directly invokes a tool handler by name with the given arguments map.
     */
    public static CallToolResult invokeTool(ProjectRepository repo, String toolName, Map<String, Object> arguments) {
        return findToolRegistration(repo, toolName).call().apply(arguments);
    }
}
