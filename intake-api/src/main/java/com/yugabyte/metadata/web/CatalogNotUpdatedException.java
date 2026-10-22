package com.yugabyte.metadata.web;

public class CatalogNotUpdatedException extends RuntimeException {
  public CatalogNotUpdatedException(String message) {
    super(message);
  }
}
