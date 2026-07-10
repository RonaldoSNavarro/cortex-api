package com.cortex.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.File;
import java.io.IOException;
import java.util.HashMap;

import org.springframework.stereotype.Service;

@Service
public class CortexConfigManager {
    private static final String CONFIG_FILE = System.getProperty("user.home") + "/.cortex/config.yaml";
    private final ObjectMapper mapper;

    public CortexConfigManager() {
        this.mapper = new ObjectMapper(new YAMLFactory());
    }

    public CortexConfig loadConfig() throws IOException {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) {
            CortexConfig defaultConfig = new CortexConfig();
            defaultConfig.setProjects(new HashMap<>());
            return defaultConfig;
        }
        return mapper.readValue(file, CortexConfig.class);
    }

    public void saveConfig(CortexConfig config) throws IOException {
        File file = new File(CONFIG_FILE);
        file.getParentFile().mkdirs();
        mapper.writeValue(file, config);
    }
}
