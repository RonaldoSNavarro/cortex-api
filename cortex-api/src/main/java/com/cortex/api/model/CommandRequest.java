package com.cortex.api.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import lombok.Data;

/**
 * Carries the common and command-specific fields accepted by the REST command API.
 */
@Data
@Schema(description = "Requisição para execução de comandos Cortex")
public class CommandRequest {
    @Schema(description = "ID do projeto", example = "cortex")
    private String project;

    @Schema(
        description = "Comando a executar (init, bootstrap, query, capture, write_page, consolidate, promote_rules, lint, handoff)",
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

    @Schema(description = "Tipo de memória (necessário para capture e write_page)", example = "gotcha")
    private String type;

    @Schema(description = "Conteúdo da memória (necessário para capture e write_page)")
    private String content;

    @Schema(description = "Tags da página curada (opcional para write_page)", example = "[\"mcp\", \"api\"]")
    private List<String> tags;

    @Schema(description = "ID da página substituída (opcional para write_page)")
    private String supersedes;

    @Schema(description = "IDs das notas raw consumidas (opcional para write_page)")
    private List<String> consumedRawIds;

    @Schema(description = "Arquivo de destino das regras (necessário para promote_rules)", example = ".agents/AGENTS.md")
    private String file;
}
