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
package org.apache.gravitino.storage;

import static org.apache.gravitino.Configs.TREE_LOCK_CLEAN_INTERVAL;
import static org.apache.gravitino.Configs.TREE_LOCK_MAX_NODE_IN_MEMORY;
import static org.apache.gravitino.Configs.TREE_LOCK_MIN_NODE_IN_MEMORY;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.dto.DeletedEntityDTO;
import org.apache.gravitino.lock.LockManager;
import org.apache.gravitino.meta.TableEntity;
import org.apache.gravitino.recovery.RecoverableDeletionManager;
import org.apache.gravitino.storage.relational.TestJDBCBackend;
import org.apache.gravitino.storage.relational.service.TableMetaService;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.ibatis.session.SqlSession;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.TestTemplate;
import org.mockito.Mockito;

public class TestSQLScripts extends TestJDBCBackend {

  private static final long RETENTION_MS = 604_800_000L;
  private static final Set<String> DELETION_ID_TABLES =
      Set.of(
          "catalog_meta",
          "fileset_meta",
          "fileset_version_info",
          "function_meta",
          "function_version_info",
          "group_meta",
          "group_role_rel",
          "job_run_meta",
          "job_template_meta",
          "metalake_meta",
          "model_meta",
          "model_version_alias_rel",
          "model_version_info",
          "owner_meta",
          "policy_meta",
          "policy_relation_meta",
          "policy_version_info",
          "role_meta",
          "role_meta_securable_object",
          "schema_meta",
          "statistic_meta",
          "table_column_version_info",
          "table_meta",
          "table_version_info",
          "tag_meta",
          "tag_relation_meta",
          "topic_meta",
          "user_meta",
          "user_role_rel",
          "view_meta",
          "view_version_info");
  private static final Set<String> ENTITY_DELETION_COLUMNS =
      Set.of(
          "affected_row_count",
          "catalog_id",
          "deleted_at",
          "deleted_by",
          "deletion_id",
          "entity_id",
          "entity_name",
          "entity_type",
          "entity_version",
          "expires_at",
          "metalake_id",
          "parent_id",
          "purged_at",
          "restore_etag",
          "restored_at",
          "revision",
          "state");

