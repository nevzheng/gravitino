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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

public class TestExpressionModel {

  private final ObjectMapper objectMapper = JsonMapper.builder().build();

  @Test
  public void testValueExpressionGrammar() throws Exception {
    assertEquals("34", objectMapper.writeValueAsString(new LiteralValue(34)));
    assertEquals("null", objectMapper.writeValueAsString(new LiteralValue(null)));

    Expression.Literal nullLiteral = new Expression.Literal(null, new DataType.NullType());
    JsonNode literalJson = objectMapper.valueToTree(nullLiteral);
    assertEquals("literal", literalJson.get("type").asText());
    assertTrue(literalJson.has("value"));
    assertTrue(literalJson.get("value").isNull());
    assertEquals("NULL", literalJson.get("data-type").get("kind").asText());

    assertEquals(
        "reference", objectMapper.valueToTree(Expression.Reference.bound(7)).get("type").asText());
    assertEquals(7, objectMapper.valueToTree(Expression.Reference.bound(7)).get("id").asInt());
    assertEquals(
        "orders",
        objectMapper.valueToTree(Expression.Reference.named("orders")).get("name").asText());

    Expression.Apply apply =
        new Expression.Apply(
            Expression.FunctionReference.qualified(
                "iceberg_functions", Collections.singletonList("bucket")),
            Arrays.asList(
                new Expression.Literal(16, new DataType.IntegerType(true)),
                Expression.Reference.named("id")));
    JsonNode applyJson = objectMapper.valueToTree(apply);
    assertEquals("apply", applyJson.get("type").asText());
    assertEquals("iceberg_functions", applyJson.get("function").get("catalog").asText());
    assertEquals("bucket", applyJson.get("function").get("identifier").get(0).asText());
    assertEquals(2, applyJson.get("arguments").size());
  }

  @Test
  public void testDeserializesRawAndTaggedValueExpressions() throws Exception {
    ValueExpression rawString = objectMapper.readValue("\"orders\"", ValueExpression.class);
    assertTrue(rawString instanceof LiteralValue);
    assertEquals("orders", ((LiteralValue) rawString).getValue());

    ValueExpression rawNull = objectMapper.readValue("null", ValueExpression.class);
    assertNotNull(rawNull);
    assertTrue(rawNull instanceof LiteralValue);
    assertNull(((LiteralValue) rawNull).getValue());

    ValueExpression rawList = objectMapper.readValue("[1,null,\"east\"]", ValueExpression.class);
    assertTrue(rawList instanceof LiteralValue);
    @SuppressWarnings("unchecked")
    List<Object> list = (List<Object>) ((LiteralValue) rawList).getValue();
    assertEquals(3, list.size());
    assertEquals(1, list.get(0));
    assertNull(list.get(1));
    assertEquals("east", list.get(2));

    ValueExpression rawStruct =
        objectMapper.readValue("{\"2\":\"west\",\"1\":7}", ValueExpression.class);
    assertTrue(rawStruct instanceof LiteralValue);
    @SuppressWarnings("unchecked")
    Map<String, Object> struct = (Map<String, Object>) ((LiteralValue) rawStruct).getValue();
    assertEquals(7, struct.get("1"));
    assertEquals("west", struct.get("2"));

    ValueExpression literal =
        objectMapper.readValue(
            "{\"type\":\"literal\",\"value\":7,\"data-type\":{\"kind\":\"INTEGER\","
                + "\"signed\":true}}",
            ValueExpression.class);
    assertTrue(literal instanceof Expression.Literal);
    Expression.Literal typedLiteral = (Expression.Literal) literal;
    assertEquals(7, typedLiteral.getValue());
    assertEquals(DataType.Kind.INTEGER, typedLiteral.getDataType().getKind());

    ValueExpression reference =
        objectMapper.readValue(
            "{\"type\":\"reference\",\"name\":\"order_id\"}", ValueExpression.class);
    assertTrue(reference instanceof Expression.Reference);
    assertEquals("order_id", ((Expression.Reference) reference).getName());

    ValueExpression apply =
        objectMapper.readValue(
            "{\"type\":\"apply\",\"function\":\"coalesce\",\"arguments\":[null,"
                + "{\"type\":\"reference\",\"name\":\"region\"},"
                + "{\"type\":\"literal\",\"value\":\"unknown\"},"
                + "{\"type\":\"eq\",\"left\":{\"type\":\"reference\",\"name\":\"enabled\"},"
                + "\"right\":true}]}",
            ValueExpression.class);
    assertTrue(apply instanceof Expression.Apply);
    Expression.Apply parsedApply = (Expression.Apply) apply;
    assertEquals("coalesce", parsedApply.getFunction().getIdentifier().get(0));
    assertEquals(4, parsedApply.getArguments().size());
    assertTrue(parsedApply.getArguments().get(0) instanceof LiteralValue);
    assertNull(((LiteralValue) parsedApply.getArguments().get(0)).getValue());
    assertTrue(parsedApply.getArguments().get(1) instanceof Expression.Reference);
    assertTrue(parsedApply.getArguments().get(2) instanceof Expression.Literal);
    assertTrue(parsedApply.getArguments().get(3) instanceof Predicate.Comparison);
    Predicate.Comparison comparison = (Predicate.Comparison) parsedApply.getArguments().get(3);
    assertTrue(comparison.getRight() instanceof LiteralValue);
    assertEquals(true, ((LiteralValue) comparison.getRight()).getValue());
  }

