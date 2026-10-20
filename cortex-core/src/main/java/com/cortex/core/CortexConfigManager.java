package com.cortex.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Gerenciador de persistência atômica da configuração global (~/.cortex/config.yaml).
 * Garante operações thread-safe, substituição atômica de arquivos e consistência no mapeamento de projetos.
 */
@Service
public class CortexConfigManager {
    private static final Object FILE_LOCK = new Object();
    private final ObjectMapper mapper;

    public CortexConfigManager() {
        this.mapper = new ObjectMapper(new YAMLFactory());
    }

    private File getConfigFile() {
        return new File(System.getProperty("user.home"), ".cortex/config.yaml");
    }

    public CortexConfig loadConfig() throws IOException {
        synchronized (FILE_LOCK) {
            File file = getConfigFile();
            if (!file.exists()) {
                CortexConfig defaultConfig = new CortexConfig();
                defaultConfig.setProjects(new HashMap<>());
                return defaultConfig;
            }
            return mapper.readValue(file, CortexConfig.class);
        }
    }

    public void saveConfig(CortexConfig config) throws IOException {
        synchronized (FILE_LOCK) {
            File file = getConfigFile();
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }

            File tempFile = File.createTempFile("config-", ".tmp", parent);
            try {
                mapper.writeValue(tempFile, config);
                try {
                    Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicEx) {
                    Files.move(tempFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                if (tempFile.exists()) {
                    tempFile.delete();
                }
            }
        }
    }

    /**
     * Registra ou atualiza atomicamente o mapeamento de um projeto para um rootPath.
     * Remove quaisquer caminhos antigos previamente associados ao mesmo projectId (mapeamento 1:1).
     */
    public void registerProject(String rootPath, String projectId) throws IOException {
        synchronized (FILE_LOCK) {
            CortexConfig config = loadConfig();
            Map<String, String> projects = config.getProjects();

            // Remove caminhos anteriores apontando para o mesmo projectId
            projects.entrySet().removeIf(entry -> projectId.equals(entry.getValue()));

            projects.put(rootPath, projectId);
            saveConfig(config);
        }
    }
}
