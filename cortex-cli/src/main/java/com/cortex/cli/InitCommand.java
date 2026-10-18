package com.cortex.cli;

import com.cortex.core.CortexConfig;
import com.cortex.core.CortexConfigManager;
import com.cortex.core.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Comando CLI para inicializar o Cortex e registrar novos projetos.
 */
@Component
@Command(name = "init", description = "Inicializa o Cortex e um projeto.")
public class InitCommand implements Callable<Integer> {

    private final ProjectRepository repo;

    @Option(names = {"--project"}, description = "Project ID")
    private String projectId;

    @Option(names = {"--path"}, description = "Project Path")
    private String projectPath;

    public InitCommand() {
        this(null);
    }

    @Autowired
    public InitCommand(ProjectRepository repo) {
        this.repo = repo;
    }

    @Override
    public Integer call() throws Exception {
        CortexConfigManager configManager = new CortexConfigManager();
        CortexConfig config = configManager.loadConfig();
        
        System.out.println("Cortex inicializado em ~/.cortex");
        
        if (projectId != null) {
            if (projectPath == null) {
                projectPath = System.getProperty("user.dir");
            }
            config.getProjects().put(projectPath, projectId);
            
            repo.init(projectId);
            System.out.println("Projeto " + projectId + " criado.");
        }
        
        configManager.saveConfig(config);
        return 0;
    }
}
