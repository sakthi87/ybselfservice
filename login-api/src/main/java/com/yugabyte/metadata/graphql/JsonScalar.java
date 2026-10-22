package com.yugabyte.metadata.graphql;

import graphql.GraphQLContext;
import graphql.execution.CoercedVariables;
import graphql.language.ArrayValue;
import graphql.language.BooleanValue;
import graphql.language.EnumValue;
import graphql.language.FloatValue;
import graphql.language.IntValue;
import graphql.language.NullValue;
import graphql.language.ObjectField;
import graphql.language.ObjectValue;
import graphql.language.StringValue;
import graphql.language.Value;
import graphql.schema.Coercing;
import graphql.schema.CoercingParseLiteralException;
import graphql.schema.CoercingParseValueException;
import graphql.schema.CoercingSerializeException;
import graphql.schema.GraphQLScalarType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

final class JsonScalar {
  static final GraphQLScalarType TYPE = GraphQLScalarType.newScalar()
      .name("JSON")
      .description("A JSON object, list, or value.")
      .coercing(new Coercing<Object, Object>() {
        @Override
        public Object serialize(Object dataFetcherResult, GraphQLContext graphQLContext, Locale locale) {
          if (dataFetcherResult == null) {
            return null;
          }
          return dataFetcherResult;
        }

        @Override
        public Object parseValue(Object input, GraphQLContext graphQLContext, Locale locale) {
          return input;
        }

        @Override
        public Object parseLiteral(Value<?> input, CoercedVariables variables, GraphQLContext graphQLContext, Locale locale)
            throws CoercingParseLiteralException {
          return literal(input);
        }
      })
      .build();

  private JsonScalar() {}

  private static Object literal(Value<?> input) {
    if (input instanceof NullValue) {
      return null;
    }
    if (input instanceof StringValue value) {
      return value.getValue();
    }
    if (input instanceof BooleanValue value) {
      return value.isValue();
    }
    if (input instanceof IntValue value) {
      return value.getValue();
    }
    if (input instanceof FloatValue value) {
      return value.getValue();
    }
    if (input instanceof EnumValue value) {
      return value.getName();
    }
    if (input instanceof ArrayValue value) {
      List<Object> items = new ArrayList<>();
      for (Value<?> item : value.getValues()) {
        items.add(literal(item));
      }
      return items;
    }
    if (input instanceof ObjectValue value) {
      Map<String, Object> object = new LinkedHashMap<>();
      for (ObjectField field : value.getObjectFields()) {
        object.put(field.getName(), literal(field.getValue()));
      }
      return object;
    }
    throw new CoercingParseLiteralException("JSON literal is not supported");
  }
}
