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
import java.util.Objects;
import javax.annotation.Nullable;

/** Portable storage intent for a public Gravitino V1 table. */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = false)
public final class TableStorage {

  /** Identifies who owns the physical data lifecycle. */
  public enum Ownership {
    /** The catalog manages physical table storage. */
    MANAGED,

    /** The caller owns physical data and Gravitino manages metadata only. */
    EXTERNAL
  }

  /** Identifies the metadata/table format selected for a table. */
  public enum TableFormat {
    /** Apache Iceberg table metadata. */
    ICEBERG,

    /** Delta Lake table metadata. */
    DELTA,

    /** Lance dataset metadata. */
    LANCE,

    /** Apache Paimon table metadata. */
    PAIMON,

    /** Apache Hudi table metadata. */
    HUDI,

    /** Hive table metadata. */
    HIVE
  }

  /** Identifies the default file format used for future data writes. */
  public enum FileFormat {
    /** Apache Parquet. */
    PARQUET,

    /** Apache ORC. */
    ORC,

    /** Apache Avro. */
    AVRO,

    /** Hive TextFile. */
    TEXTFILE,

    /** Hive SequenceFile. */
    SEQUENCEFILE,

    /** Hive RCFile. */
    RCFILE,

    /** JSON text files. */
    JSON,

    /** CSV text files. */
    CSV,

    /** Hive RegexSerde files. */
    REGEX
  }

  @JsonProperty(value = "ownership", required = true)
  private final Ownership ownership;

  @Nullable
  @JsonProperty("tableFormat")
  private final TableFormat tableFormat;

  @Nullable
  @JsonProperty("location")
  private final String location;

  @Nullable
  @JsonProperty("fileFormat")
  private final FileFormat fileFormat;

  /**
   * Creates public V1 table storage intent.
   *
   * @param ownership physical data lifecycle owner.
   * @param tableFormat optional table metadata format.
   * @param location optional connector-recognized table location.
   * @param fileFormat optional default file format for future writes.
   */
  @JsonCreator
  public TableStorage(
      @JsonProperty(value = "ownership", required = true) Ownership ownership,
      @Nullable @JsonProperty("tableFormat") TableFormat tableFormat,
      @Nullable @JsonProperty("location") String location,
      @Nullable @JsonProperty("fileFormat") FileFormat fileFormat) {
    this.ownership = Objects.requireNonNull(ownership, "ownership cannot be null");
    this.tableFormat = tableFormat;
    this.location = ModelSupport.requireNullableBoundedNonblankString(location, "location", 4096);
    this.fileFormat = fileFormat;
    if (ownership == Ownership.EXTERNAL && location == null) {
      throw new IllegalArgumentException("location is required for EXTERNAL storage");
    }
  }

  /**
   * @return physical data lifecycle owner.
   */
  public Ownership getOwnership() {
    return ownership;
  }

  /**
   * @return optional table metadata format.
   */
  @Nullable
  public TableFormat getTableFormat() {
    return tableFormat;
  }

  /**
   * @return optional connector-recognized table location.
   */
  @Nullable
  public String getLocation() {
    return location;
  }

  /**
   * @return optional default file format for future writes.
   */
  @Nullable
  public FileFormat getFileFormat() {
    return fileFormat;
  }
}
