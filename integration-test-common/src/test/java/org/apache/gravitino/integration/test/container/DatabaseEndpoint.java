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

import java.sql.SQLException;
import org.apache.gravitino.integration.test.util.TestDatabaseName;

/**
 * A database server tests connect to: a {@link DatabaseContainer} this JVM starts, or an {@link
 * ExternalDatabase} the environment provides. Tests use this type, so they run against either.
 */
public interface DatabaseEndpoint extends ServiceEndpoint {

  /**
   * Returns the JDBC URL of the database for {@code testDatabaseName}, as the test JVM reaches it.
   */
  String getJdbcUrl(TestDatabaseName testDatabaseName);

  /** Returns the user tests connect as. */
  String getUsername();

  /** Returns the password of {@link #getUsername()}. */
  String getPassword();

  /** Returns the JDBC driver class for {@link #getJdbcUrl(TestDatabaseName)}. */
  String getDriverClassName(TestDatabaseName testDatabaseName) throws SQLException;

  /** Creates the database for {@code testDatabaseName} if it does not exist. */
  void createDatabase(TestDatabaseName testDatabaseName);

  /**
   * Returns the name of the database for {@code testDatabaseName} on this server. Equal to {@code
   * testDatabaseName.toString()} unless the server is shared with other runs.
   */
  default String getDatabaseName(TestDatabaseName testDatabaseName) {
    return testDatabaseName.toString();
  }
}
