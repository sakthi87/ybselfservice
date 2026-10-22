package com.yugabyte.metadata.platform;

import com.yugabyte.metadata.web.BadRequestException;
import com.yugabyte.metadata.web.CatalogNotUpdatedException;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class ServiceClient {
  private static final ParameterizedTypeReference<Map<String, Object>> OBJECT = new ParameterizedTypeReference<>() {};

  private final RestClient login;
  private final RestClient execution;
  private final RestClient catalog;

  public ServiceClient(
      @Value("${login.base-url:http://localhost:4010}") String loginUrl,
      @Value("${execution.base-url:http://localhost:4030}") String executionUrl,
      @Value("${catalog.base-url:http://localhost:4000}") String catalogUrl,
      @Value("${spring.security.user.name}") String username,
      @Value("${spring.security.user.password}") String password) {
    this.login = client(loginUrl, username, password);
    this.execution = client(executionUrl, username, password);
    this.catalog = client(catalogUrl, username, password);
  }

  public Map<String, Object> signIn(Map<String, Object> body) {
    return mutation(login, """
        mutation($userId: String!, $password: String!) {
          result: signIn(userId: $userId, password: $password) { id userId subjectId }
        }
        """, Map.of("userId", String.valueOf(body.get("userId")), "password", String.valueOf(body.get("password"))));
  }

  public Map<String, Object> ensureUser(String userId) {
    return mutation(login, """
        mutation($userId: String!) {
          result: ensureUser(userId: $userId) { id userId subjectId }
        }
        """, Map.of("userId", userId));
  }

  public Map<String, Object> execution(String field, Map<String, Object> input) {
    return mutation(execution, "mutation($input: JSON!) { result: " + field + "(input: $input) }", Map.of("input", input));
  }

  public Map<String, Object> catalog(String field, Map<String, Object> input) {
    return mutation(catalog, "mutation($input: JSON!) { result: " + field + "(input: $input) }", Map.of("input", input));
  }

  private static RestClient client(String baseUrl, String username, String password) {
    return RestClient.builder().baseUrl(baseUrl).defaultHeaders(headers -> headers.setBasicAuth(username, password)).build();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> mutation(RestClient client, String query, Map<String, Object> variables) {
    Object result = graphql(client, query, variables);
    if (result instanceof Map<?, ?> map) {
      return (Map<String, Object>) map;
    }
    throw new BadRequestException("The service did not return a result");
  }

  @SuppressWarnings("unchecked")
  private static Object graphql(RestClient client, String query, Map<String, Object> variables) {
    Map<String, Object> request = Map.of("query", query, "variables", variables);
    Map<String, Object> response;
    try {
      response = client.post().uri("/graphql").body(request).retrieve().body(OBJECT);
    } catch (RestClientResponseException exception) {
      throw new BadRequestException(message(exception));
    }
    if (response == null) {
      throw new BadRequestException("The service did not return a result");
    }
    Object errors = response.get("errors");
    if (errors instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Map<?, ?> error) {
      Map<String, Object> details = (Map<String, Object>) error;
      String message = details.get("message") == null ? "Request failed" : String.valueOf(details.get("message"));
      Object extensions = details.get("extensions");
      if (extensions instanceof Map<?, ?> extension
          && Boolean.TRUE.equals(extension.get("databaseApplied"))
          && Boolean.FALSE.equals(extension.get("catalogUpdated"))) {
        throw new CatalogNotUpdatedException(message);
      }
      throw new BadRequestException(message);
    }
    Object data = response.get("data");
    if (!(data instanceof Map<?, ?> payload)) {
      throw new BadRequestException("The service did not return a result");
    }
    return payload.get("result");
  }

  private static String message(RestClientResponseException exception) {
    try {
      Map<String, Object> body = exception.getResponseBodyAs(OBJECT);
      if (body != null && body.get("errors") instanceof List<?> errors && !errors.isEmpty() && errors.get(0) instanceof Map<?, ?> error) {
        Object message = error.get("message");
        if (message != null) {
          return String.valueOf(message);
        }
      }
    } catch (RuntimeException ignored) {
      return exception.getMessage();
    }
    return exception.getMessage();
  }
}
