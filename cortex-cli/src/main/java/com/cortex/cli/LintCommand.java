package com.cortex.cli;

import com.cortex.core.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * Comando CLI para verificar a integridade da Wiki e auditar links quebrados.
 */
@Component
@Command(name = "lint", description = "Verifica a integridade da Wiki e encontra referências mortas.")
public class LintCommand implements Callable<Integer> {

    private final ProjectRepository repo;

    @Option(names = {"--project"}, required = true, description = "Project ID")
    private String projectId;

    public LintCommand() {
        this(null);
    }

    @Autowired
    public LintCommand(ProjectRepository repo) {
        this.repo = repo;
    }

    @Override
    public Integer call() throws Exception {
        List<String> warnings = repo.lint(projectId);
        
        System.out.println("=== Cortex Lint ===");
        System.out.println("Projeto: " + projectId);
        
        if (warnings.isEmpty()) {
            System.out.println("Status: OK. Nenhuma inconsistência encontrada na base de conhecimento.");
        } else {
            System.out.println("Foram encontrados " + warnings.size() + " avisos:");
            for (String warning : warnings) {
                System.out.println("- " + warning);
            }
            return 1;
        }
        
        return 0;
    }
}
