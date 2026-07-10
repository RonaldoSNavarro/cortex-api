package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;
import com.cortex.core.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class HandoffCommandStrategy implements CommandStrategy {
    @Autowired
    private ProjectRepository repo;

    @Override
    public String getCommandName() { return "handoff"; }

    @Override
    public String execute(CommandRequest request) throws Exception {
        String target = request.getTargetAgent() != null ? request.getTargetAgent() : "default";
        return repo.handoff(request.getProject(), target);
    }
}
