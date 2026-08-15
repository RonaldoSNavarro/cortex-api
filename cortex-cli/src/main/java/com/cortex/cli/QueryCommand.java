package com.cortex.cli;

import com.cortex.core.ProjectRepository;

import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import java.util.concurrent.Callable;

@Component
@Command(name = "query", description = "Busca texto na wiki do projeto.")
public class QueryCommand implements Callable<Integer> {

    @Autowired
    private ProjectRepository repo;


    @Option(names = {"--project"}, required = true, description = "Project ID")
    private String projectId;

    @Parameters(index = "0", description = "Termos de busca")
    private String terms;

    @Override
    public Integer call() throws Exception {
        java.util.List<com.cortex.core.SearchResult> results = repo.searchLexical(projectId, terms, 10);
        
        if (results.isEmpty()) {
            System.out.println("Nenhum resultado encontrado para '" + terms + "' no projeto " + projectId);
            return 0;
        }

        System.out.println("Resultados da busca lexical (BM25) para '" + terms + "' no projeto " + projectId + ":\n");
        for (com.cortex.core.SearchResult res : results) {
            System.out.println(res.toFormattedSummary());
            System.out.println("-".repeat(50));
        }
        
        return 0;
    }
}
