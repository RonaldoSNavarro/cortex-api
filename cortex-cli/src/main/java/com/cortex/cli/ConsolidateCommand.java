package com.cortex.cli;

import com.cortex.core.ProjectRepository;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

@Component
@Command(name = "consolidate", description = "Monitora e sugere a consolidação de informações brutas.")
public class ConsolidateCommand implements Callable<Integer> {

    @Autowired
    private ProjectRepository repo;


    @Option(names = {"--project"}, required = true, description = "Project ID")
    private String projectId;

    @Override
    public Integer call() throws Exception {
        
        long pendingCount = repo.getPendingRawCount(projectId);
        
        System.out.println("=== Cortex Consolidate ===");
        System.out.println("Projeto: " + projectId);
        System.out.println("Status: " + pendingCount + " arquivo(s) bruto(s) aguardando consolidação em raw/.");
        
        if (pendingCount > 0) {
            System.out.println("\nSugestão: Solicite à sua IA assistente (Claude ou Antigravity) para processar estes arquivos usando a tool 'write_page' e arquivá-los em páginas curadas!");
        } else {
            System.out.println("Tudo limpo! Nenhuma consolidação pendente.");
        }
        
        return 0;
    }
}
