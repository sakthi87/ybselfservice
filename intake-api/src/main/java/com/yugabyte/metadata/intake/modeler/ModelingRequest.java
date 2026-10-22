package com.yugabyte.metadata.intake.modeler;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelingRequest {
    private List<EntityModelRequest> entities = new ArrayList<>();

    public List<EntityModelRequest> getEntities() {
        return entities;
    }

    public void setEntities(List<EntityModelRequest> entities) {
        this.entities = entities;
    }
}