  @Test
  public void testBindsValueExpressionsInsideTableRequestModels() throws Exception {
    String json =
        "{"
            + "\"name\":\"orders\","
            + "\"columns\":["
            + "{\"name\":\"id\",\"type\":{\"kind\":\"INTEGER\",\"signed\":true},"
            + "\"nullable\":false,\"autoIncrement\":false,\"defaultValue\":null},"
            + "{\"name\":\"priority\",\"type\":{\"kind\":\"INTEGER\",\"signed\":true},"
            + "\"nullable\":false,\"autoIncrement\":false,\"defaultValue\":{\"type\":\"literal\","
            + "\"value\":3,\"data-type\":{\"kind\":\"INTEGER\",\"signed\":true}}}],"
            + "\"partitioning\":[],"
            + "\"distribution\":{\"strategy\":\"HASH\",\"expressions\":[{\"type\":\"reference\","
            + "\"name\":\"id\"}]},"
            + "\"sortOrders\":[{\"expression\":{\"type\":\"reference\",\"name\":\"priority\"},"
            + "\"direction\":\"ASC\",\"nullOrdering\":\"NULLS_LAST\"}],"
            + "\"indexes\":[]"
            + "}";

    TableCreateRequest request = objectMapper.readValue(json, TableCreateRequest.class);
    assertTrue(request.getColumns().get(0).getDefaultValue() instanceof LiteralValue);
    assertNull(((LiteralValue) request.getColumns().get(0).getDefaultValue()).getValue());
    assertTrue(request.getColumns().get(1).getDefaultValue() instanceof Expression.Literal);
    assertEquals(
        3, ((Expression.Literal) request.getColumns().get(1).getDefaultValue()).getValue());
    assertTrue(request.getDistribution().getExpressions().get(0) instanceof Expression.Reference);
    assertEquals(
        "id", ((Expression.Reference) request.getDistribution().getExpressions().get(0)).getName());
    assertTrue(request.getSortOrders().get(0).getExpression() instanceof Expression.Reference);
    assertEquals(
        "priority",
        ((Expression.Reference) request.getSortOrders().get(0).getExpression()).getName());
  }

