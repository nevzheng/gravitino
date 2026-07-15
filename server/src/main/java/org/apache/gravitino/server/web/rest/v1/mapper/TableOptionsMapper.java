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
package org.apache.gravitino.server.web.rest.v1.mapper;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import javax.annotation.Nullable;
import org.apache.gravitino.rest.v1.model.ClickHouseOptions;
import org.apache.gravitino.rest.v1.model.HiveOptions;
import org.apache.gravitino.rest.v1.model.IcebergOptions;
import org.apache.gravitino.rest.v1.model.MysqlOptions;
import org.apache.gravitino.rest.v1.model.TableStorage;
import org.apache.gravitino.server.web.rest.v1.error.V1ClientInputException;

/**
 * Maps public V1 typed table storage/options to the legacy connector property map.
 *
 * <p>The legacy table property key {@code format} is provider-dependent: generic lakehouse uses it
 * for table format, while the native Iceberg connector uses it for a combined {@code
 * iceberg/file-format} description. This mapper therefore always dispatches by the loaded catalog
 * provider. It intentionally fails closed for properties that have no V1 typed representation
 * instead of exposing or silently dropping the legacy property bag.
 */
public final class TableOptionsMapper {

  static final String UNPROFILED_PROVIDER = "__unprofiled__";

  private static final String LAKEHOUSE_GENERIC = "lakehouse-generic";
  private static final String LAKEHOUSE_ICEBERG = "lakehouse-iceberg";
  private static final String HIVE = "hive";
  private static final String GLUE = "glue";
  private static final String CLICKHOUSE = "jdbc-clickhouse";
  private static final String MYSQL = "jdbc-mysql";

  private static final String FORMAT = "format";
  private static final String EXTERNAL = "external";
  private static final String LOCATION = "location";
  private static final String PROVIDER = "provider";
  private static final String FORMAT_VERSION = "format-version";
  private static final String TABLE_TYPE = "table-type";
  private static final String INPUT_FORMAT = "input-format";
  private static final String OUTPUT_FORMAT = "output-format";
  private static final String SERDE_LIBRARY = "serde-lib";
  private static final String SERDE_NAME = "serde-name";
  private static final String TABLE_FORMAT = "table-format";
  private static final String GLUE_TABLE_TYPE = "table_type";
  private static final String GLUE_METADATA_LOCATION = "metadata_location";
  private static final String GLUE_PREVIOUS_METADATA_LOCATION = "previous_metadata_location";
  private static final String GLUE_ICEBERG_WRITE_FILE_FORMAT = "write.format.default";
  private static final String HIVE_TRANSIENT_LAST_DDL_TIME = "transient_lastDdlTime";
  private static final String ENGINE = "engine";
  private static final String ON_CLUSTER = "on-cluster";
  private static final String CLUSTER_NAME = "cluster-name";
  private static final String REMOTE_DATABASE = "cluster-remote-database";
  private static final String REMOTE_TABLE = "cluster-remote-table";
  private static final String SHARDING_KEY = "cluster-sharding-key";
  private static final String AUTO_INCREMENT_OFFSET = "auto-increment-offset";

  private TableOptionsMapper() {}

  /**
   * Converts one V1 create request's typed persistent state to the configured catalog's internal
   * property map.
   *
   * @param catalogProvider configured catalog provider returned by the catalog dispatcher.
   * @param storage optional portable storage intent.
   * @param icebergOptions optional Iceberg-specific state.
   * @param hiveOptions optional Hive-specific state.
   * @param clickhouseOptions optional ClickHouse-specific state.
   * @param mysqlOptions optional MySQL-specific state.
   * @return the internal property map to pass to the existing table dispatcher.
   * @throws V1ClientInputException if a typed field is incompatible with the selected provider.
   */
  public static Map<String, String> toInternalProperties(
      String catalogProvider,
      @Nullable TableStorage storage,
      @Nullable IcebergOptions icebergOptions,
      @Nullable HiveOptions hiveOptions,
      @Nullable ClickHouseOptions clickhouseOptions,
      @Nullable MysqlOptions mysqlOptions) {
    String provider = normalizedProvider(catalogProvider);
    if (LAKEHOUSE_GENERIC.equals(provider)) {
      return toGenericLakehouseProperties(
          storage, icebergOptions, hiveOptions, clickhouseOptions, mysqlOptions);
    }
    if (LAKEHOUSE_ICEBERG.equals(provider)) {
      return toIcebergProperties(
          storage, icebergOptions, hiveOptions, clickhouseOptions, mysqlOptions);
    }
    if (HIVE.equals(provider)) {
      return toHiveProperties(
          storage, icebergOptions, hiveOptions, clickhouseOptions, mysqlOptions);
    }
    if (GLUE.equals(provider)) {
      return toGlueProperties(
          storage, icebergOptions, hiveOptions, clickhouseOptions, mysqlOptions);
    }
    if (CLICKHOUSE.equals(provider)) {
      return toClickHouseProperties(
          storage, icebergOptions, hiveOptions, clickhouseOptions, mysqlOptions);
    }
    if (MYSQL.equals(provider)) {
      return toMysqlProperties(
          storage, icebergOptions, hiveOptions, clickhouseOptions, mysqlOptions);
    }
    return toBasicProperties(
        provider, storage, icebergOptions, hiveOptions, clickhouseOptions, mysqlOptions);
  }

