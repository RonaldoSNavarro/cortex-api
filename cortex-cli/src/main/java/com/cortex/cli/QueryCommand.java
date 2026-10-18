package com.cortex.cli;

import com.cortex.core.ProjectRepository;
import com.cortex.core.SearchResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Comando CLI para buscar texto na wiki do projeto com algoritmo BM25.
 */
@Component
@Command(name = "query", description = "Busca texto na wiki do projeto.")
public class QueryCommand implements Callable<Integer> {

    private final ProjectRepository repo;

    @Option(names = {"--project"}, required = true, description = "Project ID")
    private String projectId;

    @Parameters(index = "0", description = "Termos de busca")
    private String terms;

    public QueryCommand() {
        this(null);
    }

    @Autowired
    public QueryCommand(ProjectRepository repo) {
        this.repo = repo;
    }

    @Override
    public Integer call() throws Exception {
        List<SearchResult> results = repo.searchLexical(projectId, terms, 10);
        
        if (results.isEmpty()) {
            System.out.println("Nenhum resultado encontrado para '" + terms + "' no projeto " + projectId);
            return 0;
        }

        System.out.println("Resultados da busca lexical (BM25) para '" + terms + "' no projeto " + projectId + ":\n");
        for (SearchResult res : results) {
            System.out.println(res.toFormattedSummary());
            System.out.println("-".repeat(50));
        }
        
        return 0;
    }
}
