package com.cortex.cli;

import com.cortex.core.ProjectRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.concurrent.Callable;

/**
 * Comando CLI para inicializar projetos no Cortex com Skill e configurações.
 */
@Component
@Command(name = "bootstrap", description = "Inicializa um projeto completo no Cortex com Skill e config.")
public class BootstrapCommand implements Callable<Integer> {

    private final ProjectRepository repo;

    @Option(names = {"--project"}, required = false, description = "Project ID")
    private String projectId;

    @Option(names = {"--path"}, description = "Caminho absoluto do repositório de código")
    private String rootPath;

    public BootstrapCommand() {
        this(null);
    }

    @Autowired
    public BootstrapCommand(ProjectRepository repo) {
        this.repo = repo;
    }

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