  @TestTemplate
  public void testSQLScripts() throws SQLException, IOException {
    String gravitinoHome = System.getenv("GRAVITINO_HOME");
    Assertions.assertNotNull(gravitinoHome, "GRAVITINO_HOME environment variable is not set");
    Path scriptDir = Path.of(gravitinoHome, "scripts", backendType.toLowerCase());

    File[] scriptFiles = scriptDir.toFile().listFiles();
    Assertions.assertNotNull(scriptFiles, "No script files found in " + scriptDir);
    // Sort files to ensure the correct execution order (schema -> upgrade)
    Arrays.sort(scriptFiles, Comparator.comparing(File::getName));

    // A map to store connections for different schema versions
    Pattern schemaPattern =
        Pattern.compile("schema-([\\d.]+)-" + backendType.toLowerCase() + "\\.sql");
    Pattern upgradePattern =
        Pattern.compile("upgrade-([\\d.]+)-to-([\\d.]+)-" + backendType.toLowerCase() + "\\.sql");
    Pattern metricsPattern =
        Pattern.compile(
            "(?:iceberg|optimizer)-metrics-schema-([\\d.]+)-"
                + backendType.toLowerCase()
                + "\\.sql");

    Map<String, List<File>> versionScrips = new HashMap<>();
    for (File scriptFile : scriptFiles) {
      Matcher schemaMatcher = schemaPattern.matcher(scriptFile.getName());
      Matcher upgradeMatcher = upgradePattern.matcher(scriptFile.getName());
      Matcher metricsMatcher = metricsPattern.matcher(scriptFile.getName());

      if (schemaMatcher.matches()) {
        String version = schemaMatcher.group(1);
        versionScrips.computeIfAbsent(version, k -> new ArrayList<>()).add(scriptFile);

      } else if (upgradeMatcher.matches()) {
        String fromVersion = upgradeMatcher.group(1);
        Assertions.assertTrue(
            versionScrips.containsKey(fromVersion), "No schema script found for " + fromVersion);
        versionScrips.get(fromVersion).add(scriptFile);

      } else if (metricsMatcher.matches()) {
        String version = metricsMatcher.group(1);
        versionScrips.computeIfAbsent(version, k -> new ArrayList<>()).add(scriptFile);

      } else {
        Assertions.fail("Unrecognized script file name: " + scriptFile.getName());
      }
    }

    for (List<File> scripts : versionScrips.values()) {
      dropAllTables();
      for (File scriptFile : scripts) {
        List<String> ddls = extractStatements(scriptFile.toPath());

        try (SqlSession sqlSession =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true)) {
          try (Connection connection = sqlSession.getConnection()) {
            try (Statement statement = connection.createStatement()) {
              for (String ddl : ddls) {
                Assertions.assertDoesNotThrow(
                    () -> statement.execute(ddl),
                    "Failed to execute DDL in file " + scriptFile.getName() + "ddl: " + ddl);
              }
            }
          }
        }
      }
    }
  }

  @TestTemplate
  public void testRecoverySchemaParityAndRuntimeAfterUpgrade() throws Exception {
    Path scriptDir = scriptDirectory();

    dropAllTables();
    executeScript(scriptDir.resolve("schema-1.3.0-" + backendType + ".sql"));
    executeScript(scriptDir.resolve("upgrade-1.3.0-to-2.0.0-" + backendType + ".sql"));
    Map<String, String> upgradedSchema = recoverySchemaSignature();
    assertTableRecoveryWorksOnUpgradedSchema();

    dropAllTables();
    executeScript(scriptDir.resolve("schema-2.0.0-" + backendType + ".sql"));
    Assertions.assertEquals(upgradedSchema, recoverySchemaSignature());
  }

  private Path scriptDirectory() {
    String gravitinoHome = System.getenv("GRAVITINO_HOME");
    Assertions.assertNotNull(gravitinoHome, "GRAVITINO_HOME environment variable is not set");
    return Path.of(gravitinoHome, "scripts", backendType.toLowerCase(Locale.ROOT));
  }

  private void executeScript(Path script) throws IOException, SQLException {
    Assertions.assertTrue(Files.isRegularFile(script), "No SQL script found at " + script);
    List<String> ddls = extractStatements(script);
    try (SqlSession sqlSession =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = sqlSession.getConnection();
        Statement statement = connection.createStatement()) {
      for (String ddl : ddls) {
        Assertions.assertDoesNotThrow(
            () -> statement.execute(ddl),
            "Failed to execute DDL in file " + script.getFileName() + " ddl: " + ddl);
      }
    }
  }

  private Map<String, String> recoverySchemaSignature() throws SQLException {
    Map<String, String> signature = new HashMap<>();
    try (SqlSession sqlSession =
            SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true);
        Connection connection = sqlSession.getConnection()) {
      DatabaseMetaData metadata = connection.getMetaData();
      try (ResultSet columns = metadata.getColumns(connection.getCatalog(), null, "%", "%")) {
        while (columns.next()) {
          String table = columns.getString("TABLE_NAME").toLowerCase(Locale.ROOT);
          String column = columns.getString("COLUMN_NAME").toLowerCase(Locale.ROOT);
          if ("deletion_id".equals(column) || "entity_deletion".equals(table)) {
            signature.put(
                table + "." + column,
                columns.getInt("DATA_TYPE")
                    + ":"
                    + columns.getInt("COLUMN_SIZE")
                    + ":"
                    + columns.getInt("NULLABLE"));
          }
        }
      }
    }

    Set<String> expectedColumns = new HashSet<>();
    DELETION_ID_TABLES.forEach(table -> expectedColumns.add(table + ".deletion_id"));
    ENTITY_DELETION_COLUMNS.forEach(column -> expectedColumns.add("entity_deletion." + column));
    Assertions.assertEquals(expectedColumns, signature.keySet());
    return signature;
  }

  private void assertTableRecoveryWorksOnUpgradedSchema() throws Exception {
    String metalakeName = "migration_recovery_metalake";
    String catalogName = "migration_recovery_catalog";
    String schemaName = "migration_recovery_schema";
    String tableName = "migration_recovery_table";
    createAndInsertMakeLake(metalakeName);
    createAndInsertCatalog(metalakeName, catalogName);
    createAndInsertSchema(metalakeName, catalogName, schemaName);
    Namespace namespace = NamespaceUtil.ofTable(metalakeName, catalogName, schemaName);
    TableEntity table = createAndInsertTableEntity(namespace, tableName);

    Assertions.assertTrue(
        TableMetaService.getInstance().deleteTable(table.nameIdentifier(), RETENTION_MS));
    RecoverableDeletionManager manager = new RecoverableDeletionManager(RETENTION_MS);
    DeletedEntityDTO deleted = manager.getDeletedTable(namespace, tableName, table.id());
    initializeLockManager();
    Assertions.assertEquals(
        table.id(),
        manager.restoreDeletedTable(namespace, tableName, table.id(), deleted.getEtag()).id());
    Assertions.assertTrue(backend.exists(table.nameIdentifier(), table.type()));
  }

  private void initializeLockManager() throws IllegalAccessException {
    Config config = GravitinoEnv.getInstance().config();
    Mockito.when(config.get(TREE_LOCK_MAX_NODE_IN_MEMORY)).thenReturn(100_000L);
    Mockito.when(config.get(TREE_LOCK_MIN_NODE_IN_MEMORY)).thenReturn(1_000L);
    Mockito.when(config.get(TREE_LOCK_CLEAN_INTERVAL)).thenReturn(36_000L);
    FieldUtils.writeField(GravitinoEnv.getInstance(), "lockManager", new LockManager(config), true);
  }

  private List<String> extractStatements(Path sqlFile) throws IOException {
    String executableSql =
        Files.readAllLines(sqlFile).stream()
            .map(String::trim)
            .filter(line -> !line.isEmpty())
            .filter(line -> !line.startsWith("--"))
            .reduce((left, right) -> left + "\n" + right)
            .orElse("");

    return Arrays.stream(executableSql.split(";"))
        .map(String::trim)
        .filter(s -> !s.isEmpty())
        .toList();
  }

  private void dropAllTables() throws SQLException {
    try (SqlSession sqlSession =
        SqlSessionFactoryHelper.getInstance().getSqlSessionFactory().openSession(true)) {
      try (Connection connection = sqlSession.getConnection()) {
        if ("postgresql".equals(backendType)) {
          dropAllTablesForPostgreSQL(connection);
        } else {
          dropAllTablesForMySQLCompatible(connection);
        }
      }
    }
  }

  private void dropAllTablesForMySQLCompatible(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement()) {
      String query = "SHOW TABLES";
      List<String> tableList = new ArrayList<>();
      try (ResultSet rs = statement.executeQuery(query)) {
        while (rs.next()) {
          tableList.add(rs.getString(1));
        }
      }
      for (String table : tableList) {
        statement.execute("DROP TABLE " + table);
      }
    }
  }

  private void dropAllTablesForPostgreSQL(Connection connection) throws SQLException {
    String query =
        "SELECT table_name FROM information_schema.tables WHERE table_schema = current_schema()";
    List<String> tableList = new ArrayList<>();
    try (ResultSet rs = connection.createStatement().executeQuery(query)) {
      while (rs.next()) {
        tableList.add(rs.getString(1));
      }
    }

    if (tableList.isEmpty()) {
      return;
    }

    for (String table : tableList) {
      connection.createStatement().execute("DROP TABLE " + table);
    }
  }
}
