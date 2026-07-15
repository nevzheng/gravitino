/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.rest.v1.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.JsonNodeDeserializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Deserializes the V1 Iceberg expression wire grammar.
 *
 * <p>The grammar deliberately mixes raw JSON literal values with tagged expression objects. A
 * Jackson polymorphic type discriminator cannot represent that union: raw literals have no type
 * field and a raw JSON {@code null} must remain an expression rather than becoming a Java null.
 * This deserializer preserves that distinction and constructs {@link LiteralValue} for every raw
 * literal form.
 */
public final class ExpressionWireDeserializer extends JsonDeserializer<ValueExpression> {

  private static final int MAX_APPLY_ARGUMENTS = 1_024;

  /** Deserializes one V1 value expression. */
  @Override
  public ValueExpression deserialize(JsonParser parser, DeserializationContext context)
      throws IOException {
    return parseValue(readNode(parser, context), parser, context);
  }

  /**
   * Treats a raw JSON null as an explicit literal expression, not an absent Java value.
   *
   * @param context Jackson deserialization context.
   * @return an explicit null literal.
   */
  @Override
  public ValueExpression getNullValue(DeserializationContext context) {
    return new LiteralValue(null);
  }

  /**
   * Keeps an absent optional expression member distinct from an explicit JSON {@code null}.
   *
   * <p>Jackson's default absent-value implementation delegates to {@link #getNullValue}, which
   * would turn an omitted {@code defaultValue} into a literal-null default. V1 needs the usual JSON
   * distinction: an absent member means no default, while an explicit JSON {@code null} means a
   * literal-null default.
   *
   * @param context Jackson deserialization context.
   * @return {@code null} when the enclosing optional member is absent.
   */
  @Override
  public ValueExpression getAbsentValue(DeserializationContext context) {
    return null;
  }

  /**
   * Deserializes a node admitted in an {@code ApplyExpression.arguments} array.
   *
   * <p>A bare JSON boolean is parsed as a raw {@link LiteralValue}. Predicate constants remain
   * available as children of an object-form predicate; this precedence makes all raw literal scalar
   * forms usable consistently in value-expression positions.
   */
  public static final class ExpressionNodeDeserializer extends JsonDeserializer<ExpressionNode> {

    /** Deserializes one V1 expression argument. */
    @Override
    public ExpressionNode deserialize(JsonParser parser, DeserializationContext context)
        throws IOException {
      return parseExpressionNode(readNode(parser, context), parser, context);
    }

    /**
     * Treats a raw JSON null as an explicit literal expression, not an absent Java value.
     *
     * @param context Jackson deserialization context.
     * @return an explicit null literal.
     */
    @Override
    public ExpressionNode getNullValue(DeserializationContext context) {
      return new LiteralValue(null);
    }

    /**
     * Keeps an absent optional expression-node member distinct from an explicit JSON {@code null}.
     *
     * @param context Jackson deserialization context.
     * @return {@code null} when the enclosing optional member is absent.
     */
    @Override
    public ExpressionNode getAbsentValue(DeserializationContext context) {
      return null;
    }
  }

  private static ValueExpression parseValue(
      JsonNode value, JsonParser parser, DeserializationContext context) throws IOException {
    try {
      if (value == null || value.isNull() || !value.isObject()) {
        return new LiteralValue(rawJsonValue(value, parser));
      }

      JsonNode type = value.get("type");
      if (type == null) {
        return new LiteralValue(rawJsonValue(value, parser));
      }
      if (!type.isTextual()) {
        throw invalid(parser, "The V1 expression type must be a string.");
      }

      switch (type.textValue()) {
        case "literal":
          return parseLiteral(value, parser);
        case "reference":
          return parseReference(value, parser);
        case "apply":
          return parseApply(value, parser, context);
        default:
          throw invalid(parser, "Unsupported V1 value-expression type: " + type.textValue());
      }
    } catch (IllegalArgumentException exception) {
      throw invalid(parser, "Invalid V1 value expression: " + exception.getMessage(), exception);
    }
  }

  private static ExpressionNode parseExpressionNode(
      JsonNode value, JsonParser parser, DeserializationContext context) throws IOException {
    try {
      if (value != null && value.isObject()) {
        JsonNode type = value.get("type");
        if (type != null && type.isTextual() && isPredicateType(type.textValue())) {
          return parsePredicate(value, parser, context);
        }
      }
      return parseValue(value, parser, context);
    } catch (IllegalArgumentException exception) {
      throw invalid(parser, "Invalid V1 expression argument: " + exception.getMessage(), exception);
    }
  }

  private static Expression.Literal parseLiteral(JsonNode value, JsonParser parser)
      throws IOException {
    requireOnlyFields(value, parser, "literal expression", "type", "value", "data-type");
    JsonNode literalValue = requiredField(value, parser, "value", "literal expression");
    JsonNode dataType = value.get("data-type");
    return new Expression.Literal(
        rawJsonValue(literalValue, parser),
        dataType == null ? null : parseDataType(dataType, parser));
  }

