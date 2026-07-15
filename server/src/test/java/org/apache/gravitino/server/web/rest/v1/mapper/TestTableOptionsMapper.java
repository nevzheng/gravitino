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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.apache.gravitino.rest.v1.model.ClickHouseOptions;
import org.apache.gravitino.rest.v1.model.HiveOptions;
import org.apache.gravitino.rest.v1.model.IcebergOptions;
import org.apache.gravitino.rest.v1.model.MysqlOptions;
import org.apache.gravitino.rest.v1.model.TableStorage;
import org.apache.gravitino.server.web.rest.v1.error.V1ClientInputException;
import org.junit.jupiter.api.Test;

/** Tests provider-aware V1 typed table storage and option mapping. */
public class TestTableOptionsMapper {

  @Test
  public void testGenericLakehouseMapsDeltaAndLanceStorage() {
    TableStorage delta =
        new TableStorage(
            TableStorage.Ownership.EXTERNAL,
            TableStorage.TableFormat.DELTA,
            "s3://warehouse/delta-orders",
            null);
    Map<String, String> deltaProperties =
        TableOptionsMapper.toInternalProperties("lakehouse-generic", delta, null, null, null, null);

    assertEquals(
        Map.of(
            "format", "delta",
            "external", "true",
            "location", "s3://warehouse/delta-orders"),
        deltaProperties);
    assertStorage(
        TableOptionsMapper.toPublic("lakehouse-generic", deltaProperties).storage(),
        TableStorage.Ownership.EXTERNAL,
        TableStorage.TableFormat.DELTA,
        "s3://warehouse/delta-orders",
        null);

    TableStorage lance =
        new TableStorage(
            TableStorage.Ownership.MANAGED,
            TableStorage.TableFormat.LANCE,
            "file:///warehouse/lance-orders",
            null);
    Map<String, String> lanceProperties =
        TableOptionsMapper.toInternalProperties("lakehouse-generic", lance, null, null, null, null);
    assertEquals("lance", lanceProperties.get("format"));
    assertEquals("false", lanceProperties.get("external"));
    assertStorage(
        TableOptionsMapper.toPublic("lakehouse-generic", lanceProperties).storage(),
        TableStorage.Ownership.MANAGED,
        TableStorage.TableFormat.LANCE,
        "file:///warehouse/lance-orders",
        null);
  }

  @Test
  public void testNativeIcebergUsesDerivedTableFormatAndPortableFileFormat() {
    TableStorage storage =
        new TableStorage(
            TableStorage.Ownership.MANAGED,
            TableStorage.TableFormat.ICEBERG,
            "s3://warehouse/orders",
            TableStorage.FileFormat.PARQUET);
    IcebergOptions options = new IcebergOptions(2);

    Map<String, String> properties =
        TableOptionsMapper.toInternalProperties(
            "lakehouse-iceberg", storage, options, null, null, null);

    assertEquals(
        Map.of(
            "location", "s3://warehouse/orders",
            "provider", "parquet",
            "format-version", "2"),
        properties);
    assertFalse(properties.containsKey("format"));

    TableOptionsMapper.PublicTableState state =
        TableOptionsMapper.toPublic(
            "lakehouse-iceberg",
            Map.of(
                "format", "iceberg/parquet",
                "location", "s3://warehouse/orders",
                "format-version", "2"));
    assertStorage(
        state.storage(),
        TableStorage.Ownership.MANAGED,
        TableStorage.TableFormat.ICEBERG,
        "s3://warehouse/orders",
        TableStorage.FileFormat.PARQUET);
    assertEquals(2, state.icebergOptions().getFormatVersion());
  }

