package com.yugabyte.metadata.intake.modeler;

public enum PartitionSizeExpectation {
    SMALL,
    MEDIUM,
    LARGE,
    VERY_LARGE,
    UNKNOWN;

    public static PartitionSizeExpectation fromString(String value) {
        if (value == null || value.isEmpty()) {
            return UNKNOWN;
        }
        try {
            return PartitionSizeExpectation.valueOf(value.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return UNKNOWN;
        }
    }
}

