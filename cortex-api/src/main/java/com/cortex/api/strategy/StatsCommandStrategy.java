package com.cortex.api.strategy;

import com.cortex.api.model.CommandRequest;
import com.cortex.core.CortexStats;
import com.cortex.core.ProjectRepository;
import org.springframework.stereotype.Component;

/** Returns health metrics and statistics of the project knowledge base through the REST command API. */
@Component
public class StatsCommandStrategy implements CommandStrategy {

    private final ProjectRepository repository;

    public StatsCommandStrategy(ProjectRepository repository) {
        this.repository = repository;
    }

    /** {@inheritDoc} */
    @Override
    public String getCommandName() {
        return "stats";
    }

    /** {@inheritDoc} */
    @Override
    public String execute(CommandRequest request) throws Exception {
        CortexStats stats = repository.getStats(request.getProject());
        return stats.toMarkdownSummary();
    }
}
