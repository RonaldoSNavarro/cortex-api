package com.cortex.cli;

import com.cortex.core.ProjectRepository;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Callable;

@Component
@Command(name = "ingest", description = "Captura informacoes brutas (raw).")
public class IngestCommand implements Callable<Integer> {

    @Autowired
    private ProjectRepository repo;


    @Option(names = {"--project"}, required = true, description = "Project ID")
    private String projectId;

    @Option(names = {"--type"}, required = true, description = "Type of memory")
    private String type;

    @Parameters(index = "0", description = "Conteúdo para ingestão")
    private String content;

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
