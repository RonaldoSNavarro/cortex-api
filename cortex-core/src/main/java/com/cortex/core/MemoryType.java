package com.cortex.core;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum MemoryType {
    FACT("fact"),
    DECISION("decision"),
    RULE("rule"),
    GOTCHA("gotcha"),
    NOTE("note");

    private final String value;

    MemoryType(String value) {
        this.value = value;
    }

    @JsonValue
    public String getValue() {
        return value;
    }

    @JsonCreator
    public static MemoryType fromValue(String text) {
        for (MemoryType b : MemoryType.values()) {
            if (String.valueOf(b.value).equalsIgnoreCase(text)) {
                return b;
            }
        }
        return null;
    }
}