  @Test
  public void testHiveMapsStorageAndTypedDescriptor() {
    TableStorage storage =
        new TableStorage(
            TableStorage.Ownership.EXTERNAL,
            TableStorage.TableFormat.HIVE,
            "hdfs:///warehouse/orders",
            TableStorage.FileFormat.ORC);

    Map<String, String> properties =
        TableOptionsMapper.toInternalProperties("hive", storage, null, null, null, null);

    assertEquals("EXTERNAL_TABLE", properties.get("table-type"));
    assertEquals("ORC", properties.get("format"));
    properties.put("transient_lastDdlTime", "1720987200");
    TableOptionsMapper.PublicTableState state = TableOptionsMapper.toPublic("hive", properties);
    assertStorage(
        state.storage(),
        TableStorage.Ownership.EXTERNAL,
        TableStorage.TableFormat.HIVE,
        "hdfs:///warehouse/orders",
        TableStorage.FileFormat.ORC);
    assertNull(state.hiveOptions());
  }

  @Test
  public void testHiveMapsCustomDescriptorWithoutPortableFileFormat() {
    TableStorage storage =
        new TableStorage(
            TableStorage.Ownership.EXTERNAL,
            TableStorage.TableFormat.HIVE,
            "hdfs:///warehouse/orders",
            null);
    HiveOptions options =
        new HiveOptions(
            "org.example.CustomInputFormat",
            "org.example.CustomOutputFormat",
            "org.example.CustomSerde",
            "orders_serde");

    Map<String, String> properties =
        TableOptionsMapper.toInternalProperties("hive", storage, null, options, null, null);

    assertFalse(properties.containsKey("format"));
    TableOptionsMapper.PublicTableState state = TableOptionsMapper.toPublic("hive", properties);
    assertStorage(
        state.storage(),
        TableStorage.Ownership.EXTERNAL,
        TableStorage.TableFormat.HIVE,
        "hdfs:///warehouse/orders",
        null);
    assertEquals(options.getInputFormat(), state.hiveOptions().getInputFormat());
    assertEquals(options.getOutputFormat(), state.hiveOptions().getOutputFormat());
    assertEquals(options.getSerdeLibrary(), state.hiveOptions().getSerdeLibrary());
    assertEquals(options.getSerdeName(), state.hiveOptions().getSerdeName());
  }

  @Test
  public void testHiveNormalizesAnExactLoadedStandardDescriptor() {
    TableOptionsMapper.PublicTableState state =
        TableOptionsMapper.toPublic(
            "hive",
            Map.of(
                "table-type", "EXTERNAL_TABLE",
                "location", "hdfs:///warehouse/orders",
                "input-format", "org.apache.hadoop.hive.ql.io.orc.OrcInputFormat",
                "output-format", "org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat",
                "serde-lib", "org.apache.hadoop.hive.ql.io.orc.OrcSerde",
                "transient_lastDdlTime", "1720987200"));

    assertStorage(
        state.storage(),
        TableStorage.Ownership.EXTERNAL,
        TableStorage.TableFormat.HIVE,
        "hdfs:///warehouse/orders",
        TableStorage.FileFormat.ORC);
    assertNull(state.hiveOptions());
    assertEquals(
        Map.of(
            "table-type", "EXTERNAL_TABLE",
            "location", "hdfs:///warehouse/orders",
            "format", "ORC"),
        TableOptionsMapper.toInternalProperties(
            "hive", state.storage(), null, state.hiveOptions(), null, null));
  }

  @Test
  public void testHiveKeepsNamedStandardDescriptorAsTypedOptions() {
    TableOptionsMapper.PublicTableState state =
        TableOptionsMapper.toPublic(
            "hive",
            Map.of(
                "table-type", "EXTERNAL_TABLE",
                "location", "hdfs:///warehouse/orders",
                "input-format", "org.apache.hadoop.hive.ql.io.orc.OrcInputFormat",
                "output-format", "org.apache.hadoop.hive.ql.io.orc.OrcOutputFormat",
                "serde-lib", "org.apache.hadoop.hive.ql.io.orc.OrcSerde",
                "serde-name", "orders"));

    assertStorage(
        state.storage(),
        TableStorage.Ownership.EXTERNAL,
        TableStorage.TableFormat.HIVE,
        "hdfs:///warehouse/orders",
        null);
    assertEquals("orders", state.hiveOptions().getSerdeName());
  }

