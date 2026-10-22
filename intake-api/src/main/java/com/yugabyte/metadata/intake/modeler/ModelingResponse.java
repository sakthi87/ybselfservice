package com.yugabyte.metadata.intake.modeler;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ModelingResponse {
    private List<EntityModelResponse> entities = new ArrayList<>();

    public List<EntityModelResponse> getEntities() {
        return entities;
    }

    public void setEntities(List<EntityModelResponse> entities) {
        this.entities = entities;
    }
}

