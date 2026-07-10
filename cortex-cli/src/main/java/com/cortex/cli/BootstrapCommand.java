package com.cortex.cli;

import com.cortex.core.ProjectRepository;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import java.util.concurrent.Callable;

@Component
@Command(name = "bootstrap", description = "Inicializa um projeto completo no Cortex com Skill e config.")
public class BootstrapCommand implements Callable<Integer> {

    @Autowired
    private ProjectRepository repo;


    @Option(names = {"--project"}, required = true, description = "Project ID")
    private String projectId;

    @Option(names = {"--path"}, description = "Caminho absoluto do repositório de código")
    private String rootPath;

    @Override
    public Integer call() throws Exception {
        if (rootPath == null) {
            rootPath = System.getProperty("user.dir");
        }

        
        String id = repo.bootstrap(projectId, rootPath);

        System.err.println("Projeto '" + id + "' inicializado com sucesso via bootstrap.");
        System.err.println("Estrutura criada em: ~/.cortex/projects/" + id);
        System.err.println("Skill ejetada em: " + rootPath + "/.agents/skills/cortex-conventions/SKILL.md");
        return 0;
    }
}
