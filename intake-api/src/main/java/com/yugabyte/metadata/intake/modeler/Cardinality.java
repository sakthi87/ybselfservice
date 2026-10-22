package com.yugabyte.metadata.intake.modeler;

public enum Cardinality {
    HIGH,
    MEDIUM,
    LOW,
    UNKNOWN;

    public static Cardinality fromString(String value) {
        if (value == null || value.isEmpty()) {
            return UNKNOWN;
        }
        try {
            return Cardinality.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}

