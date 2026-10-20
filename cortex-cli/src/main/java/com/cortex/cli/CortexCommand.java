package com.cortex.cli;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import picocli.CommandLine;
import picocli.CommandLine.Command;

import java.util.concurrent.Callable;

/**
 * Ponto de entrada CLI do sistema Cortex via Spring Boot e Picocli.
 */
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
                HandoffCommand.class,
                StatsCommand.class
        })
public class CortexCommand implements Callable<Integer>, CommandLineRunner {

    private final CommandLine.IFactory factory;

    public CortexCommand() {
        this(null);
    }

    @Autowired
    public CortexCommand(@Autowired(required = false) CommandLine.IFactory factory) {
        this.factory = factory;
    }

    @Override
    public Integer call() {
        CommandLine.usage(this, System.out);
        return 0;
    }

    @Override
    public void run(String... args) {
        CommandLine cmdLine = factory != null ? new CommandLine(this, factory) : new CommandLine(this);
        int exitCode = cmdLine.execute(args);
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