  @Test
  public void testGlueRequiresExternalStorageAndMapsTypedDescriptor() {
    assertThrows(
        V1ClientInputException.class,
        () -> TableOptionsMapper.toInternalProperties("glue", null, null, null, null, null));

    TableStorage storage =
        new TableStorage(
            TableStorage.Ownership.EXTERNAL,
            TableStorage.TableFormat.HIVE,
            "s3://warehouse/glue-orders",
            TableStorage.FileFormat.PARQUET);
    HiveOptions options =
        new HiveOptions(null, null, "org.apache.iceberg.mr.hive.HiveIcebergSerDe", null);

    assertThrows(
        V1ClientInputException.class,
        () -> TableOptionsMapper.toInternalProperties("glue", storage, null, options, null, null));

    Map<String, String> properties =
        TableOptionsMapper.toInternalProperties("glue", storage, null, null, null, null);

    assertEquals("HIVE", properties.get("table-format"));
    assertEquals("parquet", properties.get("format"));
    assertEquals("s3://warehouse/glue-orders", properties.get("location"));
    TableOptionsMapper.PublicTableState state = TableOptionsMapper.toPublic("glue", properties);
    assertStorage(
        state.storage(),
        TableStorage.Ownership.EXTERNAL,
        TableStorage.TableFormat.HIVE,
        "s3://warehouse/glue-orders",
        TableStorage.FileFormat.PARQUET);
    assertNull(state.hiveOptions());
  }

  @Test
  public void testGlueMapsCustomDescriptorWithoutPortableFileFormat() {
    TableStorage storage =
        new TableStorage(
            TableStorage.Ownership.EXTERNAL,
            TableStorage.TableFormat.HIVE,
            "s3://warehouse/glue-orders",
            null);
    HiveOptions options =
        new HiveOptions(null, null, "org.apache.iceberg.mr.hive.HiveIcebergSerDe", null);

    Map<String, String> properties =
        TableOptionsMapper.toInternalProperties("glue", storage, null, options, null, null);

    assertEquals("HIVE", properties.get("table-format"));
    assertEquals("s3://warehouse/glue-orders", properties.get("location"));
    TableOptionsMapper.PublicTableState state = TableOptionsMapper.toPublic("glue", properties);
    assertStorage(
        state.storage(),
        TableStorage.Ownership.EXTERNAL,
        TableStorage.TableFormat.HIVE,
        "s3://warehouse/glue-orders",
        null);
    assertEquals(options.getSerdeLibrary(), state.hiveOptions().getSerdeLibrary());
  }

  @Test
  public void testGlueNormalizesAnExactLoadedStandardDescriptor() {
    TableOptionsMapper.PublicTableState state =
        TableOptionsMapper.toPublic(
            "glue",
            Map.of(
                "table-format", "HIVE",
                "location", "s3://warehouse/glue-orders",
                "input-format", "org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat",
                "output-format", "org.apache.hadoop.hive.ql.io.parquet.MapredParquetOutputFormat",
                "serde-lib", "org.apache.hadoop.hive.ql.io.parquet.serde.ParquetHiveSerDe"));

    assertStorage(
        state.storage(),
        TableStorage.Ownership.EXTERNAL,
        TableStorage.TableFormat.HIVE,
        "s3://warehouse/glue-orders",
        TableStorage.FileFormat.PARQUET);
    assertNull(state.hiveOptions());
    assertEquals(
        Map.of(
            "table-format", "HIVE",
            "location", "s3://warehouse/glue-orders",
            "format", "parquet"),
        TableOptionsMapper.toInternalProperties(
            "glue", state.storage(), null, state.hiveOptions(), null, null));
  }

