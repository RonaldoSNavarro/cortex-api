package com.cortex.core;

import java.util.HashMap;
import java.util.Map;

/**
 * Modelo de dados correspondente à configuração global em ~/.cortex/config.yaml.
 * Mapeia diretórios-raiz dos projetos aos seus respectivos identificadores únicos.
 */
public class CortexConfig {
    private Map<String, String> projects = new HashMap<>();

    public CortexConfig() {
    }

    public CortexConfig(Map<String, String> projects) {
        this.projects = projects != null ? projects : new HashMap<>();
    }

    public Map<String, String> getProjects() {
        if (projects == null) projects = new HashMap<>();
        return projects;
    }

    public void setProjects(Map<String, String> projects) {
        this.projects = projects != null ? projects : new HashMap<>();
    }
}