  @Test
  public void testStructuredIcebergSingleValues() throws Exception {
    Map<String, Object> struct = new LinkedHashMap<>();
    struct.put("2", "bar");
    struct.put("1", 34);
    struct.put("3", null);

    List<Object> list = new ArrayList<>();
    list.add(struct);
    list.add(Arrays.asList(true, null));

    Map<String, Object> map = new LinkedHashMap<>();
    map.put("keys", Arrays.asList("a", "b"));
    map.put("values", Arrays.asList(list, null));

    LiteralValue shorthand = new LiteralValue(map);
    JsonNode shorthandJson = objectMapper.valueToTree(shorthand);
    assertEquals("a", shorthandJson.get("keys").get(0).asText());
    assertEquals(34, shorthandJson.get("values").get(0).get(0).get("1").asInt());
    assertTrue(shorthandJson.get("values").get(1).isNull());

    Expression.Literal canonical =
        new Expression.Literal(
            list,
            new DataType.ListType(
                new DataType.StructType(
                    Arrays.asList(
                        new DataType.StructField(
                            "number", new DataType.IntegerType(true), false, null),
                        new DataType.StructField("text", new DataType.StringType(), true, null))),
                true));
    JsonNode canonicalJson = objectMapper.valueToTree(canonical);
    assertEquals("literal", canonicalJson.get("type").asText());
    assertEquals(34, canonicalJson.get("value").get(0).get("1").asInt());
    assertEquals("LIST", canonicalJson.get("data-type").get("kind").asText());

    struct.put("1", 999);
    list.clear();
    map.clear();
    assertEquals("a", objectMapper.valueToTree(shorthand).get("keys").get(0).asText());
    assertEquals(34, objectMapper.valueToTree(canonical).get("value").get(0).get("1").asInt());

    @SuppressWarnings("unchecked")
    Map<String, Object> immutableMap = (Map<String, Object>) shorthand.getValue();
    assertThrows(UnsupportedOperationException.class, () -> immutableMap.put("1", "mutation"));
  }

  @Test
  public void testAllFunctionReferenceForms() throws Exception {
    assertEquals(
        "\"identity\"",
        objectMapper.writeValueAsString(Expression.FunctionReference.named("identity")));
    assertEquals(
        "[\"ns\",\"func\"]",
        objectMapper.writeValueAsString(
            Expression.FunctionReference.identified(Arrays.asList("ns", "func"))));
    assertEquals(
        "{\"identifier\":[\"ns\",\"func\"]}",
        objectMapper.writeValueAsString(
            Expression.FunctionReference.qualified(null, Arrays.asList("ns", "func"))));
    assertEquals(
        "{\"catalog\":\"catalog\",\"identifier\":[\"func\"]}",
        objectMapper.writeValueAsString(
            Expression.FunctionReference.qualified("catalog", Collections.singletonList("func"))));
  }

