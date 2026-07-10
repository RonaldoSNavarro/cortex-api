package com.cortex.cli;

import com.cortex.core.ProjectRepository;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.List;
import java.util.concurrent.Callable;

@Component
@Command(name = "lint", description = "Verifica a integridade da Wiki e encontra referências mortas.")
public class LintCommand implements Callable<Integer> {

    @Autowired
    private ProjectRepository repo;


    @Option(names = {"--project"}, required = true, description = "Project ID")
    private String projectId;

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
