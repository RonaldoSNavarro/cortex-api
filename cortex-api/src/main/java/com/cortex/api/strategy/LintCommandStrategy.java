package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;
import com.cortex.core.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import java.util.List;

@Component
public class LintCommandStrategy implements CommandStrategy {
    @Autowired
    private ProjectRepository repo;

    @Override
    public String getCommandName() { return "lint"; }

    @Override
    public String execute(CommandRequest request) throws Exception {
        List<String> warnings = repo.lint(request.getProject());
        if (warnings.isEmpty()) {
            return "Nenhum problema encontrado. Status: OK";
        } else {
            return "Problemas encontrados:\n" + String.join("\n", warnings);
        }
    }
}
