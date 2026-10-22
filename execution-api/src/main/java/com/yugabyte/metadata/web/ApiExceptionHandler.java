package com.yugabyte.metadata.web;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {
  @ExceptionHandler(NotFoundException.class)
  ResponseEntity<Map<String, String>> notFound(NotFoundException exception) {
    return ResponseEntity.status(404).body(Map.of("error", exception.getMessage()));
  }

  @ExceptionHandler(BadRequestException.class)
  ResponseEntity<Map<String, String>> badRequest(BadRequestException exception) {
    return ResponseEntity.badRequest().body(Map.of("error", exception.getMessage()));
  }

  @ExceptionHandler(CatalogNotUpdatedException.class)
  ResponseEntity<Map<String, Object>> catalogNotUpdated(CatalogNotUpdatedException exception) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("error", exception.getMessage());
    body.put("databaseApplied", true);
    body.put("catalogUpdated", false);
    return ResponseEntity.status(409).body(body);
  }

  @ExceptionHandler(Exception.class)
  ResponseEntity<Map<String, String>> unexpected(Exception exception) {
    exception.printStackTrace();
    return ResponseEntity.internalServerError().body(Map.of("error", "Internal server error"));
  }
}