  @Test
  public void testGlueIcebergMapsDerivedMetadataAndWriteFileFormat() {
    TableOptionsMapper.PublicTableState state =
        TableOptionsMapper.toPublic(
            "glue",
            Map.of(
                "table_type", "ICEBERG",
                "location", "s3://warehouse/glue-iceberg-orders",
                "metadata_location", "s3://warehouse/glue-iceberg-orders/metadata/v2.json",
                "previous_metadata_location", "s3://warehouse/glue-iceberg-orders/metadata/v1.json",
                "write.format.default", "parquet",
                "comment", "derived Iceberg metadata"));

    assertStorage(
        state.storage(),
        TableStorage.Ownership.EXTERNAL,
        TableStorage.TableFormat.ICEBERG,
        "s3://warehouse/glue-iceberg-orders",
        TableStorage.FileFormat.PARQUET);
    assertNull(state.hiveOptions());
  }

  @Test
  public void testGlueIcebergConsumesDerivedMetadataAndChecksFileFormatAgreement() {
    TableStorage storage =
        new TableStorage(
            TableStorage.Ownership.EXTERNAL,
            TableStorage.TableFormat.ICEBERG,
            "s3://warehouse/glue-iceberg-orders",
            TableStorage.FileFormat.PARQUET);
    Map<String, String> createProperties =
        TableOptionsMapper.toInternalProperties("glue", storage, null, null, null, null);
    assertEquals("ICEBERG", createProperties.get("table-format"));
    assertEquals("parquet", createProperties.get("format"));

    TableOptionsMapper.PublicTableState state =
        TableOptionsMapper.toPublic(
            "glue",
            Map.of(
                "table-format", "ICEBERG",
                "table_type", "ICEBERG",
                "location", "s3://warehouse/glue-iceberg-orders",
                "format", "parquet",
                "write.format.default", "PARQUET",
                "metadata_location", "s3://warehouse/glue-iceberg-orders/metadata/v1.metadata.json",
                "previous_metadata_location",
                    "s3://warehouse/glue-iceberg-orders/metadata/v0.metadata.json",
                "comment", "derived duplicate"));
    assertStorage(
        state.storage(),
        TableStorage.Ownership.EXTERNAL,
        TableStorage.TableFormat.ICEBERG,
        "s3://warehouse/glue-iceberg-orders",
        TableStorage.FileFormat.PARQUET);
    assertNull(state.hiveOptions());

    assertThrows(
        V1ClientInputException.class,
        () ->
            TableOptionsMapper.toInternalProperties(
                "glue", storage, null, new HiveOptions("input", null, null, null), null, null));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            TableOptionsMapper.toPublic(
                "glue",
                Map.of(
                    "table-format", "ICEBERG",
                    "table_type", "ICEBERG",
                    "location", "s3://warehouse/glue-iceberg-orders",
                    "format", "parquet",
                    "write.format.default", "orc")));
  }

  @Test
  public void testClickHouseMapsTypedClusterOptions() {
    ClickHouseOptions options =
        new ClickHouseOptions(
            "Distributed", true, "analytics", "sales", "orders_local", "cityHash64(id)");

    Map<String, String> properties =
        TableOptionsMapper.toInternalProperties("jdbc-clickhouse", null, null, null, options, null);

    assertEquals(
        Map.of(
            "engine", "Distributed",
            "on-cluster", "true",
            "cluster-name", "analytics",
            "cluster-remote-database", "sales",
            "cluster-remote-table", "orders_local",
            "cluster-sharding-key", "cityHash64(id)"),
        properties);
    ClickHouseOptions roundTrip =
        TableOptionsMapper.toPublic("jdbc-clickhouse", properties).clickhouseOptions();
    assertEquals(options.getEngine(), roundTrip.getEngine());
    assertEquals(options.getOnCluster(), roundTrip.getOnCluster());
    assertEquals(options.getClusterName(), roundTrip.getClusterName());
    assertEquals(options.getRemoteDatabase(), roundTrip.getRemoteDatabase());
    assertEquals(options.getRemoteTable(), roundTrip.getRemoteTable());
    assertEquals(options.getShardingKey(), roundTrip.getShardingKey());
  }

  @Test
  public void testMysqlMapsTypedEngineOptions() {
    MysqlOptions options = new MysqlOptions("InnoDB", 10);

    Map<String, String> properties =
        TableOptionsMapper.toInternalProperties("jdbc-mysql", null, null, null, null, options);

    assertEquals(Map.of("engine", "InnoDB", "auto-increment-offset", "10"), properties);
    MysqlOptions roundTrip = TableOptionsMapper.toPublic("jdbc-mysql", properties).mysqlOptions();
    assertEquals("InnoDB", roundTrip.getEngine());
    assertEquals(10, roundTrip.getAutoIncrementOffset());
  }

  @Test
  public void testBasicJdbcProfileAcceptsAnEmptyTypedStateAndPaimonFailsBeforeCreate() {
    assertEquals(
        Collections.emptyMap(),
        TableOptionsMapper.toInternalProperties("jdbc-postgresql", null, null, null, null, null));
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            TableOptionsMapper.toInternalProperties(
                "lakehouse-paimon", null, null, null, null, null));
    assertNull(TableOptionsMapper.toPublic("jdbc-postgresql", Collections.emptyMap()).storage());
    assertNull(TableOptionsMapper.toPublic("lakehouse-paimon", Collections.emptyMap()).storage());
  }

  @Test
  public void testIncompatibleTypedStateAndUnrepresentedLegacyPropertiesFailClosed() {
    assertThrows(
        V1ClientInputException.class,
        () ->
            TableOptionsMapper.toInternalProperties(
                "lakehouse-generic", null, new IcebergOptions(2), null, null, null));
    assertThrows(
        V1ClientInputException.class,
        () ->
            TableOptionsMapper.toInternalProperties(
                "jdbc-mysql",
                new TableStorage(
                    TableStorage.Ownership.MANAGED, TableStorage.TableFormat.HIVE, null, null),
                null,
                null,
                null,
                null));
    assertThrows(
        UnsupportedOperationException.class,
        () -> TableOptionsMapper.toPublic("jdbc-mysql", Map.of("legacy", "value")));
    assertThrows(
        UnsupportedOperationException.class,
        () -> TableOptionsMapper.toPublic("glue", Map.of("arbitrary", "value")));
    Map<String, String> nullValuedProperties = new HashMap<>();
    nullValuedProperties.put("arbitrary", null);
    assertThrows(
        UnsupportedOperationException.class,
        () -> TableOptionsMapper.toPublic("glue", nullValuedProperties));
  }

  @Test
  public void testGenericProviderRejectsInvalidDeltaAndSupportsDerivedLanceVersion() {
    assertThrows(
        V1ClientInputException.class,
        () ->
            TableOptionsMapper.toInternalProperties(
                "lakehouse-generic",
                new TableStorage(
                    TableStorage.Ownership.MANAGED, TableStorage.TableFormat.DELTA, null, null),
                null,
                null,
                null,
                null));
    assertStorage(
        TableOptionsMapper.toPublic(
                "lakehouse-generic",
                Map.of(
                    "format", "lance",
                    "external", "false",
                    "location", "file:///warehouse/orders",
                    "lance.version", "7"))
            .storage(),
        TableStorage.Ownership.MANAGED,
        TableStorage.TableFormat.LANCE,
        "file:///warehouse/orders",
        null);
    assertThrows(
        UnsupportedOperationException.class,
        () ->
            TableOptionsMapper.toPublic(
                "lakehouse-generic",
                Map.of(
                    "format", "lance",
                    "external", "false",
                    "location", "file:///warehouse/orders",
                    "lance.declared", "true")));
  }

  private static void assertStorage(
      TableStorage storage,
      TableStorage.Ownership ownership,
      TableStorage.TableFormat tableFormat,
      String location,
      TableStorage.FileFormat fileFormat) {
    assertEquals(ownership, storage.getOwnership());
    assertEquals(tableFormat, storage.getTableFormat());
    assertEquals(location, storage.getLocation());
    assertEquals(fileFormat, storage.getFileFormat());
  }
}