  /**
   * Converts a loaded internal property map into V1 typed persistent state.
   *
   * <p>All map keys must be explicitly represented by this V1 slice, or intentionally recognized as
   * derived/read-only connector metadata. Any other visible key fails closed so a full V1 PUT
   * cannot silently discard it.
   *
   * @param catalogProvider configured catalog provider returned by the catalog dispatcher.
   * @param properties internal table properties.
   * @return V1 typed persistent state.
   * @throws UnsupportedOperationException if visible internal properties are not representable.
   */
  static PublicTableState toPublic(
      String catalogProvider, @Nullable Map<String, String> properties) {
    String provider = normalizedProvider(catalogProvider);
    TreeMap<String, String> remaining = new TreeMap<>();
    if (properties != null) {
      properties.forEach(
          (key, value) -> {
            if (key != null && value != null) {
              remaining.put(key, value);
            }
          });
    }

    if (LAKEHOUSE_GENERIC.equals(provider)) {
      return publicGenericLakehouse(provider, remaining);
    }
    if (LAKEHOUSE_ICEBERG.equals(provider)) {
      return publicIceberg(provider, remaining);
    }
    if (HIVE.equals(provider)) {
      return publicHive(provider, remaining);
    }
    if (GLUE.equals(provider)) {
      return publicGlue(provider, remaining);
    }
    if (CLICKHOUSE.equals(provider)) {
      return publicClickHouse(provider, remaining);
    }
    if (MYSQL.equals(provider)) {
      return publicMysql(provider, remaining);
    }
    return publicBasic(provider, remaining);
  }

  private static Map<String, String> toGenericLakehouseProperties(
      @Nullable TableStorage storage,
      @Nullable IcebergOptions icebergOptions,
      @Nullable HiveOptions hiveOptions,
      @Nullable ClickHouseOptions clickhouseOptions,
      @Nullable MysqlOptions mysqlOptions) {
    rejectOptions(icebergOptions, hiveOptions, clickhouseOptions, mysqlOptions, LAKEHOUSE_GENERIC);
    if (storage == null) {
      throw invalid("storage", "is required by the lakehouse-generic catalog.");
    }
    if (storage.getTableFormat() == null) {
      throw invalid("storage.tableFormat", "is required by the lakehouse-generic catalog.");
    }
    if (storage.getTableFormat() != TableStorage.TableFormat.DELTA
        && storage.getTableFormat() != TableStorage.TableFormat.LANCE) {
      throw invalid(
          "storage.tableFormat",
          "must be DELTA or LANCE for the initial lakehouse-generic V1 profile.");
    }
    if (storage.getFileFormat() != null) {
      throw invalid(
          "storage.fileFormat", "is not currently supported by the lakehouse-generic catalog.");
    }
    if (storage.getTableFormat() == TableStorage.TableFormat.DELTA
        && storage.getOwnership() != TableStorage.Ownership.EXTERNAL) {
      throw invalid(
          "storage.ownership",
          "must be EXTERNAL for Delta because this catalog currently registers existing Delta tables.");
    }
    if (storage.getTableFormat() == TableStorage.TableFormat.LANCE
        && storage.getLocation() == null) {
      throw invalid("storage.location", "is required for Lance tables.");
    }
    Map<String, String> properties = new HashMap<>();
    properties.put(FORMAT, lower(storage.getTableFormat().name()));
    properties.put(
        EXTERNAL, Boolean.toString(storage.getOwnership() == TableStorage.Ownership.EXTERNAL));
    putIfNonNull(properties, LOCATION, storage.getLocation());
    return properties;
  }

