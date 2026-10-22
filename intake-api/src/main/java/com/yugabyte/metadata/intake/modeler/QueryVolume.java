package com.yugabyte.metadata.intake.modeler;

public enum QueryVolume {
    READ_HEAVY,
    WRITE_HEAVY,
    BALANCED,
    UNKNOWN;

    public static QueryVolume fromString(String value) {
        if (value == null || value.isEmpty()) {
            return UNKNOWN;
        }
        try {
            return QueryVolume.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}

