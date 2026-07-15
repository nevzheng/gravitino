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
import javax.annotation.Nullable;

/** Immutable Hive storage-descriptor overrides. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public final class HiveOptions {

  @Nullable
  @JsonProperty("inputFormat")
  private final String inputFormat;

  @Nullable
  @JsonProperty("outputFormat")
  private final String outputFormat;

  @Nullable
  @JsonProperty("serdeLibrary")
  private final String serdeLibrary;

  @Nullable
  @JsonProperty("serdeName")
  private final String serdeName;

  /**
   * Creates public V1 Hive options.
   *
   * @param inputFormat optional Hive input format class.
   * @param outputFormat optional Hive output format class.
   * @param serdeLibrary optional Hive SerDe library class.
   * @param serdeName optional Hive SerDe name.
   */
  @JsonCreator
  public HiveOptions(
      @Nullable @JsonProperty("inputFormat") String inputFormat,
      @Nullable @JsonProperty("outputFormat") String outputFormat,
      @Nullable @JsonProperty("serdeLibrary") String serdeLibrary,
      @Nullable @JsonProperty("serdeName") String serdeName) {
    this.inputFormat =
        ModelSupport.requireNullableBoundedNonblankString(inputFormat, "inputFormat", 1024);
    this.outputFormat =
        ModelSupport.requireNullableBoundedNonblankString(outputFormat, "outputFormat", 1024);
    this.serdeLibrary =
        ModelSupport.requireNullableBoundedNonblankString(serdeLibrary, "serdeLibrary", 1024);
    this.serdeName =
        ModelSupport.requireNullableBoundedNonblankString(serdeName, "serdeName", 1024);
    if (this.inputFormat == null
        && this.outputFormat == null
        && this.serdeLibrary == null
        && this.serdeName == null) {
      throw new IllegalArgumentException("hiveOptions must contain at least one option");
    }
  }

  /**
   * @return optional Hive input format class.
   */
  @Nullable
  public String getInputFormat() {
    return inputFormat;
  }

  /**
   * @return optional Hive output format class.
   */
  @Nullable
  public String getOutputFormat() {
    return outputFormat;
  }

  /**
   * @return optional Hive SerDe library class.
   */
  @Nullable
  public String getSerdeLibrary() {
    return serdeLibrary;
  }

  /**
   * @return optional Hive SerDe name.
   */
  @Nullable
  public String getSerdeName() {
    return serdeName;
  }
}