  private static Map<String, String> toIcebergProperties(
      @Nullable TableStorage storage,
      @Nullable IcebergOptions icebergOptions,
      @Nullable HiveOptions hiveOptions,
      @Nullable ClickHouseOptions clickhouseOptions,
      @Nullable MysqlOptions mysqlOptions) {
    rejectOptions(null, hiveOptions, clickhouseOptions, mysqlOptions, LAKEHOUSE_ICEBERG);
    Map<String, String> properties = new HashMap<>();
    if (storage != null) {
      if (storage.getOwnership() != TableStorage.Ownership.MANAGED) {
        throw invalid("storage.ownership", "must be MANAGED for lakehouse-iceberg.");
      }
      if (storage.getTableFormat() != null
          && storage.getTableFormat() != TableStorage.TableFormat.ICEBERG) {
        throw invalid("storage.tableFormat", "must be ICEBERG for lakehouse-iceberg.");
      }
      putIfNonNull(properties, LOCATION, storage.getLocation());
      if (storage.getFileFormat() != null) {
        properties.put(PROVIDER, icebergFileFormat(storage.getFileFormat()));
      }
    }
    if (icebergOptions != null) {
      properties.put(FORMAT_VERSION, String.valueOf(icebergOptions.getFormatVersion()));
    }
    return properties;
  }

  private static Map<String, String> toHiveProperties(
      @Nullable TableStorage storage,
      @Nullable IcebergOptions icebergOptions,
      @Nullable HiveOptions hiveOptions,
      @Nullable ClickHouseOptions clickhouseOptions,
      @Nullable MysqlOptions mysqlOptions) {
    rejectOptions(icebergOptions, null, clickhouseOptions, mysqlOptions, HIVE);
    Map<String, String> properties = new HashMap<>();
    if (storage != null) {
      if (storage.getTableFormat() != null
          && storage.getTableFormat() != TableStorage.TableFormat.HIVE) {
        throw invalid("storage.tableFormat", "must be HIVE for the Hive catalog.");
      }
      properties.put(
          TABLE_TYPE,
          storage.getOwnership() == TableStorage.Ownership.EXTERNAL
              ? "EXTERNAL_TABLE"
              : "MANAGED_TABLE");
      putIfNonNull(properties, LOCATION, storage.getLocation());
      if (storage.getFileFormat() != null) {
        properties.put(FORMAT, storage.getFileFormat().name());
      }
    }
    applyHiveOptions(properties, hiveOptions);
    return properties;
  }

  private static Map<String, String> toGlueProperties(
      @Nullable TableStorage storage,
      @Nullable IcebergOptions icebergOptions,
      @Nullable HiveOptions hiveOptions,
      @Nullable ClickHouseOptions clickhouseOptions,
      @Nullable MysqlOptions mysqlOptions) {
    rejectOptions(icebergOptions, null, clickhouseOptions, mysqlOptions, GLUE);
    if (storage == null) {
      throw invalid("storage", "is required by the Glue V1 profile.");
    }
    Map<String, String> properties = new HashMap<>();
    if (storage.getOwnership() != TableStorage.Ownership.EXTERNAL) {
      throw invalid("storage.ownership", "must be EXTERNAL for Glue tables.");
    }
    if (storage.getTableFormat() != null
        && storage.getTableFormat() != TableStorage.TableFormat.ICEBERG
        && storage.getTableFormat() != TableStorage.TableFormat.HIVE) {
      throw invalid("storage.tableFormat", "must be ICEBERG or HIVE for Glue tables.");
    }
    if (storage.getTableFormat() == TableStorage.TableFormat.ICEBERG && hiveOptions != null) {
      throw invalid(
          "hiveOptions", "are not supported with storage.tableFormat ICEBERG for Glue tables.");
    }
    putIfNonNull(
        properties,
        TABLE_FORMAT,
        storage.getTableFormat() == null ? null : storage.getTableFormat().name());
    putIfNonNull(properties, LOCATION, storage.getLocation());
    if (storage.getFileFormat() != null) {
      properties.put(
          FORMAT,
          storage.getTableFormat() == TableStorage.TableFormat.ICEBERG
              ? icebergFileFormat(storage.getFileFormat())
              : lower(storage.getFileFormat().name()));
    }
    applyHiveOptions(properties, hiveOptions);
    return properties;
  }

