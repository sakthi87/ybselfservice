package com.yugabyte.metadata.execution;

import java.util.Map;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
public class ExecutionGraphQlController {
  private final ExecutionService executionService;

  public ExecutionGraphQlController(ExecutionService executionService) {
    this.executionService = executionService;
  }

  @QueryMapping
  Map<String, Object> health() {
    return Map.of("status", "UP", "api", "execution");
  }

  @MutationMapping
  Map<String, Object> createDatabase(@Argument Map<String, Object> input) {
    return executionService.createDatabase(input);
  }

  @MutationMapping
  Map<String, Object> createSchema(@Argument Map<String, Object> input) {
    return executionService.createSchema(input);
  }

  @MutationMapping
  Map<String, Object> executeDdl(@Argument Map<String, Object> input) {
    return executionService.executeDdl(input);
  }

  @MutationMapping
  Map<String, Object> design(@Argument Map<String, Object> input) {
    return executionService.design(input);
  }

  @MutationMapping
  Map<String, Object> addColumn(@Argument Map<String, Object> input) {
    return executionService.addColumn(input);
  }

  @MutationMapping
  Map<String, Object> addIndex(@Argument Map<String, Object> input) {
    return executionService.addIndex(input);
  }

  @MutationMapping
  Map<String, Object> promote(@Argument Map<String, Object> input) {
    return executionService.promote(input);
  }
}
