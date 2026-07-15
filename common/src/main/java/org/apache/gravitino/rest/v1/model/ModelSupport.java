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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.annotation.Nullable;

final class ModelSupport {

  private static final int MAX_JSON_CONTAINER_SIZE = 10_000;

  static String requireNonEmpty(String value, String name) {
    Objects.requireNonNull(value, name + " cannot be null");
    if (value.isEmpty()) {
      throw new IllegalArgumentException(name + " cannot be empty");
    }
    return value;
  }

  static <T> List<T> immutableList(List<T> values, String name) {
    Objects.requireNonNull(values, name + " cannot be null");
    ArrayList<T> copy = new ArrayList<>(values.size());
    for (T value : values) {
      copy.add(Objects.requireNonNull(value, name + " cannot contain null values"));
    }
    return Collections.unmodifiableList(copy);
  }

  static List<List<String>> immutableFieldNames(List<List<String>> fieldNames, String name) {
    Objects.requireNonNull(fieldNames, name + " cannot be null");
    ArrayList<List<String>> copy = new ArrayList<>(fieldNames.size());
    for (List<String> fieldName : fieldNames) {
      copy.add(immutableList(fieldName, name + " entry"));
    }
    return Collections.unmodifiableList(copy);
  }

  static <T> List<List<T>> immutableNestedList(List<List<T>> values, String name) {
    Objects.requireNonNull(values, name + " cannot be null");
    ArrayList<List<T>> copy = new ArrayList<>(values.size());
    for (List<T> value : values) {
      copy.add(immutableList(value, name + " entry"));
    }
    return Collections.unmodifiableList(copy);
  }

  static Map<String, String> immutableMap(Map<String, String> values, String name) {
    Objects.requireNonNull(values, name + " cannot be null");
    TreeMap<String, String> copy = new TreeMap<>();
    for (Map.Entry<String, String> entry : values.entrySet()) {
      copy.put(
          Objects.requireNonNull(entry.getKey(), name + " cannot contain null keys"),
          Objects.requireNonNull(entry.getValue(), name + " cannot contain null values"));
    }
    return Collections.unmodifiableMap(copy);
  }

  /**
   * Recursively validates and defensively copies an Iceberg JSON single value.
   *
   * <p>Nested values use the representations from Appendix D of the Iceberg table specification:
   * structs are objects keyed by positive field ID, lists are JSON arrays, and maps are objects
   * with parallel key and value arrays.
   */
  @Nullable
  static Object immutableJsonValue(@Nullable Object value, String name) {
    if (value == null || value instanceof String || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Number) {
      validateJsonNumber((Number) value, name);
      return value;
    }
    if (value instanceof List) {
      List<?> values = (List<?>) value;
      requireJsonContainerSize(values.size(), name);
      ArrayList<Object> copy = new ArrayList<>(values.size());
      for (int index = 0; index < values.size(); index++) {
        copy.add(immutableJsonValue(values.get(index), name + "[" + index + "]"));
      }
      return Collections.unmodifiableList(copy);
    }
    if (value instanceof Map) {
      return immutableJsonObject((Map<?, ?>) value, name);
    }
    throw new IllegalArgumentException(
        name + " must use an Iceberg JSON single-value representation");
  }

  private static Map<String, Object> immutableJsonObject(Map<?, ?> value, String name) {
    requireJsonContainerSize(value.size(), name);
    if (isMapValue(value)) {
      return immutableMapValue(value, name);
    }

    TreeMap<Integer, Object> byFieldId = new TreeMap<>();
    for (Map.Entry<?, ?> entry : value.entrySet()) {
      if (!(entry.getKey() instanceof String)) {
        throw new IllegalArgumentException(name + " struct keys must be field ID strings");
      }
      String fieldIdText = (String) entry.getKey();
      int fieldId = parseFieldId(fieldIdText, name);
      byFieldId.put(fieldId, immutableJsonValue(entry.getValue(), name + "." + fieldIdText));
    }

    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    for (Map.Entry<Integer, Object> entry : byFieldId.entrySet()) {
      copy.put(Integer.toString(entry.getKey()), entry.getValue());
    }
    return Collections.unmodifiableMap(copy);
  }

  private static boolean isMapValue(Map<?, ?> value) {
    return value.size() == 2 && value.containsKey("keys") && value.containsKey("values");
  }

  private static Map<String, Object> immutableMapValue(Map<?, ?> value, String name) {
    Object keysValue = value.get("keys");
    Object valuesValue = value.get("values");
    if (!(keysValue instanceof List) || !(valuesValue instanceof List)) {
      throw new IllegalArgumentException(name + " map keys and values must be JSON arrays");
    }

    List<?> keys = (List<?>) keysValue;
    List<?> values = (List<?>) valuesValue;
    requireJsonContainerSize(keys.size(), name + ".keys");
    requireJsonContainerSize(values.size(), name + ".values");
    if (keys.size() != values.size()) {
      throw new IllegalArgumentException(name + " map keys and values must have equal lengths");
    }

    ArrayList<Object> keyCopy = new ArrayList<>(keys.size());
    ArrayList<Object> valueCopy = new ArrayList<>(values.size());
    for (int index = 0; index < keys.size(); index++) {
      Object key = keys.get(index);
      if (key == null) {
        throw new IllegalArgumentException(name + " map keys cannot contain null");
      }
      keyCopy.add(immutableJsonValue(key, name + ".keys[" + index + "]"));
      valueCopy.add(immutableJsonValue(values.get(index), name + ".values[" + index + "]"));
    }

    LinkedHashMap<String, Object> copy = new LinkedHashMap<>();
    copy.put("keys", Collections.unmodifiableList(keyCopy));
    copy.put("values", Collections.unmodifiableList(valueCopy));
    return Collections.unmodifiableMap(copy);
  }

  private static int parseFieldId(String fieldId, String name) {
    if (!fieldId.matches("[1-9][0-9]*")) {
      throw new IllegalArgumentException(
          name + " struct key is not a positive field ID: " + fieldId);
    }
    try {
      return Integer.parseInt(fieldId);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(
          name + " struct field ID exceeds integer range: " + fieldId, e);
    }
  }

  private static void validateJsonNumber(Number value, String name) {
    if (!(value instanceof Byte)
        && !(value instanceof Short)
        && !(value instanceof Integer)
        && !(value instanceof Long)
        && !(value instanceof Float)
        && !(value instanceof Double)
        && !(value instanceof BigInteger)
        && !(value instanceof BigDecimal)) {
      throw new IllegalArgumentException(name + " contains an unsupported JSON number");
    }
    if ((value instanceof Double && !Double.isFinite(value.doubleValue()))
        || (value instanceof Float && !Float.isFinite(value.floatValue()))) {
      throw new IllegalArgumentException(name + " contains a non-finite JSON number");
    }
  }

  static void requireJsonContainerSize(int size, String name) {
    if (size > MAX_JSON_CONTAINER_SIZE) {
      throw new IllegalArgumentException(
          name + " cannot contain more than " + MAX_JSON_CONTAINER_SIZE + " values");
    }
  }

  private ModelSupport() {}
}
