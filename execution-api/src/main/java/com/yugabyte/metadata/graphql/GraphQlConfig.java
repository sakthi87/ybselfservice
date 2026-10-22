package com.yugabyte.metadata.graphql;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.graphql.execution.RuntimeWiringConfigurer;

@Configuration
public class GraphQlConfig {
  @Bean
  RuntimeWiringConfigurer jsonScalar() {
    return wiring -> wiring.scalar(JsonScalar.TYPE);
  }
}
