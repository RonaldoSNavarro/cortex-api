package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;
import com.cortex.core.ProjectRepository;
import org.springframework.stereotype.Component;

/** Promotes active Cortex rules into an agent instruction file through REST. */
@Component
public class PromoteRulesCommandStrategy implements CommandStrategy {

    private final ProjectRepository repository;

    public PromoteRulesCommandStrategy(ProjectRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public String getCommandName() {
        return "promote_rules";
    }

    /** {@inheritDoc} */
    @Override
    public String execute(CommandRequest request) throws Exception {
        String file = request.getFile();
        if (file == null || file.trim().isEmpty()) {
            throw new IllegalArgumentException("Parâmetro 'file' é obrigatório para promote_rules.");
        }
        repository.promoteRules(request.getProject(), file.trim());
        return "Regras promovidas com sucesso para " + file.trim();
    }
}