  private static Expression.Reference parseReference(JsonNode value, JsonParser parser)
      throws JsonMappingException {
    requireOnlyFields(value, parser, "reference expression", "type", "id", "name");
    JsonNode id = value.get("id");
    JsonNode name = value.get("name");
    if ((id == null) == (name == null)) {
      throw invalid(parser, "A reference expression must contain exactly one of id and name.");
    }
    if (id != null) {
      if (!id.isIntegralNumber() || !id.canConvertToInt()) {
        throw invalid(parser, "A reference expression id must be a 32-bit integer.");
      }
      return new Expression.Reference(id.intValue(), null);
    }
    if (!name.isTextual()) {
      throw invalid(parser, "A reference expression name must be a string.");
    }
    return new Expression.Reference(null, name.textValue());
  }

  private static Expression.Apply parseApply(
      JsonNode value, JsonParser parser, DeserializationContext context) throws IOException {
    requireOnlyFields(value, parser, "apply expression", "type", "function", "arguments");
    JsonNode function = requiredField(value, parser, "function", "apply expression");
    JsonNode arguments = requiredField(value, parser, "arguments", "apply expression");
    if (!arguments.isArray()) {
      throw invalid(parser, "An apply expression arguments field must be an array.");
    }
    if (arguments.size() > MAX_APPLY_ARGUMENTS) {
      throw invalid(
          parser,
          "An apply expression cannot contain more than " + MAX_APPLY_ARGUMENTS + " arguments.");
    }

    List<ExpressionNode> parsedArguments = new ArrayList<>(arguments.size());
    for (int index = 0; index < arguments.size(); index++) {
      parsedArguments.add(parseExpressionNode(arguments.get(index), parser, context));
    }
    return new Expression.Apply(parseFunctionReference(function, parser), parsedArguments);
  }

  private static Predicate parsePredicate(
      JsonNode value, JsonParser parser, DeserializationContext context) throws IOException {
    String type = expressionType(value, parser, "predicate");
    switch (type) {
      case "not":
        requireOnlyFields(value, parser, "not predicate", "type", "child");
        return new Predicate.Not(
            parsePredicateChild(
                requiredField(value, parser, "child", "not predicate"), parser, context));
      case "and":
      case "or":
        requireOnlyFields(value, parser, "logical predicate", "type", "left", "right");
        return new Predicate.Logical(
            "and".equals(type) ? Predicate.LogicalOperator.AND : Predicate.LogicalOperator.OR,
            parsePredicateChild(
                requiredField(value, parser, "left", "logical predicate"), parser, context),
            parsePredicateChild(
                requiredField(value, parser, "right", "logical predicate"), parser, context));
      case "is-null":
      case "not-null":
      case "is-nan":
      case "not-nan":
        requireOnlyFields(value, parser, "unary predicate", "type", "child");
        return new Predicate.UnaryTest(
            unaryOperator(type),
            parseValue(requiredField(value, parser, "child", "unary predicate"), parser, context));
      case "lt":
      case "lt-eq":
      case "gt":
      case "gt-eq":
      case "eq":
      case "not-eq":
      case "starts-with":
      case "not-starts-with":
        requireOnlyFields(value, parser, "comparison predicate", "type", "left", "right");
        return new Predicate.Comparison(
            comparisonOperator(type),
            parseValue(
                requiredField(value, parser, "left", "comparison predicate"), parser, context),
            parseValue(
                requiredField(value, parser, "right", "comparison predicate"), parser, context));
      case "in":
      case "not-in":
        requireOnlyFields(value, parser, "set predicate", "type", "child", "values");
        return new Predicate.SetTest(
            "in".equals(type) ? Predicate.SetOperator.IN : Predicate.SetOperator.NOT_IN,
            parseValue(requiredField(value, parser, "child", "set predicate"), parser, context),
            parseLiteralCollection(
                requiredField(value, parser, "values", "set predicate"), parser, context));
      default:
        throw invalid(parser, "Unsupported V1 predicate type: " + type);
    }
  }

  private static Predicate parsePredicateChild(
      JsonNode value, JsonParser parser, DeserializationContext context) throws IOException {
    if (value != null && value.isBoolean()) {
      return new Predicate.Constant(value.booleanValue());
    }
    if (value == null || !value.isObject()) {
      throw invalid(parser, "A predicate child must be a boolean or predicate object.");
    }
    String type = expressionType(value, parser, "predicate child");
    if (!isPredicateType(type)) {
      throw invalid(parser, "A predicate child must use a predicate type.");
    }
    return parsePredicate(value, parser, context);
  }

