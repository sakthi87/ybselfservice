package com.yugabyte.metadata.intake.modeler;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class FieldMetadata {
    private String name;
    private String dataType;
    private String description;
    private boolean businessKey;
    private boolean mutable;
    private boolean tenantField;
    private boolean timeField;
    private Cardinality cardinality = Cardinality.UNKNOWN;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDataType() {
        return dataType;
    }

    public void setDataType(String dataType) {
        this.dataType = dataType;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean isBusinessKey() {
        return businessKey;
    }

    public void setBusinessKey(boolean businessKey) {
        this.businessKey = businessKey;
    }

    public boolean isMutable() {
        return mutable;
    }

    public void setMutable(boolean mutable) {
        this.mutable = mutable;
    }

    public boolean isTenantField() {
        return tenantField;
    }

    public void setTenantField(boolean tenantField) {
        this.tenantField = tenantField;
    }

    public boolean isTimeField() {
        return timeField;
    }

    public void setTimeField(boolean timeField) {
        this.timeField = timeField;
    }

    public Cardinality getCardinality() {
        return cardinality;
    }

    public void setCardinality(Cardinality cardinality) {
        this.cardinality = cardinality;
    }
}

