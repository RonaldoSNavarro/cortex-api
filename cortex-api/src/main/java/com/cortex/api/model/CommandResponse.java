package com.cortex.api.model;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "Resposta da execução de um comando")
public class CommandResponse {
    @Schema(description = "Status da execução (success ou error)", example = "success")
    private String status;
    
    @Schema(description = "Saída do comando executado")
    private String output;
}
