package com.cortex.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Requisição para execução de comandos Cortex")
public class CommandRequest {
    @Schema(description = "ID do projeto", example = "cortex")
    private String project;
    
    @Schema(description = "Comando a executar (init, lint, bootstrap, handoff)", example = "bootstrap")
    private String command;
    
    @Schema(description = "Caminho raiz (necessário para bootstrap)", example = "/caminho/do/projeto")
    private String rootPath;
    
    @Schema(description = "Agente destino (necessário para handoff)", example = "claude")
    private String targetAgent;
}
