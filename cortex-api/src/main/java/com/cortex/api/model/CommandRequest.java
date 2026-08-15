package com.cortex.api.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.ArrayList;
import java.util.List;

/**
 * Carries the common and command-specific fields accepted by the REST command API.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Requisição para execução de comandos Cortex")
public class CommandRequest {
    @Schema(description = "ID do projeto", example = "cortex")
    private String project;

    @Schema(
        description = "Comando a executar (init, bootstrap, query, capture, write_page, consolidate, promote_rules, lint, handoff, stats)",
        example = "query"
    )
    private String command;

    @Schema(description = "Caminho raiz (necessário para bootstrap)", example = "/caminho/do/projeto")
    private String rootPath;

    @Schema(description = "Agente destino (necessário para handoff)", example = "codex")
    private String targetAgent;

    @Schema(description = "Termos de busca (necessário para query)", example = "MCP")
    @JsonAlias("query")
    private String terms;

    @Schema(description = "Tópico opcional para consolidação em lote", example = "jackson")
    private String topic;

    @Schema(description = "Tipo de memória (necessário para capture e write_page)", example = "gotcha")
    private String type;

    @Schema(description = "Conteúdo da memória (necessário para capture e write_page)")
    private String content;

    @Schema(description = "Tags da página curada (opcional para write_page)", example = "[\"mcp\", \"api\"]")
    private List<String> tags = new ArrayList<>();

    @Schema(description = "ID da página substituída (opcional para write_page)")
    private String supersedes;

    @Schema(description = "IDs das notas raw consumidas (opcional para write_page)")
    private List<String> consumedRawIds = new ArrayList<>();

    @Schema(description = "Arquivo de destino das regras (necessário para promote_rules)", example = ".agents/AGENTS.md")
    private String file;

    public CommandRequest() {
    }

    public String getProject() {
        return project;
    }

    public void setProject(String project) {
        this.project = project;
    }

    public String getCommand() {
        return command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public String getRootPath() {
        return rootPath;
    }

    public void setRootPath(String rootPath) {
        this.rootPath = rootPath;
    }

    public String getTargetAgent() {
        return targetAgent;
    }

    public void setTargetAgent(String targetAgent) {
        this.targetAgent = targetAgent;
    }

    public String getTerms() {
        return terms;
    }

    public void setTerms(String terms) {
        this.terms = terms;
    }

    public String getTopic() {
        return topic;
    }

    public void setTopic(String topic) {
        this.topic = topic;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public List<String> getTags() {
        if (tags == null) tags = new ArrayList<>();
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
    }

    public String getSupersedes() {
        return supersedes;
    }

    public void setSupersedes(String supersedes) {
        this.supersedes = supersedes;
    }

    public List<String> getConsumedRawIds() {
        if (consumedRawIds == null) consumedRawIds = new ArrayList<>();
        return consumedRawIds;
    }

    public void setConsumedRawIds(List<String> consumedRawIds) {
        this.consumedRawIds = consumedRawIds != null ? new ArrayList<>(consumedRawIds) : new ArrayList<>();
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }
}
