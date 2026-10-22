package com.yugabyte.metadata.intake.modeler;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class PatternField {
    private String field;
    private FilterType type = FilterType.UNKNOWN;
    private String notes;

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public FilterType getType() {
        return type;
    }

    public void setType(FilterType type) {
        this.type = type;
    }
    
    // Custom setter to handle string deserialization from JSON
    @JsonSetter("type")
    public void setTypeFromString(String typeString) {
        this.type = FilterType.fromString(typeString);
    }

    public String getNotes() {
        return notes;
    }

    public void setNotes(String notes) {
        this.notes = notes;
    }
}

