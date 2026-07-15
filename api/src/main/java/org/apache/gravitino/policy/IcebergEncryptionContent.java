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
package org.apache.gravitino.policy;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.MetadataObject;

/** Built-in policy content for governing encryption of Iceberg tables. */
public class IcebergEncryptionContent implements PolicyContent {

  /** The supported schema version. */
  public static final int CURRENT_SCHEMA_VERSION = 1;

  /** Whether encryption is required by default. */
  public static final boolean DEFAULT_REQUIRED = true;

  /** The default enforcement behavior. */
  public static final Enforcement DEFAULT_ENFORCEMENT = Enforcement.REPORT;

  /** Rule key for the policy content schema version. */
  public static final String SCHEMA_VERSION_KEY = "schemaVersion";

  /** Rule key for the governed tag. */
  public static final String TAG_KEY = "tag";

  /** Rule key for whether encryption is required. */
  public static final String REQUIRED_KEY = "required";

  /** Rule key for allowed key identifiers. */
  public static final String ALLOWED_KEY_IDS_KEY = "allowedKeyIds";

  /** Rule key for enforcement behavior. */
  public static final String ENFORCEMENT_KEY = "enforcement";

  private static final Set<MetadataObject.Type> SUPPORTED_OBJECT_TYPES =
      ImmutableSet.of(MetadataObject.Type.TABLE);

  private final int schemaVersion;
  private final String tag;
  private final boolean required;
  private final List<String> allowedKeyIds;
  private final Enforcement enforcement;

  /** Default constructor for Jackson deserialization only. */
  private IcebergEncryptionContent() {
    this(0, null, null, null, null);
  }

  IcebergEncryptionContent(
      Integer schemaVersion,
      String tag,
      Boolean required,
      List<String> allowedKeyIds,
      Enforcement enforcement) {
    this.schemaVersion = schemaVersion == null ? 0 : schemaVersion;
    this.tag = tag;
    this.required = required == null ? DEFAULT_REQUIRED : required;
    this.allowedKeyIds =
        allowedKeyIds == null
            ? Collections.emptyList()
            : Collections.unmodifiableList(new ArrayList<>(allowedKeyIds));
    this.enforcement = enforcement == null ? DEFAULT_ENFORCEMENT : enforcement;
  }

  /**
   * Returns the policy content schema version.
   *
   * @return schema version
   */
  public int schemaVersion() {
    return schemaVersion;
  }

  /**
   * Returns the tag governed by this policy.
   *
   * @return governed tag
   */
  public String tag() {
    return tag;
  }

  /**
   * Returns whether encryption is required for matching tables.
   *
   * @return {@code true} if encryption is required
   */
  public boolean required() {
    return required;
  }

  /**
   * Returns the allowed key identifiers. Values are preserved exactly and are case-sensitive.
   *
   * @return immutable allowed key identifier list
   */
  public List<String> allowedKeyIds() {
    return allowedKeyIds;
  }

  /**
   * Returns the enforcement behavior.
   *
   * @return enforcement behavior
   */
  public Enforcement enforcement() {
    return enforcement;
  }

  @Override
  public Set<MetadataObject.Type> supportedObjectTypes() {
    return SUPPORTED_OBJECT_TYPES;
  }

  @Override
  public Map<String, String> properties() {
    return ImmutableMap.of();
  }

  @Override
  public Map<String, Object> rules() {
    Map<String, Object> rules = new LinkedHashMap<>();
    rules.put(SCHEMA_VERSION_KEY, schemaVersion);
    rules.put(TAG_KEY, tag);
    rules.put(REQUIRED_KEY, required);
    rules.put(ALLOWED_KEY_IDS_KEY, allowedKeyIds);
    rules.put(ENFORCEMENT_KEY, enforcement.value());
    return Collections.unmodifiableMap(rules);
  }

  @Override
  public void validate() throws IllegalArgumentException {
    PolicyContent.super.validate();
    Preconditions.checkArgument(
        schemaVersion == CURRENT_SCHEMA_VERSION,
        "schemaVersion must be %s",
        CURRENT_SCHEMA_VERSION);
    Preconditions.checkArgument(StringUtils.isNotBlank(tag), "tag cannot be blank");
    Preconditions.checkArgument(enforcement != null, "enforcement cannot be null");
    Preconditions.checkArgument(
        !required || !allowedKeyIds.isEmpty(),
        "allowedKeyIds cannot be empty when encryption is required");

    Set<String> uniqueKeyIds = new HashSet<>();
    for (String keyId : allowedKeyIds) {
      Preconditions.checkArgument(
          StringUtils.isNotBlank(keyId), "allowedKeyIds cannot contain blanks");
      Preconditions.checkArgument(
          uniqueKeyIds.add(keyId), "allowedKeyIds cannot contain duplicate key ID: %s", keyId);
    }
  }

  @Override
  public boolean equals(Object o) {
    if (!(o instanceof IcebergEncryptionContent)) {
      return false;
    }
    IcebergEncryptionContent that = (IcebergEncryptionContent) o;
    return schemaVersion == that.schemaVersion
        && required == that.required
        && Objects.equals(tag, that.tag)
        && Objects.equals(allowedKeyIds, that.allowedKeyIds)
        && enforcement == that.enforcement;
  }

  @Override
  public int hashCode() {
    return Objects.hash(schemaVersion, tag, required, allowedKeyIds, enforcement);
  }

  @Override
  public String toString() {
    return "IcebergEncryptionContent{"
        + "schemaVersion="
        + schemaVersion
        + ", tag='"
        + tag
        + '\''
        + ", required="
        + required
        + ", allowedKeyIds="
        + allowedKeyIds
        + ", enforcement="
        + enforcement
        + '}';
  }

  /** Enforcement behavior for a noncompliant Iceberg table creation request. */
  public enum Enforcement {
    /** Report the violation without denying table creation. */
    REPORT("report"),

    /** Deny creation of the noncompliant table. */
    DENY_CREATE("deny-create");

    private final String value;

    Enforcement(String value) {
      this.value = value;
    }

    /**
     * Returns the stable JSON value for this enforcement behavior.
     *
     * @return wire value
     */
    @JsonValue
    public String value() {
      return value;
    }

    /**
     * Parses an exact, case-sensitive enforcement wire value.
     *
     * @param value wire value
     * @return matching enforcement behavior
     * @throws IllegalArgumentException if the value is unsupported
     */
    @JsonCreator
    public static Enforcement fromValue(String value) {
      for (Enforcement enforcement : values()) {
        if (enforcement.value.equals(value)) {
          return enforcement;
        }
      }
      throw new IllegalArgumentException("Unknown enforcement: " + value);
    }
  }
}
