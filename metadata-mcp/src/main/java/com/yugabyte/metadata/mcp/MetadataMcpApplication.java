package com.yugabyte.metadata.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class MetadataMcpApplication {
  public static void main(String[] args) {
    SpringApplication.run(MetadataMcpApplication.class, args);
  }
}
