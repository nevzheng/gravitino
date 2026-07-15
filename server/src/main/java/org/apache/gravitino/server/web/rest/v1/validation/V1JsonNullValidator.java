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
package org.apache.gravitino.server.web.rest.v1.validation;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Map;
import org.apache.gravitino.rest.v1.model.CatalogUpdateRequest;
import org.apache.gravitino.rest.v1.model.MetalakeUpdateRequest;
import org.apache.gravitino.rest.v1.model.SchemaUpdateRequest;
import org.apache.gravitino.rest.v1.model.TableUpdateRequest;

/** Validates V1 JSON nulls that cannot be distinguished after normal Jackson binding. */
final class V1JsonNullValidator {

  private static final String COMMENT_FIELD = "comment";
  private static final String LITERAL_TYPE = "literal";
  private static final String TYPE_FIELD = "type";
  private static final String VALUE_FIELD = "value";

  private V1JsonNullValidator() {}

  /**
   * Rejects an explicit JSON null unless the request schema deliberately permits it.
   *
   * <p>Jackson maps an omitted optional member and an explicit JSON null to the same Java null. V1
   * needs this raw-tree check before model binding so closed request schemas retain that
   * distinction. Full-replacement comments may be null to clear them, and the value subtree of a
   * typed Iceberg literal may contain nulls.
   *
   * @param body parsed V1 JSON body.
   * @param requestType V1 request class selected for the route.
   */
  static void validate(JsonNode body, Class<?> requestType) {
    if (body == null) {
      throw new IllegalArgumentException("A V1 request body must be a JSON value");
    }
    validateNode(body, requestType, true, false, false);
  }

  private static void validateNode(
      JsonNode node,
      Class<?> requestType,
      boolean root,
      boolean withinLiteralValue,
      boolean valueExpressionExpected) {
    if (node.isNull()) {
      if (!withinLiteralValue && !valueExpressionExpected) {
        throw new IllegalArgumentException("Explicit JSON null is not permitted by this V1 schema");
      }
      return;
    }

    if (valueExpressionExpected && isRawLiteralContainer(node)) {
      validateNode(node, requestType, false, true, false);
      return;
    }

    if (node.isObject()) {
      boolean literalObject = LITERAL_TYPE.equals(node.path(TYPE_FIELD).asText());
      Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
      while (fields.hasNext()) {
        Map.Entry<String, JsonNode> field = fields.next();
        JsonNode value = field.getValue();
        if (root && isNullableUpdateComment(requestType, field.getKey()) && value.isNull()) {
          continue;
        }
        if (isValueExpressionArray(node, field.getKey())) {
          validateValueExpressionArray(value, requestType, withinLiteralValue);
          continue;
        }
        validateNode(
            value,
            requestType,
            false,
            withinLiteralValue || (literalObject && VALUE_FIELD.equals(field.getKey())),
            isValueExpressionField(node, field.getKey()));
      }
      return;
    }

    if (node.isArray()) {
      for (JsonNode element : node) {
        validateNode(element, requestType, false, withinLiteralValue, false);
      }
    }
  }

  private static void validateValueExpressionArray(
      JsonNode node, Class<?> requestType, boolean withinLiteralValue) {
    if (!node.isArray()) {
      validateNode(node, requestType, false, withinLiteralValue, false);
      return;
    }
    for (JsonNode element : node) {
      validateNode(element, requestType, false, withinLiteralValue, true);
    }
  }

  private static boolean isRawLiteralContainer(JsonNode node) {
    return node.isArray() || (node.isObject() && !node.has(TYPE_FIELD));
  }

  private static boolean isValueExpressionArray(JsonNode object, String fieldName) {
    if ("expressions".equals(fieldName) && object.has("strategy")) {
      return true;
    }
    if (!"arguments".equals(fieldName)) {
      return false;
    }
    return "APPLY".equals(object.path("kind").asText())
        || "apply".equals(object.path(TYPE_FIELD).asText());
  }

  private static boolean isValueExpressionField(JsonNode object, String fieldName) {
    if ("defaultValue".equals(fieldName) || "expression".equals(fieldName)) {
      return true;
    }

    String type = object.path(TYPE_FIELD).asText();
    if (isUnaryValueExpressionPredicate(type) || isSetValueExpressionPredicate(type)) {
      return "child".equals(fieldName);
    }
    return isComparisonValueExpressionPredicate(type)
        && ("left".equals(fieldName) || "right".equals(fieldName));
  }

  private static boolean isUnaryValueExpressionPredicate(String type) {
    return "is-null".equals(type)
        || "not-null".equals(type)
        || "is-nan".equals(type)
        || "not-nan".equals(type);
  }

  private static boolean isSetValueExpressionPredicate(String type) {
    return "in".equals(type) || "not-in".equals(type);
  }

  private static boolean isComparisonValueExpressionPredicate(String type) {
    return "lt".equals(type)
        || "lt-eq".equals(type)
        || "gt".equals(type)
        || "gt-eq".equals(type)
        || "eq".equals(type)
        || "not-eq".equals(type)
        || "starts-with".equals(type)
        || "not-starts-with".equals(type);
  }

  private static boolean isNullableUpdateComment(Class<?> requestType, String fieldName) {
    return COMMENT_FIELD.equals(fieldName)
        && (requestType == MetalakeUpdateRequest.class
            || requestType == CatalogUpdateRequest.class
            || requestType == SchemaUpdateRequest.class
            || requestType == TableUpdateRequest.class);
  }
}
