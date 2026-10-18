package com.cortex.cli;

import com.cortex.core.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Comando CLI para promover as regras de memória ativa para o arquivo de agentes da IDE.
 */
@Component
@Command(name = "promote_rule", aliases = {"promote_rules"}, description = "Promove as regras de memória ativa para um arquivo de agentes na IDE.")
public class PromoteCommand implements Callable<Integer> {

    private final ProjectRepository repo;

    @Option(names = {"--project"}, required = true, description = "Project ID")
    private String projectId;
    
    @Option(names = {"--file"}, description = "Caminho do arquivo destino para promover as regras (ex: .agents/AGENTS.md)")
    private String file = ".agents/AGENTS.md";

    public PromoteCommand() {
        this(null);
    }

    @Autowired
    public PromoteCommand(ProjectRepository repo) {
        this.repo = repo;
    }

    @Override
    public Integer call() throws Exception {
        System.out.println("=== Cortex Promote ===");
        System.out.println("Projeto: " + projectId);
        System.out.println("Alvo: " + file);
        
        repo.promoteRules(projectId, file);
        
        System.out.println("Regras promovidas com sucesso!");
        return 0;
    }
}
