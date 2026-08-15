package com.cortex.cli;

import com.cortex.core.CortexStats;
import com.cortex.core.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Component
@Command(name = "stats", description = "Exibe métricas de saúde, conexões e consolidação do projeto.")
public class StatsCommand implements Callable<Integer> {

    @Autowired
    private ProjectRepository repo;

    @Option(names = {"--project"}, required = true, description = "Project ID")
    private String projectId;

    @Override
    public Integer call() throws Exception {
        CortexStats stats = repo.getStats(projectId);
        System.out.println(stats.toMarkdownSummary());
        return 0;
    }
}
