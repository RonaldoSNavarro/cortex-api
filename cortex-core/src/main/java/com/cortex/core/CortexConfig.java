package com.cortex.core;

import java.util.HashMap;
import java.util.Map;

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
