package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;
import com.cortex.core.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class InitCommandStrategy implements CommandStrategy {
    @Autowired
    private ProjectRepository repo;

    @Override
    public String getCommandName() { return "init"; }

    @Override
    public String execute(CommandRequest request) throws Exception {
        repo.init(request.getProject());
        return "Repositório inicializado para " + request.getProject();
    }
}
