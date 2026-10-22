package com.yugabyte.metadata.intake.modeler;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class EntityModelRequest {
    private String entityName;
    private String keyspace;
    private String description;
    private List<FieldMetadata> fields = new ArrayList<>();
    private List<AccessPattern> accessPatterns = new ArrayList<>(); // All access patterns treated equally
    private ConstraintSettings constraints;

    public String getEntityName() {
        return entityName;
    }

    public void setEntityName(String entityName) {
        this.entityName = entityName;
    }

    public String getKeyspace() {
        return keyspace;
    }

    public void setKeyspace(String keyspace) {
        this.keyspace = keyspace;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<FieldMetadata> getFields() {
        return fields;
    }

    public void setFields(List<FieldMetadata> fields) {
        this.fields = fields;
    }

    public List<AccessPattern> getAccessPatterns() {
        return accessPatterns;
    }

    public void setAccessPatterns(List<AccessPattern> accessPatterns) {
        this.accessPatterns = accessPatterns;
    }

    public ConstraintSettings getConstraints() {
        return constraints;
    }

    public void setConstraints(ConstraintSettings constraints) {
        this.constraints = constraints;
    }
}

