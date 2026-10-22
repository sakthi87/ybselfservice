package com.yugabyte.metadata.intake.modeler;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonSetter;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccessPattern {
    private String name;
    private String description;
    private List<PatternField> filters = new ArrayList<>();
    private List<SortField> sortFields = new ArrayList<>();
    private String queryFrequency;
    private Cardinality cardinality; // Pattern-level cardinality

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<PatternField> getFilters() {
        return filters;
    }

    public void setFilters(List<PatternField> filters) {
        this.filters = filters;
    }

    public List<SortField> getSortFields() {
        return sortFields;
    }

    public void setSortFields(List<SortField> sortFields) {
        this.sortFields = sortFields;
    }

    public String getQueryFrequency() {
        return queryFrequency;
    }

    public void setQueryFrequency(String queryFrequency) {
        this.queryFrequency = queryFrequency;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public Cardinality getCardinality() {
        return cardinality;
    }

    public void setCardinality(Cardinality cardinality) {
        this.cardinality = cardinality;
    }
    
    // Custom setter to handle string deserialization from JSON
    @JsonSetter("cardinality")
    public void setCardinalityFromString(String cardinalityString) {
        this.cardinality = Cardinality.fromString(cardinalityString);
    }
}