  private static Map<String, String> toClickHouseProperties(
      @Nullable TableStorage storage,
      @Nullable IcebergOptions icebergOptions,
      @Nullable HiveOptions hiveOptions,
      @Nullable ClickHouseOptions clickhouseOptions,
      @Nullable MysqlOptions mysqlOptions) {
    rejectStorage(storage, CLICKHOUSE);
    rejectOptions(icebergOptions, hiveOptions, null, mysqlOptions, CLICKHOUSE);
    Map<String, String> properties = new HashMap<>();
    if (clickhouseOptions == null) {
      return properties;
    }
    putIfNonNull(properties, ENGINE, clickhouseOptions.getEngine());
    if (clickhouseOptions.getOnCluster() != null) {
      properties.put(ON_CLUSTER, clickhouseOptions.getOnCluster().toString());
    }
    putIfNonNull(properties, CLUSTER_NAME, clickhouseOptions.getClusterName());
    putIfNonNull(properties, REMOTE_DATABASE, clickhouseOptions.getRemoteDatabase());
    putIfNonNull(properties, REMOTE_TABLE, clickhouseOptions.getRemoteTable());
    putIfNonNull(properties, SHARDING_KEY, clickhouseOptions.getShardingKey());
    return properties;
  }

  private static Map<String, String> toMysqlProperties(
      @Nullable TableStorage storage,
      @Nullable IcebergOptions icebergOptions,
      @Nullable HiveOptions hiveOptions,
      @Nullable ClickHouseOptions clickhouseOptions,
      @Nullable MysqlOptions mysqlOptions) {
    rejectStorage(storage, MYSQL);
    rejectOptions(icebergOptions, hiveOptions, clickhouseOptions, null, MYSQL);
    Map<String, String> properties = new HashMap<>();
    if (mysqlOptions != null) {
      putIfNonNull(properties, ENGINE, mysqlOptions.getEngine());
      if (mysqlOptions.getAutoIncrementOffset() != null) {
        properties.put(AUTO_INCREMENT_OFFSET, mysqlOptions.getAutoIncrementOffset().toString());
      }
    }
    return properties;
  }

  private static Map<String, String> toBasicProperties(
      String provider,
      @Nullable TableStorage storage,
      @Nullable IcebergOptions icebergOptions,
      @Nullable HiveOptions hiveOptions,
      @Nullable ClickHouseOptions clickhouseOptions,
      @Nullable MysqlOptions mysqlOptions) {
    rejectStorage(storage, provider);
    rejectOptions(icebergOptions, hiveOptions, clickhouseOptions, mysqlOptions, provider);
    return Collections.emptyMap();
  }

  private static PublicTableState publicGenericLakehouse(
      String provider, TreeMap<String, String> properties) {
    String tableFormat = properties.remove(FORMAT);
    String external = properties.remove(EXTERNAL);
    String location = properties.remove(LOCATION);
    if (tableFormat == null && external == null && location == null) {
      requireNoUnrepresentedProperties(provider, properties);
      return PublicTableState.empty();
    }
    if (tableFormat == null) {
      throw unsupportedProperties(
          provider, "the generic lakehouse table is missing its table format");
    }
    TableStorage.TableFormat publicTableFormat = tableFormat(tableFormat, provider);
    if (publicTableFormat != TableStorage.TableFormat.DELTA
        && publicTableFormat != TableStorage.TableFormat.LANCE) {
      throw unsupportedProperties(provider, "an unsupported generic lakehouse table format");
    }
    TableStorage.Ownership publicOwnership = ownership(external, provider);
    if (publicTableFormat == TableStorage.TableFormat.DELTA
        && publicOwnership != TableStorage.Ownership.EXTERNAL) {
      throw unsupportedProperties(provider, "a Delta table that is not external");
    }
    if (publicTableFormat == TableStorage.TableFormat.LANCE && location == null) {
      throw unsupportedProperties(provider, "a Lance table without a location");
    }
    if (publicTableFormat == TableStorage.TableFormat.LANCE) {
      // The connector refreshes this volatile dataset marker after creation and alteration.
      properties.remove("lance.version");
    }
    TableStorage storage =
        publicStorage(publicOwnership, publicTableFormat, location, null, provider);
    requireNoUnrepresentedProperties(provider, properties);
    return new PublicTableState(storage, null, null, null, null);
  }

