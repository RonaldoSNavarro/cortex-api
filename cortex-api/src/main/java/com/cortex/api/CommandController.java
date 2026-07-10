package com.cortex.api;

import com.cortex.api.model.CommandRequest;
import com.cortex.api.model.CommandResponse;
import com.cortex.api.strategy.CommandStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
@Tag(name = "Command API", description = "Executa comandos do Cortex (similar a CLI)")
public class CommandController {

    private final Map<String, CommandStrategy> strategies;

    public CommandController(List<CommandStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(s -> s.getCommandName().toLowerCase(), s -> s));
    }

    @PostMapping("/execute")
    @Operation(summary = "Executa um comando", description = "Recebe um comando (ex: bootstrap, handoff) e o executa via Strategy.")
    public ResponseEntity<CommandResponse> executeCommand(@RequestBody CommandRequest request) throws Exception {
        if (request.getCommand() == null) {
            throw new IllegalArgumentException("Parâmetro 'command' é obrigatório.");
        }

        if (request.getProject() == null || request.getProject().trim().isEmpty()) {
            if ("bootstrap".equalsIgnoreCase(request.getCommand()) && request.getRootPath() != null) {
                String root = request.getRootPath().replace("\\", "/");
                if (root.endsWith("/")) root = root.substring(0, root.length() - 1);
                String inferredProject = root.substring(root.lastIndexOf('/') + 1);
                request.setProject(inferredProject);
            } else {
                throw new IllegalArgumentException("Parâmetro 'project' é obrigatório.");
            }
        }

        CommandStrategy strategy = strategies.get(request.getCommand().toLowerCase());
        if (strategy == null) {
            throw new IllegalArgumentException("Comando desconhecido: " + request.getCommand());
        }

        String output = strategy.execute(request);
        return ResponseEntity.ok(new CommandResponse("success", output));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<CommandResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.BAD_REQUEST)
                .body(new CommandResponse("error", ex.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommandResponse> handleException(Exception ex) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new CommandResponse("error", "Erro interno: " + ex.getMessage()));
    }
}
