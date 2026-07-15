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

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;
import java.util.Objects;

/** A physical partition transform in the public Gravitino V1 wire contract. */
@JsonTypeInfo(
    use = JsonTypeInfo.Id.NAME,
    include = JsonTypeInfo.As.EXISTING_PROPERTY,
    property = "kind")
@JsonSubTypes({
  @JsonSubTypes.Type(value = Transform.Identity.class, name = "IDENTITY"),
  @JsonSubTypes.Type(value = Transform.Year.class, name = "YEAR"),
  @JsonSubTypes.Type(value = Transform.Month.class, name = "MONTH"),
  @JsonSubTypes.Type(value = Transform.Day.class, name = "DAY"),
  @JsonSubTypes.Type(value = Transform.Hour.class, name = "HOUR"),
  @JsonSubTypes.Type(value = Transform.Bucket.class, name = "BUCKET"),
  @JsonSubTypes.Type(value = Transform.Truncate.class, name = "TRUNCATE"),
  @JsonSubTypes.Type(value = Transform.ListTransform.class, name = "LIST"),
  @JsonSubTypes.Type(value = Transform.Range.class, name = "RANGE"),
  @JsonSubTypes.Type(value = Transform.Apply.class, name = "APPLY")
})
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public abstract class Transform {

  @JsonProperty(value = "kind", required = true)
  private final Kind kind;

  Transform(Kind kind) {
    this.kind = Objects.requireNonNull(kind, "kind cannot be null");
  }

  /**
   * @return stable transform kind discriminator.
   */
  public Kind getKind() {
    return kind;
  }

  /** Transform kinds supported by the V1 table contract. */
  public enum Kind {
    /** Identity transform. */
    IDENTITY,
    /** Year transform. */
    YEAR,
    /** Month transform. */
    MONTH,
    /** Day transform. */
    DAY,
    /** Hour transform. */
    HOUR,
    /** Bucket transform. */
    BUCKET,
    /** Truncate transform. */
    TRUNCATE,
    /** List partition transform. */
    LIST,
    /** Range partition transform. */
    RANGE,
    /** Catalog-defined applied function. */
    APPLY;

    /**
     * @return stable uppercase JSON value.
     */
    @JsonValue
    public String value() {
      return name();
    }
  }

  /** Base class for a transform over one field. */
  public abstract static class SingleField extends Transform {
    @JsonProperty(value = "fieldName", required = true)
    private final List<String> fieldName;

    SingleField(Kind kind, List<String> fieldName) {
      super(kind);
      this.fieldName = ModelSupport.immutableList(fieldName, "fieldName");
      if (this.fieldName.isEmpty()) {
        throw new IllegalArgumentException("fieldName cannot be empty");
      }
    }

    /**
     * @return ordered field-name path.
     */
    public List<String> getFieldName() {
      return fieldName;
    }
  }

  /** An identity transform. */
  public static final class Identity extends SingleField {
    /**
     * @param fieldName ordered field-name path.
     */
    @JsonCreator
    public Identity(@JsonProperty(value = "fieldName", required = true) List<String> fieldName) {
      super(Kind.IDENTITY, fieldName);
    }
  }

  /** A year transform. */
  public static final class Year extends SingleField {
    /**
     * @param fieldName ordered field-name path.
     */
    @JsonCreator
    public Year(@JsonProperty(value = "fieldName", required = true) List<String> fieldName) {
      super(Kind.YEAR, fieldName);
    }
  }

  /** A month transform. */
  public static final class Month extends SingleField {
    /**
     * @param fieldName ordered field-name path.
     */
    @JsonCreator
    public Month(@JsonProperty(value = "fieldName", required = true) List<String> fieldName) {
      super(Kind.MONTH, fieldName);
    }
  }

  /** A day transform. */
  public static final class Day extends SingleField {
    /**
     * @param fieldName ordered field-name path.
     */
    @JsonCreator
    public Day(@JsonProperty(value = "fieldName", required = true) List<String> fieldName) {
      super(Kind.DAY, fieldName);
    }
  }

  /** An hour transform. */
  public static final class Hour extends SingleField {
    /**
     * @param fieldName ordered field-name path.
     */
    @JsonCreator
    public Hour(@JsonProperty(value = "fieldName", required = true) List<String> fieldName) {
      super(Kind.HOUR, fieldName);
    }
  }

  /** A hash bucket transform. */
  public static final class Bucket extends Transform {
    @JsonProperty(value = "numBuckets", required = true)
    private final int numBuckets;

    @JsonProperty(value = "fieldNames", required = true)
    private final List<List<String>> fieldNames;

    /**
     * Creates a bucket transform.
     *
     * @param numBuckets positive number of buckets.
     * @param fieldNames ordered field-name paths.
     */
    @JsonCreator
    public Bucket(
        @JsonProperty(value = "numBuckets", required = true) int numBuckets,
        @JsonProperty(value = "fieldNames", required = true) List<List<String>> fieldNames) {
      super(Kind.BUCKET);
      if (numBuckets < 1) {
        throw new IllegalArgumentException("numBuckets must be positive");
      }
      this.numBuckets = numBuckets;
      this.fieldNames = ModelSupport.immutableFieldNames(fieldNames, "fieldNames");
      if (this.fieldNames.isEmpty()) {
        throw new IllegalArgumentException("fieldNames cannot be empty");
      }
    }

    /**
     * @return positive number of buckets.
     */
    public int getNumBuckets() {
      return numBuckets;
    }

    /**
     * @return ordered field-name paths.
     */
    public List<List<String>> getFieldNames() {
      return fieldNames;
    }
  }

  /** A width-based truncate transform. */
  public static final class Truncate extends SingleField {
    @JsonProperty(value = "width", required = true)
    private final int width;

    /**
     * Creates a truncate transform.
     *
     * @param width positive truncation width.
     * @param fieldName ordered field-name path.
     */
    @JsonCreator
    public Truncate(
        @JsonProperty(value = "width", required = true) int width,
        @JsonProperty(value = "fieldName", required = true) List<String> fieldName) {
      super(Kind.TRUNCATE, fieldName);
      if (width < 1) {
        throw new IllegalArgumentException("width must be positive");
      }
      this.width = width;
    }

    /**
     * @return positive truncation width.
     */
    public int getWidth() {
      return width;
    }
  }

  /** A list partition transform. */
  public static final class ListTransform extends Transform {
    @JsonProperty(value = "fieldNames", required = true)
    private final List<List<String>> fieldNames;

    @JsonProperty(value = "assignments", required = true)
    private final List<PartitionAssignment> assignments;

    /**
     * Creates a list transform.
     *
     * @param fieldNames ordered field-name paths.
     * @param assignments preassigned list partitions.
     */
    @JsonCreator
    public ListTransform(
        @JsonProperty(value = "fieldNames", required = true) List<List<String>> fieldNames,
        @JsonProperty(value = "assignments", required = true)
            List<PartitionAssignment> assignments) {
      super(Kind.LIST);
      this.fieldNames = ModelSupport.immutableFieldNames(fieldNames, "fieldNames");
      this.assignments = ModelSupport.immutableList(assignments, "assignments");
      if (this.fieldNames.isEmpty()) {
        throw new IllegalArgumentException("fieldNames cannot be empty");
      }
      for (PartitionAssignment assignment : assignments) {
        if (assignment.getKind() != PartitionAssignment.Kind.LIST) {
          throw new IllegalArgumentException("LIST transform requires LIST assignments");
        }
      }
    }

    /**
     * @return ordered field-name paths.
     */
    public List<List<String>> getFieldNames() {
      return fieldNames;
    }

    /**
     * @return preassigned list partitions.
     */
    public List<PartitionAssignment> getAssignments() {
      return assignments;
    }
  }

  /** A range partition transform. */
  public static final class Range extends SingleField {
    @JsonProperty(value = "assignments", required = true)
    private final List<PartitionAssignment> assignments;

    /**
     * Creates a range transform.
     *
     * @param fieldName ordered field-name path.
     * @param assignments preassigned range partitions.
     */
    @JsonCreator
    public Range(
        @JsonProperty(value = "fieldName", required = true) List<String> fieldName,
        @JsonProperty(value = "assignments", required = true)
            List<PartitionAssignment> assignments) {
      super(Kind.RANGE, fieldName);
      this.assignments = ModelSupport.immutableList(assignments, "assignments");
      for (PartitionAssignment assignment : assignments) {
        if (assignment.getKind() != PartitionAssignment.Kind.RANGE) {
          throw new IllegalArgumentException("RANGE transform requires RANGE assignments");
        }
      }
    }

    /**
     * @return preassigned range partitions.
     */
    public List<PartitionAssignment> getAssignments() {
      return assignments;
    }
  }

  /** A catalog-defined applied transform. */
  public static final class Apply extends Transform {
    @JsonProperty(value = "name", required = true)
    private final String name;

    @JsonProperty(value = "arguments", required = true)
    private final List<ValueExpression> arguments;

    /**
     * Creates an applied transform.
     *
     * @param name transform function name.
     * @param arguments ordered transform arguments.
     */
    @JsonCreator
    public Apply(
        @JsonProperty(value = "name", required = true) String name,
        @JsonProperty(value = "arguments", required = true) List<ValueExpression> arguments) {
      super(Kind.APPLY);
      this.name = ModelSupport.requireNonEmpty(name, "name");
      this.arguments = ModelSupport.immutableList(arguments, "arguments");
    }

    /**
     * @return transform function name.
     */
    public String getName() {
      return name;
    }

    /**
     * @return ordered transform arguments.
     */
    public List<ValueExpression> getArguments() {
      return arguments;
    }
  }
}