  private static PublicTableState publicIceberg(
      String provider, TreeMap<String, String> properties) {
    String format = properties.remove(FORMAT);
    String location = properties.remove(LOCATION);
    String fileProvider = properties.remove(PROVIDER);
    String formatVersion = properties.remove(FORMAT_VERSION);
    discardIcebergDerivedProperties(properties);

    TableStorage storage = null;
    if (format != null || location != null || fileProvider != null) {
      storage =
          publicStorage(
              TableStorage.Ownership.MANAGED,
              TableStorage.TableFormat.ICEBERG,
              location,
              icebergFileFormat(format, fileProvider, provider),
              provider);
    }
    IcebergOptions icebergOptions =
        formatVersion == null
            ? null
            : publicIcebergOptions(
                integerProperty(formatVersion, FORMAT_VERSION, provider), provider);
    requireNoUnrepresentedProperties(provider, properties);
    return new PublicTableState(storage, icebergOptions, null, null, null);
  }

  private static PublicTableState publicHive(String provider, TreeMap<String, String> properties) {
    String tableType = properties.remove(TABLE_TYPE);
    String external = properties.remove("EXTERNAL");
    String location = properties.remove(LOCATION);
    String fileFormat = properties.remove(FORMAT);
    HiveOptions hiveOptions = removeHiveOptions(properties, provider);
    TableStorage storage = null;
    if (tableType != null
        || external != null
        || location != null
        || fileFormat != null
        || hiveOptions != null) {
      storage =
          publicStorage(
              hiveOwnership(tableType, external, provider),
              TableStorage.TableFormat.HIVE,
              location,
              fileFormat == null ? null : fileFormat(fileFormat, provider),
              provider);
    }
    // Hive metastore maintains this timestamp independently from public table state.
    properties.remove(HIVE_TRANSIENT_LAST_DDL_TIME);
    requireNoUnrepresentedProperties(provider, properties);
    return new PublicTableState(storage, null, hiveOptions, null, null);
  }

  private static PublicTableState publicGlue(String provider, TreeMap<String, String> properties) {
    String tableFormat = properties.remove(TABLE_FORMAT);
    String glueTableType = properties.remove(GLUE_TABLE_TYPE);
    String location = properties.remove(LOCATION);
    String fileFormat = properties.remove(FORMAT);
    String icebergWriteFileFormat = properties.remove(GLUE_ICEBERG_WRITE_FILE_FORMAT);
    HiveOptions hiveOptions = removeHiveOptions(properties, provider);
    TableStorage storage = null;
    if (tableFormat != null
        || glueTableType != null
        || location != null
        || fileFormat != null
        || icebergWriteFileFormat != null) {
      TableStorage.TableFormat publicTableFormat =
          tableFormat == null
              ? glueIcebergTableFormat(glueTableType, provider)
              : tableFormat(tableFormat, provider);
      if (publicTableFormat != null
          && publicTableFormat != TableStorage.TableFormat.ICEBERG
          && publicTableFormat != TableStorage.TableFormat.HIVE) {
        throw unsupportedProperties(provider, "an unsupported Glue table format");
      }
      if (glueTableType != null
          && !"ICEBERG".equalsIgnoreCase(glueTableType)
          && publicTableFormat == TableStorage.TableFormat.ICEBERG) {
        throw unsupportedProperties(provider, "an inconsistent Glue Iceberg table type");
      }
      if (publicTableFormat == TableStorage.TableFormat.ICEBERG && hiveOptions != null) {
        throw unsupportedProperties(provider, "Hive options on an Iceberg Glue table");
      }
      if (publicTableFormat == TableStorage.TableFormat.ICEBERG) {
        discardGlueIcebergDerivedProperties(properties);
      } else if (glueTableType != null) {
        throw unsupportedProperties(provider, "an unrepresentable Glue table type");
      }
      storage =
          publicStorage(
              TableStorage.Ownership.EXTERNAL,
              publicTableFormat,
              location,
              glueFileFormat(publicTableFormat, fileFormat, icebergWriteFileFormat, provider),
              provider);
    }
    requireNoUnrepresentedProperties(provider, properties);
    return new PublicTableState(storage, null, hiveOptions, null, null);
  }

