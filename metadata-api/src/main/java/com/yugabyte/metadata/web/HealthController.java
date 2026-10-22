package com.yugabyte.metadata.web;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
  private final String api;

  public HealthController(@Value("${platform.api:catalog}") String api) {
    this.api = api;
  }

  @GetMapping("/api/v1/health")
  Map<String, Object> health() {
    return Map.of("status", "UP", "api", api);
  }
}
