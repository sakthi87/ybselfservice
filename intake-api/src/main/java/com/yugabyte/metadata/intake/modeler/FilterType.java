package com.yugabyte.metadata.intake.modeler;

public enum FilterType {
    EQUALITY,
    RANGE,
    IN,
    UNKNOWN;

    public static FilterType fromString(String value) {
        if (value == null || value.isEmpty()) {
            return UNKNOWN;
        }
        try {
            return FilterType.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}

