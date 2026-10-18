package com.cortex.cli;

import com.cortex.core.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Comando CLI para gerar relatório de handoff consolidado do projeto.
 */
@Component
@Command(name = "handoff", description = "Gera um relatório de handoff consolidado do projeto.")
public class HandoffCommand implements Callable<Integer> {

    private final ProjectRepository repo;

    @Option(names = {"--project"}, required = true, description = "Project ID")
    private String projectId;

    @Option(names = {"--target"}, description = "Nome do agente alvo (ex: claude, antigravity)")
    private String targetAgent;

    public HandoffCommand() {
        this(null);
    }

    @Autowired
    public HandoffCommand(ProjectRepository repo) {
        this.repo = repo;
    }

    @Override
    public Integer call() throws Exception {
        String summary = repo.handoff(projectId, targetAgent);
        System.err.println(summary);
        return 0;
    }
}
