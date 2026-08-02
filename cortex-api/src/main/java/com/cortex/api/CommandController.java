package com.cortex.api;

import com.cortex.api.model.CommandRequest;
import com.cortex.api.model.CommandResponse;
import com.cortex.api.strategy.CommandStrategy;
import com.cortex.core.CortexConfig;
import com.cortex.core.CortexConfigManager;
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
    private final CortexConfigManager configManager = new CortexConfigManager();

    public CommandController(List<CommandStrategy> strategyList) {
        this.strategies = strategyList.stream()
                .collect(Collectors.toMap(s -> s.getCommandName().toLowerCase(), s -> s));
    }

    @PostMapping("/execute")
    @Operation(summary = "Executa um comando", description = "Recebe e executa operações REST do Cortex, incluindo query, capture, write_page, consolidate e promote_rules.")
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
                String inferred = inferDefaultProject();
                if (inferred != null) {
                    request.setProject(inferred);
                } else {
                    throw new IllegalArgumentException(
                        "Parâmetro 'project' é obrigatório. Projetos disponíveis: " + getAvailableProjects());
                }
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

    @ExceptionHandler(org.springframework.http.converter.HttpMessageNotReadableException.class)
    public ResponseEntity<CommandResponse> handleJsonParseError(
            org.springframework.http.converter.HttpMessageNotReadableException ex) {
        String msg = "Erro de parsing JSON. Verifique o encoding (UTF-8) e a estrutura do payload.";
        if (ex.getMessage() != null && ex.getMessage().contains("UTF-8")) {
            msg += " Dica: o corpo da requisição contém bytes não-UTF-8 (ex: caracteres acentuados via PowerShell Windows).";
        }
        return ResponseEntity.badRequest().body(new CommandResponse("error", msg));
    }

    private String inferDefaultProject() {
        try {
            CortexConfigManager mgr = new CortexConfigManager();
            CortexConfig config = mgr.loadConfig();
            java.util.Set<String> uniqueProjects = new java.util.HashSet<>(config.getProjects().values());
            if (uniqueProjects.size() == 1) {
                return uniqueProjects.iterator().next();
            }
        } catch (Exception e) {
            // Config not available, can't infer
        }
        return null;
    }

    private String getAvailableProjects() {
        try {
            CortexConfigManager mgr = new CortexConfigManager();
            CortexConfig config = mgr.loadConfig();
            return String.join(", ", new java.util.TreeSet<>(config.getProjects().values()));
        } catch (Exception e) {
            return "(não foi possível carregar config)";
        }
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<CommandResponse> handleException(Exception ex) {
        return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new CommandResponse("error", "Erro interno: " + ex.getMessage()));
    }
}
