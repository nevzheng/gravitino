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
package org.apache.gravitino.integration.test.container;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.integration.test.container.ExternalDatabase.Engine;
import org.apache.gravitino.integration.test.util.TestDatabaseName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests how an external database is selected and how its names and URLs are built. */
public class TestExternalDatabase {

  @Test
  void testNotSelectedWithoutUrl() {
    Assertions.assertTrue(
        ExternalDatabase.from(Engine.MYSQL, Map.<String, String>of()::get).isEmpty());
    Assertions.assertTrue(
        ExternalDatabase.from(Engine.MYSQL, ImmutableMap.of("gravitino.test.mysql.url", " ")::get)
            .isEmpty());
  }

  @Test
  void testSelectedFromProperties() {
    Map<String, String> properties =
        ImmutableMap.of(
            "gravitino.test.postgresql.url", "jdbc:postgresql://db:5432/postgres",
            "gravitino.test.postgresql.user", "admin",
            "gravitino.test.postgresql.password", "secret");
    ExternalDatabase database = ExternalDatabase.from(Engine.POSTGRESQL, properties::get).get();
    Assertions.assertEquals(Engine.POSTGRESQL, database.getEngine());
    Assertions.assertEquals("admin", database.getUsername());
    Assertions.assertEquals("secret", database.getPassword());
  }

  @Test
  void testUserRequiredWithUrl() {
    Map<String, String> properties =
        ImmutableMap.of("gravitino.test.mysql.url", "jdbc:mysql://db:3306");
    Assertions.assertThrows(
        IllegalArgumentException.class, () -> ExternalDatabase.from(Engine.MYSQL, properties::get));
  }

  @Test
  void testUrlMustMatchEngine() {
    Assertions.assertThrows(
        IllegalArgumentException.class,
        () -> new ExternalDatabase(Engine.MYSQL, "jdbc:postgresql://db:5432/", "u", "p", "t"));
  }

  @Test
  void testDatabaseNamesCarryTheToken() {
    ExternalDatabase database =
        new ExternalDatabase(Engine.MYSQL, "jdbc:mysql://db:3306", "u", "p", "ab12cd34");
    Assertions.assertEquals(
        "mysql_jdbc_backend_ab12cd34",
        database.getDatabaseName(TestDatabaseName.MYSQL_JDBC_BACKEND));
  }

  @Test
  void testJdbcUrlKeepsServerAndQuery() {
    ExternalDatabase mysql =
        new ExternalDatabase(
            Engine.MYSQL,
            "jdbc:mysql://db:3306/?useSSL=false&allowPublicKeyRetrieval=true",
            "u",
            "p",
            "t1");
    Assertions.assertEquals(
        "jdbc:mysql://db:3306/mysql_jdbc_backend_t1?useSSL=false&allowPublicKeyRetrieval=true",
        mysql.getJdbcUrl(TestDatabaseName.MYSQL_JDBC_BACKEND));

    ExternalDatabase postgresql =
        new ExternalDatabase(
            Engine.POSTGRESQL, "jdbc:postgresql://db:5432/postgres", "u", "p", "t1");
    Assertions.assertEquals(
        "jdbc:postgresql://db:5432/pg_jdbc_backend_t1",
        postgresql.getJdbcUrl(TestDatabaseName.PG_JDBC_BACKEND));
    Assertions.assertEquals("jdbc:postgresql://db:5432/postgres", postgresql.serverUrl());
  }
}
