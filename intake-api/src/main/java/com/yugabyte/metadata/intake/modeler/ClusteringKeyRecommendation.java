package com.yugabyte.metadata.intake.modeler;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ClusteringKeyRecommendation {
    private String field;
    private String order;
    private ClusteringKeyType type;
    private String reason;

    public ClusteringKeyRecommendation() {
    }

    public ClusteringKeyRecommendation(String field, String order, ClusteringKeyType type, String reason) {
        this.field = field;
        this.order = order;
        this.type = type;
        this.reason = reason;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getOrder() {
        return order;
    }

    public void setOrder(String order) {
        this.order = order;
    }

    public ClusteringKeyType getType() {
        return type;
    }

    public void setType(ClusteringKeyType type) {
        this.type = type;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }
}

