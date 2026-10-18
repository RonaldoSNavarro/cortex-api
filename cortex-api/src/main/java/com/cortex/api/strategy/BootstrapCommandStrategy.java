package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;
import com.cortex.core.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Estratégia REST para inicialização e bootstrap de projetos no Cortex.
 */
@Component
public class BootstrapCommandStrategy implements CommandStrategy {

    private final ProjectRepository repo;

    public BootstrapCommandStrategy() {
        this(null);
    }

    @Autowired
    public BootstrapCommandStrategy(ProjectRepository repo) {
        this.repo = repo;
    }

    @Override
    public String getCommandName() { return "bootstrap"; }

    @Override
    public String execute(CommandRequest request) throws Exception {
        if (request.getRootPath() == null || request.getRootPath().trim().isEmpty()) {
            throw new IllegalArgumentException("rootPath é obrigatório para bootstrap");
        }
        return "Projeto bootstrapped com ID: " + repo.bootstrap(request.getProject(), request.getRootPath());
    }
}
