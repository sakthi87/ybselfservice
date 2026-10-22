package com.yugabyte.metadata.graphql;

import com.yugabyte.metadata.web.BadRequestException;
import com.yugabyte.metadata.web.CatalogNotUpdatedException;
import com.yugabyte.metadata.web.NotFoundException;
import graphql.GraphQLError;
import graphql.GraphqlErrorBuilder;
import java.util.Map;
import org.springframework.graphql.data.method.annotation.GraphQlExceptionHandler;
import org.springframework.graphql.execution.ErrorType;
import org.springframework.web.bind.annotation.ControllerAdvice;

@ControllerAdvice
public class GraphQlExceptionAdvice {
  @GraphQlExceptionHandler
  GraphQLError badRequest(BadRequestException exception) {
    return GraphqlErrorBuilder.newError()
        .message(exception.getMessage())
        .errorType(ErrorType.BAD_REQUEST)
        .build();
  }

  @GraphQlExceptionHandler
  GraphQLError notFound(NotFoundException exception) {
    return GraphqlErrorBuilder.newError()
        .message(exception.getMessage())
        .errorType(ErrorType.NOT_FOUND)
        .build();
  }

  @GraphQlExceptionHandler
  GraphQLError catalogNotUpdated(CatalogNotUpdatedException exception) {
    return GraphqlErrorBuilder.newError()
        .message(exception.getMessage())
        .errorType(ErrorType.BAD_REQUEST)
        .extensions(Map.of("databaseApplied", true, "catalogUpdated", false))
        .build();
  }
}
