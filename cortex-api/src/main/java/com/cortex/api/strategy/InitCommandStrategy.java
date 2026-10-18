package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;
import com.cortex.core.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Estratégia REST para inicialização básica do diretório de projeto.
 */
@Component
public class InitCommandStrategy implements CommandStrategy {

    private final ProjectRepository repo;

    public InitCommandStrategy() {
        this(null);
    }

    @Autowired
    public InitCommandStrategy(ProjectRepository repo) {
        this.repo = repo;
    }

    @Override
    public String getCommandName() { return "init"; }

    @Override
    public String execute(CommandRequest request) throws Exception {
        repo.init(request.getProject());
        return "Repositório inicializado para " + request.getProject();
    }
}
