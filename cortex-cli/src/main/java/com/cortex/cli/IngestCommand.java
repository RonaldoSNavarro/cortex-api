package com.cortex.cli;

import com.cortex.core.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.concurrent.Callable;

/**
 * Comando CLI para capturar notas e informações brutas (raw).
 */
@Component
@Command(name = "ingest", aliases = {"capture"}, description = "Captura informacoes brutas (raw).")
public class IngestCommand implements Callable<Integer> {

    private final ProjectRepository repo;

    @Option(names = {"--project"}, required = true, description = "Project ID")
    private String projectId;

    @Option(names = {"--type"}, required = true, description = "Type of memory")
    private String type;

    @Parameters(index = "0", description = "Conteúdo para ingestão")
    private String content;

    public IngestCommand() {
        this(null);
    }

    @Autowired
    public IngestCommand(ProjectRepository repo) {
        this.repo = repo;
    }

    @Override
    public Integer call() throws Exception {
        try {
            String id = repo.createRaw(projectId, type, content);
            System.out.println("Ingestão salva com ID " + id);
        } catch (IllegalArgumentException e) {
            System.err.println("Falha na ingestão: " + e.getMessage());
            return 1;
        }
        return 0;
    }
}
