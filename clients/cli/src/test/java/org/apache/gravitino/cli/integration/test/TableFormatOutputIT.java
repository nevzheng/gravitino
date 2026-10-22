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
package org.apache.gravitino.cli.integration.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.apache.gravitino.cli.GravitinoOptions;
import org.apache.gravitino.cli.Main;
import org.apache.gravitino.cli.commands.Command;
import org.apache.gravitino.integration.test.container.ContainerSuite;
import org.apache.gravitino.integration.test.container.MySQLContainer;
import org.apache.gravitino.integration.test.container.PostgreSQLContainer;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.TestDatabaseName;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("gravitino-docker-test")
public class TableFormatOutputIT extends BaseIT {
  private static final TestDatabaseName PG_DB = TestDatabaseName.PG_CLI_TABLE_FORMAT_IT;
  private static final TestDatabaseName MYSQL_DB = TestDatabaseName.MYSQL_CLI_TABLE_FORMAT_IT;

  private final ContainerSuite containerSuite = ContainerSuite.getInstance();
  private String gravitinoUrl;

  @BeforeAll
  public void startUp() throws SQLException {
    // Fail the test, rather than end the test JVM, when a command fails.
    Main.useExit = false;
    gravitinoUrl = String.format("http://127.0.0.1:%d", getGravitinoServerPort());

    containerSuite.startPostgreSQLContainer(PG_DB);
    PostgreSQLContainer postgres = containerSuite.getPostgreSQLContainer();
    String postgresProperties =
        String.format(
            "jdbc-url=%s,jdbc-user=%s,jdbc-password=%s,jdbc-database=%s,jdbc-driver=%s",
            postgres.getJdbcUrl(PG_DB),
            postgres.getUsername(),
            postgres.getPassword(),
            PG_DB,
            postgres.getDriverClassName(PG_DB));

    containerSuite.startMySQLContainer(MYSQL_DB);
    MySQLContainer mysql = containerSuite.getMySQLContainer();
    String mysqlJdbcUrl = mysql.getJdbcUrl(MYSQL_DB);
    String mysqlProperties =
        String.format(
            "jdbc-url=%s,jdbc-user=%s,jdbc-password=%s,jdbc-driver=%s",
            mysqlJdbcUrl.substring(0, mysqlJdbcUrl.lastIndexOf("/")),
            mysql.getUsername(),
            mysql.getPassword(),
            mysql.getDriverClassName(MYSQL_DB));
    String[] create_metalake_args = {
      "metalake",
      "create",
      commandArg(GravitinoOptions.METALAKE),
      "my_metalake",
      commandArg(GravitinoOptions.COMMENT),
      "my metalake",
      commandArg(GravitinoOptions.URL),
      gravitinoUrl
    };
    Main.main(create_metalake_args);

    String[] create_catalog_args = {
      "catalog",
      "create",
      commandArg(GravitinoOptions.METALAKE),
      "my_metalake",
      commandArg(GravitinoOptions.NAME),
      "postgres",
      commandArg(GravitinoOptions.PROVIDER),
      "postgres",
      commandArg(GravitinoOptions.PROPERTIES),
      postgresProperties,
      commandArg(GravitinoOptions.URL),
      gravitinoUrl
    };
    Main.main(create_catalog_args);

    String[] create_catalog_with_comment_args = {
      "catalog",
      "create",
      commandArg(GravitinoOptions.METALAKE),
      "my_metalake",
      commandArg(GravitinoOptions.NAME),
      "postgres2",
      commandArg(GravitinoOptions.PROVIDER),
      "postgres",
      commandArg(GravitinoOptions.PROPERTIES),
      postgresProperties,
      commandArg(GravitinoOptions.URL),
      gravitinoUrl,
      commandArg(GravitinoOptions.COMMENT),
      "catalog, 用于测试"
    };
    Main.main(create_catalog_with_comment_args);

    String[] create_mysql_catalog_args = {
      "catalog",
      "create",
      commandArg(GravitinoOptions.METALAKE),
      "my_metalake",
      commandArg(GravitinoOptions.NAME),
      "mysql",
      commandArg(GravitinoOptions.PROVIDER),
      "mysql",
      commandArg(GravitinoOptions.PROPERTIES),
      mysqlProperties,
      commandArg(GravitinoOptions.URL),
      gravitinoUrl
    };
    Main.main(create_mysql_catalog_args);
  }

  @AfterAll
  public void restoreExit() {
    Main.useExit = true;
  }

  @Test
  public void testMetalakeListCommand() {
    // Create a byte array output stream to capture the output of the command
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(outputStream));

    String[] args = {
      "metalake",
      "list",
      commandArg(GravitinoOptions.OUTPUT),
      Command.OUTPUT_FORMAT_TABLE,
      commandArg(GravitinoOptions.URL),
      gravitinoUrl
    };
    Main.main(args);