  private static Predicate.LiteralCollection parseLiteralCollection(
      JsonNode value, JsonParser parser, DeserializationContext context) throws IOException {
    if (value.isArray()) {
      List<ValueExpression> values = new ArrayList<>(value.size());
      for (int index = 0; index < value.size(); index++) {
        values.add(parseValue(value.get(index), parser, context));
      }
      return new Predicate.LiteralList(values);
    }
    if (!value.isObject() || !"literals".equals(expressionType(value, parser, "literal set"))) {
      throw invalid(
          parser, "A set predicate values field must be a literal array or literals object.");
    }
    requireOnlyFields(value, parser, "literals expression", "type", "values", "data-type");
    JsonNode values = requiredField(value, parser, "values", "literals expression");
    if (!values.isArray()) {
      throw invalid(parser, "A literals expression values field must be an array.");
    }
    List<Object> parsedValues = new ArrayList<>(values.size());
    for (int index = 0; index < values.size(); index++) {
      parsedValues.add(rawJsonValue(values.get(index), parser));
    }
    return new Predicate.LiteralSet(
        parsedValues,
        parseDataType(requiredField(value, parser, "data-type", "literals expression"), parser));
  }

  private static Expression.FunctionReference parseFunctionReference(
      JsonNode value, JsonParser parser) throws IOException {
    return Expression.FunctionReference.fromJson(rawJsonValue(value, parser));
  }

  private static DataType parseDataType(JsonNode value, JsonParser parser) throws IOException {
    if (!value.isObject()) {
      throw invalid(parser, "An expression data-type must be an object.");
    }
    DataType dataType = parser.getCodec().treeToValue(value, DataType.class);
    if (dataType == null) {
      throw invalid(parser, "An expression data-type cannot be null.");
    }
    return dataType;
  }

  private static Object rawJsonValue(JsonNode value, JsonParser parser) throws IOException {
    if (value == null || value.isNull()) {
      return null;
    }
    return parser.getCodec().treeToValue(value, Object.class);
  }

  private static JsonNode readNode(JsonParser parser, DeserializationContext context)
      throws IOException {
    // Do not use JsonParser.readValueAsTree(): V1 enables FAIL_ON_TRAILING_TOKENS, and that
    // convenience method would treat the enclosing request object's remaining fields as trailing.
    return JsonNodeDeserializer.getDeserializer(JsonNode.class).deserialize(parser, context);
  }

  private static String expressionType(JsonNode value, JsonParser parser, String subject)
      throws JsonMappingException {
    JsonNode type = requiredField(value, parser, "type", subject);
    if (!type.isTextual()) {
      throw invalid(parser, "The " + subject + " type must be a string.");
    }
    return type.textValue();
  }

  private static JsonNode requiredField(
      JsonNode value, JsonParser parser, String field, String subject) throws JsonMappingException {
    JsonNode result = value.get(field);
    if (result == null) {
      throw invalid(parser, "The " + subject + " requires field '" + field + "'.");
    }
    return result;
  }

  private static void requireOnlyFields(
      JsonNode value, JsonParser parser, String subject, String... allowedFields)
      throws JsonMappingException {
    Iterator<String> fieldNames = value.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      if (!isAllowedField(fieldName, allowedFields)) {
        throw invalid(parser, "Unknown field '" + fieldName + "' in " + subject + ".");
      }
    }
  }

  private static boolean isAllowedField(String field, String... allowedFields) {
    for (String allowedField : allowedFields) {
      if (allowedField.equals(field)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isPredicateType(String type) {
    switch (type) {
      case "not":
      case "and":
      case "or":
      case "is-null":
      case "not-null":
      case "is-nan":
      case "not-nan":
      case "lt":
      case "lt-eq":
      case "gt":
      case "gt-eq":
      case "eq":
      case "not-eq":
      case "starts-with":
      case "not-starts-with":
      case "in":
      case "not-in":
        return true;
      default:
        return false;
    }
  }

  private static Predicate.UnaryOperator unaryOperator(String type) {
    switch (type) {
      case "is-null":
        return Predicate.UnaryOperator.IS_NULL;
      case "not-null":
        return Predicate.UnaryOperator.NOT_NULL;
      case "is-nan":
        return Predicate.UnaryOperator.IS_NAN;
      case "not-nan":
        return Predicate.UnaryOperator.NOT_NAN;
      default:
        throw new IllegalArgumentException("Unsupported unary predicate type: " + type);
    }
  }

  private static Predicate.ComparisonOperator comparisonOperator(String type) {
    switch (type) {
      case "lt":
        return Predicate.ComparisonOperator.LT;
      case "lt-eq":
        return Predicate.ComparisonOperator.LT_EQ;
      case "gt":
        return Predicate.ComparisonOperator.GT;
      case "gt-eq":
        return Predicate.ComparisonOperator.GT_EQ;
      case "eq":
        return Predicate.ComparisonOperator.EQ;
      case "not-eq":
        return Predicate.ComparisonOperator.NOT_EQ;
      case "starts-with":
        return Predicate.ComparisonOperator.STARTS_WITH;
      case "not-starts-with":
        return Predicate.ComparisonOperator.NOT_STARTS_WITH;
      default:
        throw new IllegalArgumentException("Unsupported comparison predicate type: " + type);
    }
  }

  private static JsonMappingException invalid(JsonParser parser, String message) {
    return JsonMappingException.from(parser, message);
  }

  private static JsonMappingException invalid(
      JsonParser parser, String message, IllegalArgumentException cause) {
    JsonMappingException exception = invalid(parser, message);
    exception.initCause(cause);
    return exception;
  }
}
