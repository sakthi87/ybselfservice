package com.yugabyte.metadata.intake;

import com.yugabyte.metadata.intake.modeler.ModelingRequest;
import java.util.Map;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import tools.jackson.databind.ObjectMapper;

@Controller
public class IntakeGraphQlController {
  private final IntakeService intakeService;
  private final ObjectMapper mapper;

  public IntakeGraphQlController(IntakeService intakeService, ObjectMapper mapper) {
    this.intakeService = intakeService;
    this.mapper = mapper;
  }

  @QueryMapping
  Map<String, Object> health() {
    return Map.of("status", "UP", "api", "intake");
  }

  @QueryMapping
  Object projects(@Argument String userId) {
    return intakeService.projects(userId);
  }

  @QueryMapping
  Object project(@Argument String projectId) {
    return intakeService.project(projectId);
  }

  @QueryMapping
  Object requests(@Argument String userId) {
    return intakeService.requests(userId);
  }

  @QueryMapping
  Object activity(@Argument String userId, @Argument String requestId, @Argument String action, @Argument String q) {
    return intakeService.activity(userId, requestId, action, q);
  }

  @QueryMapping
  Object openChanges(@Argument String projectId) {
    return intakeService.openChanges(projectId);
  }

  @MutationMapping
  Object register(@Argument Map<String, Object> input) {
    return intakeService.register(input);
  }

  @MutationMapping
  Object openChange(@Argument String projectId, @Argument Map<String, Object> input) {
    return intakeService.openChange(projectId, input);
  }

  @MutationMapping
  Object finishChange(@Argument String projectId, @Argument String requestId) {
    return intakeService.finishChange(projectId, requestId);
  }

  @MutationMapping
  boolean discardChange(@Argument String projectId, @Argument String requestId) {
    intakeService.discardChange(projectId, requestId);
    return true;
  }

  @MutationMapping
  Object promote(@Argument String projectId, @Argument Map<String, Object> input) {
    return intakeService.promote(projectId, input);
  }

  @MutationMapping
  Object createDatabase(@Argument String projectId, @Argument Map<String, Object> input) {
    return intakeService.createDatabase(projectId, input);
  }

  @MutationMapping
  Object createSchema(@Argument String databaseId, @Argument Map<String, Object> input) {
    return intakeService.createSchema(databaseId, input);
  }

  @MutationMapping
  Object generateModel(@Argument Map<String, Object> input) {
    return mapper.convertValue(intakeService.generate(mapper.convertValue(input, ModelingRequest.class)), Map.class);
  }

  @MutationMapping
  Object designTable(@Argument String schemaId, @Argument Map<String, Object> input) {
    return intakeService.createDesignedTable(schemaId, input);
  }

  @MutationMapping
  Object previewTable(@Argument String schemaId, @Argument Map<String, Object> input) {
    return intakeService.previewTable(schemaId, input);
  }

  @MutationMapping
  Object createTable(@Argument String schemaId, @Argument Map<String, Object> input) {
    return intakeService.createTable(schemaId, input);
  }

  @MutationMapping
  Object addColumn(@Argument String tableId, @Argument Map<String, Object> input) {
    return intakeService.addColumn(tableId, input);
  }

  @MutationMapping
  Object addIndex(@Argument String tableId, @Argument Map<String, Object> input) {
    return intakeService.addIndex(tableId, input);
  }

  @MutationMapping
  Object previewDdl(@Argument String schemaId, @Argument Map<String, Object> input) {
    return intakeService.previewDdl(schemaId, input);
  }

  @MutationMapping
  Object executeDdl(@Argument String schemaId, @Argument Map<String, Object> input) {
    return intakeService.executeDdl(schemaId, input);
  }

  @MutationMapping
  Object deploy(@Argument String databaseId, @Argument Map<String, Object> input) {
    return intakeService.deploy(databaseId, input);
  }
}