    // Restore the original System.out
    System.setOut(originalOut);
    // Get the output and verify it
    String output = new String(outputStream.toByteArray(), StandardCharsets.UTF_8).trim();
    assertEquals(
        "+-------------+\n"
            + "|  Metalake   |\n"
            + "+-------------+\n"
            + "| my_metalake |\n"
            + "+-------------+",
        output);
  }

  @Test
  public void testMetalakeDetailsCommand() {
    // Create a byte array output stream to capture the output of the command
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(outputStream));

    String[] args = {
      "metalake",
      "details",
      commandArg(GravitinoOptions.METALAKE),
      "my_metalake",
      commandArg(GravitinoOptions.OUTPUT),
      Command.OUTPUT_FORMAT_TABLE,
      commandArg(GravitinoOptions.URL),
      gravitinoUrl
    };
    Main.main(args);

    // Restore the original System.out
    System.setOut(originalOut);
    // Get the output and verify it
    String output = new String(outputStream.toByteArray(), StandardCharsets.UTF_8).trim();
    assertEquals(
        "+-------------+-------------+\n"
            + "|  Metalake   |   Comment   |\n"
            + "+-------------+-------------+\n"
            + "| my_metalake | my metalake |\n"
            + "+-------------+-------------+",
        output);
  }

  @Test
  public void testCatalogListCommand() {
    // Create a byte array output stream to capture the output of the command
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(outputStream));

    String[] args = {
      "catalog",
      "list",
      commandArg(GravitinoOptions.METALAKE),
      "my_metalake",
      commandArg(GravitinoOptions.OUTPUT),
      Command.OUTPUT_FORMAT_TABLE,
      commandArg(GravitinoOptions.URL),
      gravitinoUrl
    };
    Main.main(args);

    // Restore the original System.out
    System.setOut(originalOut);
    // Get the output and verify it
    String output = new String(outputStream.toByteArray(), StandardCharsets.UTF_8).trim();
    assertEquals(
        "+-----------+\n"
            + "|  Catalog  |\n"
            + "+-----------+\n"
            + "| mysql     |\n"
            + "| postgres  |\n"
            + "| postgres2 |\n"
            + "+-----------+",
        output);
  }

  @Test
  public void testCatalogDetailsCommand() {
    // Create a byte array output stream to capture the output of the command
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(outputStream));

    String[] args = {
      "catalog",
      "details",
      commandArg(GravitinoOptions.METALAKE),
      "my_metalake",
      commandArg(GravitinoOptions.NAME),
      "postgres",
      commandArg(GravitinoOptions.OUTPUT),
      "table",
      commandArg(GravitinoOptions.URL),
      gravitinoUrl
    };
    Main.main(args);

    // Restore the original System.out
    System.setOut(originalOut);
    // Get the output and verify it
    String output = new String(outputStream.toByteArray(), StandardCharsets.UTF_8).trim();
    assertEquals(
        "+----------+------------+-----------------+---------+\n"
            + "| Catalog  |    Type    |    Provider     | Comment |\n"
            + "+----------+------------+-----------------+---------+\n"
            + "| postgres | RELATIONAL | jdbc-postgresql | null    |\n"
            + "+----------+------------+-----------------+---------+",
        output);
  }

  @Test
  public void testCatalogDetailsCommandFullCornerCharacter() {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(outputStream));

    String[] args = {
      "catalog",
      "details",
      commandArg(GravitinoOptions.METALAKE),
      "my_metalake",
      commandArg(GravitinoOptions.NAME),
      "postgres2",
      commandArg(GravitinoOptions.OUTPUT),
      "table",
      commandArg(GravitinoOptions.URL),
      gravitinoUrl
    };
    Main.main(args);
    // Restore the original System.out
    System.setOut(originalOut);
    // Get the output and verify it
    String output = new String(outputStream.toByteArray(), StandardCharsets.UTF_8).trim();
    assertEquals(
        "+-----------+------------+-----------------+-------------------+\n"
            + "|  Catalog  |    Type    |    Provider     |      Comment      |\n"
            + "+-----------+------------+-----------------+-------------------+\n"
            + "| postgres2 | RELATIONAL | jdbc-postgresql | catalog, 用于测试 |\n"
            + "+-----------+------------+-----------------+-------------------+",
        output);
  }

  @Test
  public void testMySQLCatalogDetailsCommand() {
    ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
    PrintStream originalOut = System.out;
    System.setOut(new PrintStream(outputStream));

    String[] args = {
      "catalog",
      "details",
      commandArg(GravitinoOptions.METALAKE),
      "my_metalake",
      commandArg(GravitinoOptions.NAME),
      "mysql",
      commandArg(GravitinoOptions.OUTPUT),
      "table",
      commandArg(GravitinoOptions.URL),
      gravitinoUrl
    };
    Main.main(args);

    System.setOut(originalOut);
    String output = new String(outputStream.toByteArray(), StandardCharsets.UTF_8).trim();
    assertEquals(
        "+---------+------------+------------+---------+\n"
            + "| Catalog |    Type    |  Provider  | Comment |\n"
            + "+---------+------------+------------+---------+\n"
            + "| mysql   | RELATIONAL | jdbc-mysql | null    |\n"
            + "+---------+------------+------------+---------+",
        output);
  }

  private String commandArg(String arg) {
    return String.format("--%s", arg);
  }
}
