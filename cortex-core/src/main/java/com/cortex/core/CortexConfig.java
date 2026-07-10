package com.cortex.core;

import java.util.Map;

import lombok.Data;

@Data
public class CortexConfig {
    private Map<String, String> projects;
}