  @Test
  public void testCompletePredicateGrammar() {
    Expression.Reference ref = Expression.Reference.named("value");
    Expression.Literal literal = new Expression.Literal(10, new DataType.IntegerType(true));

    for (Predicate.UnaryOperator operator : Predicate.UnaryOperator.values()) {
      JsonNode json = objectMapper.valueToTree(new Predicate.UnaryTest(operator, ref));
      assertEquals(operator.value(), json.get("type").asText());
      assertEquals("reference", json.get("child").get("type").asText());
    }

    for (Predicate.ComparisonOperator operator : Predicate.ComparisonOperator.values()) {
      JsonNode json = objectMapper.valueToTree(new Predicate.Comparison(operator, ref, literal));
      assertEquals(operator.value(), json.get("type").asText());
      assertEquals("reference", json.get("left").get("type").asText());
      assertEquals("literal", json.get("right").get("type").asText());
    }

    Predicate.LiteralSet values =
        new Predicate.LiteralSet(Arrays.asList(1, 2, 3), new DataType.IntegerType(true));
    for (Predicate.SetOperator operator : Predicate.SetOperator.values()) {
      JsonNode json = objectMapper.valueToTree(new Predicate.SetTest(operator, ref, values));
      assertEquals(operator.value(), json.get("type").asText());
      assertEquals("literals", json.get("values").get("type").asText());
      assertEquals(3, json.get("values").get("values").size());
    }
    Map<String, Object> structValue = new LinkedHashMap<>();
    structValue.put("1", 10);
    Predicate.LiteralSet structuredValues =
        new Predicate.LiteralSet(
            Collections.singletonList(structValue),
            new DataType.StructType(
                Collections.singletonList(
                    new DataType.StructField(
                        "value", new DataType.IntegerType(true), false, null))));
    assertEquals(
        10, objectMapper.valueToTree(structuredValues).get("values").get(0).get("1").asInt());
    Predicate.LiteralList shorthandValues =
        new Predicate.LiteralList(
            Arrays.asList(
                new LiteralValue(1), new Expression.Literal(2, new DataType.IntegerType(true))));
    JsonNode shorthandJson =
        objectMapper.valueToTree(
            new Predicate.SetTest(Predicate.SetOperator.IN, ref, shorthandValues));
    assertTrue(shorthandJson.get("values").isArray());
    assertEquals(1, shorthandJson.get("values").get(0).asInt());
    assertEquals("literal", shorthandJson.get("values").get(1).get("type").asText());

    Predicate.Constant truePredicate = new Predicate.Constant(true);
    Predicate.Constant falsePredicate = new Predicate.Constant(false);
    assertEquals("true", objectMapper.valueToTree(truePredicate).toString());
    assertEquals("false", objectMapper.valueToTree(falsePredicate).toString());
    assertEquals(
        "not", objectMapper.valueToTree(new Predicate.Not(falsePredicate)).get("type").asText());

    for (Predicate.LogicalOperator operator : Predicate.LogicalOperator.values()) {
      JsonNode json =
          objectMapper.valueToTree(new Predicate.Logical(operator, truePredicate, falsePredicate));
      assertEquals(operator.value(), json.get("type").asText());
      assertTrue(json.get("left").asBoolean());
      assertFalse(json.get("right").asBoolean());
    }
  }

  @Test
  public void testStrictExpressionValidation() {
    assertThrows(IllegalArgumentException.class, () -> new Expression.Reference(null, null));
    assertThrows(IllegalArgumentException.class, () -> new Expression.Reference(1, "name"));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Expression.Literal(null, new DataType.IntegerType(true)));
    assertThrows(
        IllegalArgumentException.class,
        () -> new Predicate.LiteralSet(Collections.singletonList(Double.NaN), null));
    assertThrows(
        IllegalArgumentException.class,
        () -> Expression.FunctionReference.fromJson(Collections.singletonMap("unknown", "x")));

    Map<String, Object> invalidStruct = new LinkedHashMap<>();
    invalidStruct.put("field-name", 1);
    assertThrows(IllegalArgumentException.class, () -> new LiteralValue(invalidStruct));
    invalidStruct.clear();
    invalidStruct.put("2147483648", 1);
    assertThrows(IllegalArgumentException.class, () -> new LiteralValue(invalidStruct));

    Map<String, Object> mismatchedMap = new LinkedHashMap<>();
    mismatchedMap.put("keys", Arrays.asList("a", "b"));
    mismatchedMap.put("values", Collections.singletonList(1));
    assertThrows(IllegalArgumentException.class, () -> new LiteralValue(mismatchedMap));

    Map<String, Object> nullKeyMap = new LinkedHashMap<>();
    nullKeyMap.put("keys", Collections.singletonList(null));
    nullKeyMap.put("values", Collections.singletonList(1));
    assertThrows(IllegalArgumentException.class, () -> new LiteralValue(nullKeyMap));

    assertThrows(
        IllegalArgumentException.class,
        () -> new LiteralValue(Collections.singletonList(Double.POSITIVE_INFINITY)));
    assertThrows(
        IllegalArgumentException.class, () -> new LiteralValue(Collections.nCopies(10_001, 1)));
  }
}
