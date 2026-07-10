package com.cortex.cli;

import com.cortex.core.CortexConfig;
import com.cortex.core.CortexConfigManager;
import com.cortex.core.ProjectRepository;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.io.IOException;
import java.util.concurrent.Callable;

@Component
@Command(name = "init", description = "Inicializa o Cortex e um projeto.")
public class InitCommand implements Callable<Integer> {

    @Autowired
    private ProjectRepository repo;


    @Option(names = {"--project"}, description = "Project ID")
    private String projectId;

    @Option(names = {"--path"}, description = "Project Path")
    private String projectPath;

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
