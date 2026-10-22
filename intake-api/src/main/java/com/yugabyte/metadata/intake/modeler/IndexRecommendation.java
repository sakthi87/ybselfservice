package com.yugabyte.metadata.intake.modeler;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class IndexRecommendation {
    private String field;
    private String reason;
    private Cardinality cardinality;

    public IndexRecommendation() {
    }

    public IndexRecommendation(String field, String reason, Cardinality cardinality) {
        this.field = field;
        this.reason = reason;
        this.cardinality = cardinality;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public Cardinality getCardinality() {
        return cardinality;
    }

    public void setCardinality(Cardinality cardinality) {
        this.cardinality = cardinality;
    }
}