  private static PublicTableState publicClickHouse(
      String provider, TreeMap<String, String> properties) {
    String engine = properties.remove(ENGINE);
    String onCluster = properties.remove(ON_CLUSTER);
    String clusterName = properties.remove(CLUSTER_NAME);
    String remoteDatabase = properties.remove(REMOTE_DATABASE);
    String remoteTable = properties.remove(REMOTE_TABLE);
    String shardingKey = properties.remove(SHARDING_KEY);
    ClickHouseOptions clickhouseOptions = null;
    if (engine != null
        || onCluster != null
        || clusterName != null
        || remoteDatabase != null
        || remoteTable != null
        || shardingKey != null) {
      clickhouseOptions =
          publicClickHouseOptions(
              engine,
              onCluster == null ? null : booleanProperty(onCluster, ON_CLUSTER, provider),
              clusterName,
              remoteDatabase,
              remoteTable,
              shardingKey,
              provider);
    }
    requireNoUnrepresentedProperties(provider, properties);
    return new PublicTableState(null, null, null, clickhouseOptions, null);
  }

  private static PublicTableState publicMysql(String provider, TreeMap<String, String> properties) {
    String engine = properties.remove(ENGINE);
    String autoIncrementOffset = properties.remove(AUTO_INCREMENT_OFFSET);
    MysqlOptions mysqlOptions = null;
    if (engine != null || autoIncrementOffset != null) {
      mysqlOptions =
          publicMysqlOptions(
              engine,
              autoIncrementOffset == null
                  ? null
                  : integerProperty(autoIncrementOffset, AUTO_INCREMENT_OFFSET, provider),
              provider);
    }
    requireNoUnrepresentedProperties(provider, properties);
    return new PublicTableState(null, null, null, null, mysqlOptions);
  }

  private static PublicTableState publicBasic(String provider, TreeMap<String, String> properties) {
    requireNoUnrepresentedProperties(provider, properties);
    return PublicTableState.empty();
  }

  private static void applyHiveOptions(
      Map<String, String> properties, @Nullable HiveOptions options) {
    if (options == null) {
      return;
    }
    putIfNonNull(properties, INPUT_FORMAT, options.getInputFormat());
    putIfNonNull(properties, OUTPUT_FORMAT, options.getOutputFormat());
    putIfNonNull(properties, SERDE_LIBRARY, options.getSerdeLibrary());
    putIfNonNull(properties, SERDE_NAME, options.getSerdeName());
  }

  @Nullable
  private static HiveOptions removeHiveOptions(
      TreeMap<String, String> properties, String provider) {
    String inputFormat = properties.remove(INPUT_FORMAT);
    String outputFormat = properties.remove(OUTPUT_FORMAT);
    String serdeLibrary = properties.remove(SERDE_LIBRARY);
    String serdeName = properties.remove(SERDE_NAME);
    if (inputFormat == null && outputFormat == null && serdeLibrary == null && serdeName == null) {
      return null;
    }
    try {
      return new HiveOptions(inputFormat, outputFormat, serdeLibrary, serdeName);
    } catch (IllegalArgumentException exception) {
      throw unsupportedProperties(provider, "unrepresentable Hive storage descriptor");
    }
  }

  private static void discardIcebergDerivedProperties(TreeMap<String, String> properties) {
    properties.remove("comment");
    properties.remove("creator");
    properties.remove("current-snapshot-id");
    properties.remove("cherry-pick-snapshot-id");
    properties.remove("sort-order");
    properties.remove("identifier-fields");
    // V1's physical distribution is the public source of truth for this derived Iceberg property.
    properties.remove("write.distribution-mode");
  }

  private static void discardGlueIcebergDerivedProperties(TreeMap<String, String> properties) {
    // Apache Iceberg's GlueCatalog maintains these Glue parameters as its commit pointer/history.
    // They are derived metadata rather than user-configurable table create state.
    properties.remove("comment");
    properties.remove(GLUE_METADATA_LOCATION);
    properties.remove(GLUE_PREVIOUS_METADATA_LOCATION);
  }

  private static void rejectStorage(@Nullable TableStorage storage, String provider) {
    if (storage != null) {
      throw invalid("storage", "is not supported by catalog provider '" + provider + "'.");
    }
  }

  private static void rejectOptions(
      @Nullable IcebergOptions icebergOptions,
      @Nullable HiveOptions hiveOptions,
      @Nullable ClickHouseOptions clickhouseOptions,
      @Nullable MysqlOptions mysqlOptions,
      String provider) {
    if (icebergOptions != null) {
      throw invalid("icebergOptions", "is not supported by catalog provider '" + provider + "'.");
    }
    if (hiveOptions != null) {
      throw invalid("hiveOptions", "is not supported by catalog provider '" + provider + "'.");
    }
    if (clickhouseOptions != null) {
      throw invalid(
          "clickhouseOptions", "is not supported by catalog provider '" + provider + "'.");
    }
    if (mysqlOptions != null) {
      throw invalid("mysqlOptions", "is not supported by catalog provider '" + provider + "'.");
    }
  }

