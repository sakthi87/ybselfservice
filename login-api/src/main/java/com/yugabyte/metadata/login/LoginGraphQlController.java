package com.yugabyte.metadata.login;

import java.util.Map;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.MutationMapping;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

@Controller
public class LoginGraphQlController {
  private final LoginService loginService;

  public LoginGraphQlController(LoginService loginService) {
    this.loginService = loginService;
  }

  @QueryMapping
  Map<String, Object> health() {
    return Map.of("status", "UP", "api", "login");
  }

  @MutationMapping
  Map<String, Object> signIn(@Argument String userId, @Argument String password) {
    return loginService.session(Map.of("userId", userId, "password", password));
  }

  @MutationMapping
  Map<String, Object> ensureUser(@Argument String userId) {
    return loginService.ensure(Map.of("userId", userId));
  }
}
