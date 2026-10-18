package com.cortex.cli;

import com.cortex.core.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * Comando CLI para monitorar e sugerir a consolidação de informações brutas em páginas de memória.
 */
@Component
@Command(name = "consolidate", description = "Monitora e sugere a consolidação de informações brutas.")
public class ConsolidateCommand implements Callable<Integer> {

    private final ProjectRepository repo;

    @Spec
    private CommandLine.Model.CommandSpec spec;

    @Option(names = {"--project"}, required = true, description = "Project ID")
    private String projectId;

    @Option(names = {"--topic"}, description = "Tópico opcional para filtrar notas na consolidação")
    private String topic;

    public ConsolidateCommand() {
        this(null);
    }

    @Autowired
    public ConsolidateCommand(ProjectRepository repo) {
        this.repo = repo;
    }

    @Override
    public Integer call() throws Exception {
        String report = repo.consolidateReport(projectId, topic);
        if (spec != null && spec.commandLine() != null) {
            spec.commandLine().getOut().println(report);
        } else {
            System.out.println(report);
        }
        return 0;
    }
}