  private static TableStorage publicStorage(
      TableStorage.Ownership ownership,
      @Nullable TableStorage.TableFormat tableFormat,
      @Nullable String location,
      @Nullable TableStorage.FileFormat fileFormat,
      String provider) {
    try {
      return new TableStorage(ownership, tableFormat, location, fileFormat);
    } catch (IllegalArgumentException exception) {
      throw unsupportedProperties(provider, "unrepresentable storage state");
    }
  }

  private static IcebergOptions publicIcebergOptions(int formatVersion, String provider) {
    try {
      return new IcebergOptions(formatVersion);
    } catch (IllegalArgumentException exception) {
      throw unsupportedProperties(provider, "unrepresentable Iceberg options");
    }
  }

  private static ClickHouseOptions publicClickHouseOptions(
      @Nullable String engine,
      @Nullable Boolean onCluster,
      @Nullable String clusterName,
      @Nullable String remoteDatabase,
      @Nullable String remoteTable,
      @Nullable String shardingKey,
      String provider) {
    try {
      return new ClickHouseOptions(
          engine, onCluster, clusterName, remoteDatabase, remoteTable, shardingKey);
    } catch (IllegalArgumentException exception) {
      throw unsupportedProperties(provider, "unrepresentable ClickHouse options");
    }
  }

  private static MysqlOptions publicMysqlOptions(
      @Nullable String engine, @Nullable Integer autoIncrementOffset, String provider) {
    try {
      return new MysqlOptions(engine, autoIncrementOffset);
    } catch (IllegalArgumentException exception) {
      throw unsupportedProperties(provider, "unrepresentable MySQL options");
    }
  }

  private static void requireNoUnrepresentedProperties(
      String provider, TreeMap<String, String> properties) {
    if (!properties.isEmpty()) {
      throw unsupportedProperties(
          provider, "unrepresented property '" + properties.firstKey() + "'");
    }
  }

  private static UnsupportedOperationException unsupportedProperties(
      String provider, String reason) {
    return new UnsupportedOperationException(
        "The V1 table contract cannot represent "
            + reason
            + " for catalog provider '"
            + provider
            + "'.");
  }

  private static TableStorage.Ownership ownership(@Nullable String external, String provider) {
    if (external == null || "false".equalsIgnoreCase(external)) {
      return TableStorage.Ownership.MANAGED;
    }
    if ("true".equalsIgnoreCase(external)) {
      return TableStorage.Ownership.EXTERNAL;
    }
    throw unsupportedProperties(provider, "invalid external property value");
  }

  private static TableStorage.Ownership hiveOwnership(
      @Nullable String tableType, @Nullable String external, String provider) {
    if (tableType == null) {
      return ownership(external, provider);
    }
    if ("MANAGED_TABLE".equalsIgnoreCase(tableType)) {
      return TableStorage.Ownership.MANAGED;
    }
    if ("EXTERNAL_TABLE".equalsIgnoreCase(tableType)) {
      return TableStorage.Ownership.EXTERNAL;
    }
    throw unsupportedProperties(provider, "unrepresentable Hive table type");
  }

  private static TableStorage.TableFormat tableFormat(String value, String provider) {
    try {
      return TableStorage.TableFormat.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw unsupportedProperties(provider, "unrepresentable table format");
    }
  }

  @Nullable
  private static TableStorage.TableFormat glueIcebergTableFormat(
      @Nullable String glueTableType, String provider) {
    if (glueTableType == null) {
      return null;
    }
    if ("ICEBERG".equalsIgnoreCase(glueTableType)) {
      return TableStorage.TableFormat.ICEBERG;
    }
    throw unsupportedProperties(provider, "an unrepresentable Glue table type");
  }

  @Nullable
  private static TableStorage.FileFormat icebergFileFormat(
      @Nullable String format, @Nullable String providerValue, String provider) {
    String candidate = providerValue;
    if (format != null) {
      String normalized = format.toLowerCase(Locale.ROOT);
      if (normalized.startsWith("iceberg/")) {
        candidate = normalized.substring("iceberg/".length());
      } else if (!"iceberg".equals(normalized)) {
        throw unsupportedProperties(provider, "unrepresentable native Iceberg format");
      }
    }
    if (candidate == null || "iceberg".equalsIgnoreCase(candidate)) {
      return null;
    }
    return fileFormat(candidate, provider);
  }

