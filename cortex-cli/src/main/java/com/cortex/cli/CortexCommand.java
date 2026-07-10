package com.cortex.cli;

import com.cortex.core.ProjectRepository;

import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.beans.factory.annotation.Autowired;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

@SpringBootApplication(scanBasePackages = "com.cortex")
@Command(name = "cortex", mixinStandardHelpOptions = true, version = "1.0",
        description = "Cortex - Memory and Wiki System",
        subcommands = {
                InitCommand.class,
                IngestCommand.class,
                QueryCommand.class,
                ConsolidateCommand.class,
                LintCommand.class,
                PromoteCommand.class,
                BootstrapCommand.class,
                HandoffCommand.class
        })
public class CortexCommand implements Callable<Integer>, CommandLineRunner {

    @Autowired
    private CommandLine.IFactory factory;

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Override
    public void run(String... args) {
        int exitCode = new CommandLine(this, factory).execute(args);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(CortexCommand.class);
        app.setBannerMode(org.springframework.boot.Banner.Mode.OFF);
        // Desativar logging do Spring na CLI
        app.setDefaultProperties(java.util.Collections.singletonMap("logging.level.root", "ERROR"));
        app.run(args);
    }
}
