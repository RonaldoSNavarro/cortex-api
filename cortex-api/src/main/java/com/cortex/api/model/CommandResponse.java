package com.cortex.api.model;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Resposta da execução de um comando")
public class CommandResponse {
    @Schema(description = "Status da execução (success ou error)", example = "success")
    private String status;
    
    @Schema(description = "Saída do comando executado")
    private String output;

    public CommandResponse() {
    }

    public CommandResponse(String status, String output) {
        this.status = status;
        this.output = output;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getOutput() {
        return output;
    }

    public void setOutput(String output) {
        this.output = output;
    }
}