  private static String icebergFileFormat(TableStorage.FileFormat fileFormat) {
    switch (fileFormat) {
      case PARQUET:
      case ORC:
      case AVRO:
        return lower(fileFormat.name());
      default:
        throw invalid("storage.fileFormat", "must be PARQUET, ORC, or AVRO for lakehouse-iceberg.");
    }
  }

  @Nullable
  private static TableStorage.FileFormat glueFileFormat(
      @Nullable TableStorage.TableFormat tableFormat,
      @Nullable String format,
      @Nullable String icebergWriteFileFormat,
      String provider) {
    if (tableFormat != TableStorage.TableFormat.ICEBERG) {
      if (icebergWriteFileFormat != null) {
        throw unsupportedProperties(
            provider, "an Iceberg write file format on a non-Iceberg table");
      }
      return format == null ? null : fileFormat(format, provider);
    }
    if (format != null
        && icebergWriteFileFormat != null
        && !format.equalsIgnoreCase(icebergWriteFileFormat)) {
      throw unsupportedProperties(provider, "conflicting Glue and Iceberg file formats");
    }
    String resolvedFormat = format == null ? icebergWriteFileFormat : format;
    if (resolvedFormat == null) {
      return null;
    }
    TableStorage.FileFormat publicFileFormat = fileFormat(resolvedFormat, provider);
    switch (publicFileFormat) {
      case PARQUET:
      case ORC:
      case AVRO:
        return publicFileFormat;
      default:
        throw unsupportedProperties(provider, "an unsupported Iceberg file format");
    }
  }

  private static TableStorage.FileFormat fileFormat(String value, String provider) {
    try {
      return TableStorage.FileFormat.valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw unsupportedProperties(provider, "unrepresentable file format");
    }
  }

  private static boolean booleanProperty(String value, String key, String provider) {
    if ("true".equalsIgnoreCase(value)) {
      return true;
    }
    if ("false".equalsIgnoreCase(value)) {
      return false;
    }
    throw unsupportedProperties(provider, "invalid " + key + " property value");
  }

  private static int integerProperty(String value, String key, String provider) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException exception) {
      throw unsupportedProperties(provider, "invalid " + key + " property value");
    }
  }

  private static void putIfNonNull(Map<String, String> target, String key, @Nullable String value) {
    if (value != null) {
      target.put(key, value);
    }
  }

  private static String lower(String value) {
    return value.toLowerCase(Locale.ROOT);
  }

  private static String normalizedProvider(String provider) {
    Objects.requireNonNull(provider, "catalogProvider cannot be null");
    if (provider.trim().isEmpty()) {
      throw new IllegalArgumentException("catalogProvider cannot be blank");
    }
    return provider.toLowerCase(Locale.ROOT);
  }

  private static V1ClientInputException invalid(String field, String description) {
    return new V1ClientInputException(field, description);
  }

  /** Typed public table state extracted from one internal property map. */
  static final class PublicTableState {

    @Nullable private final TableStorage storage;
    @Nullable private final IcebergOptions icebergOptions;
    @Nullable private final HiveOptions hiveOptions;
    @Nullable private final ClickHouseOptions clickhouseOptions;
    @Nullable private final MysqlOptions mysqlOptions;

    PublicTableState(
        @Nullable TableStorage storage,
        @Nullable IcebergOptions icebergOptions,
        @Nullable HiveOptions hiveOptions,
        @Nullable ClickHouseOptions clickhouseOptions,
        @Nullable MysqlOptions mysqlOptions) {
      this.storage = storage;
      this.icebergOptions = icebergOptions;
      this.hiveOptions = hiveOptions;
      this.clickhouseOptions = clickhouseOptions;
      this.mysqlOptions = mysqlOptions;
    }

    static PublicTableState empty() {
      return new PublicTableState(null, null, null, null, null);
    }

    @Nullable
    TableStorage storage() {
      return storage;
    }

    @Nullable
    IcebergOptions icebergOptions() {
      return icebergOptions;
    }

    @Nullable
    HiveOptions hiveOptions() {
      return hiveOptions;
    }

    @Nullable
    ClickHouseOptions clickhouseOptions() {
      return clickhouseOptions;
    }

    @Nullable
    MysqlOptions mysqlOptions() {
      return mysqlOptions;
    }
  }
}
