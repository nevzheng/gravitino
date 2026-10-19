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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.integration.test.util.TestDatabaseName;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A MySQL or PostgreSQL server the environment provides, such as a Kubernetes service or a CI
 * service container. Selected by the system property {@code gravitino.test.<engine>.url}, with
 * {@code .user} and {@code .password}; no container is started for that engine.
 *
 * <p>The server may outlive the run and be shared, so every database name gets a random token
 * unique to this JVM, and {@link #close()} drops only the databases this JVM created.
 */
public final class ExternalDatabase implements DatabaseEndpoint, AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(ExternalDatabase.class);

  private static final String PROPERTY_PREFIX = "gravitino.test.";

  private static final Pattern DATABASE_NAME = Pattern.compile("[a-z0-9_]{1,63}");

  /** A database engine an external server can run. */
  public enum Engine {
    /** MySQL. */
    MYSQL("mysql", "`"),
    /** PostgreSQL. */
    POSTGRESQL("postgresql", "\"");

    private final String scheme;
    private final String quote;

    Engine(String scheme, String quote) {
      this.scheme = scheme;
      this.quote = quote;
    }

    /** The system property naming this engine's server URL. */
    public String urlProperty() {
      return PROPERTY_PREFIX + scheme + ".url";
    }

    private String quoted(String name) {
      return quote + name + quote;
    }
  }

  private final Engine engine;
  private final URI serverUri;
  private final String username;
  private final String password;
  private final String token;
  private final List<String> createdDatabases = new ArrayList<>();

  @VisibleForTesting
  ExternalDatabase(Engine engine, String url, String username, String password, String token) {
    Preconditions.checkArgument(
        url.startsWith("jdbc:" + engine.scheme + "://"),
        "%s must be a jdbc:%s:// URL, got %s",
        engine.urlProperty(),
        engine.scheme,
        url);
    this.engine = engine;
    this.serverUri = URI.create(url.substring("jdbc:".length()));
    this.username = username;
    this.password = password;
    this.token = token;
  }

  /**
   * Returns the external server for {@code engine} named by system properties, or empty when {@link
   * Engine#urlProperty()} is not set.
   */
  public static Optional<ExternalDatabase> fromSystemProperties(Engine engine) {
    return from(engine, System::getProperty);
  }

  @VisibleForTesting
  static Optional<ExternalDatabase> from(Engine engine, Function<String, String> properties) {
    String url = properties.apply(engine.urlProperty());
    if (StringUtils.isBlank(url)) {
      return Optional.empty();
    }
    String prefix = PROPERTY_PREFIX + engine.scheme;
    String username = properties.apply(prefix + ".user");
    Preconditions.checkArgument(
        StringUtils.isNotBlank(username), "%s.user must be set with %s", prefix, url);
    String password = StringUtils.defaultString(properties.apply(prefix + ".password"));
    ExternalDatabase database =
        new ExternalDatabase(
            engine,
            url.trim(),
            username,
            password,
            RandomStringUtils.randomAlphanumeric(8).toLowerCase(Locale.ROOT));
    LOG.info(
        "Using the external {} server at {}; no {} container is started",
        engine.scheme,
        url,
        engine.scheme);
    return Optional.of(database);
  }

  /** Returns the engine of this server. */
  public Engine getEngine() {
    return engine;
  }

  @Override
  public String getDatabaseName(TestDatabaseName testDatabaseName) {
    String name = (testDatabaseName + "_" + token).toLowerCase(Locale.ROOT);
    Preconditions.checkArgument(
        DATABASE_NAME.matcher(name).matches(), "Invalid database name: %s", name);
    return name;
  }

  @Override
  public String getJdbcUrl(TestDatabaseName testDatabaseName) {
    return jdbcUrl(getDatabaseName(testDatabaseName));
  }

  @Override
  public String getUsername() {
    return username;
  }

  @Override
  public String getPassword() {
    return password;
  }

  @Override
  public String getDriverClassName(TestDatabaseName testDatabaseName) throws SQLException {
    return DriverManager.getDriver(getJdbcUrl(testDatabaseName)).getClass().getName();
  }

  @Override
  public synchronized void createDatabase(TestDatabaseName testDatabaseName) {
    String name = getDatabaseName(testDatabaseName);
    if (createdDatabases.contains(name)) {
      return;
    }
    String sql =
        engine == Engine.MYSQL
            ? "CREATE DATABASE IF NOT EXISTS " + engine.quoted(name)
            : "CREATE DATABASE " + engine.quoted(name);
    try (Connection connection = connectToServer();
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new IllegalStateException(
          String.format("Failed to create database %s on %s", name, serverUrl()), e);
    }
    createdDatabases.add(name);
    LOG.info("Created database {} on the external {} server", name, engine.scheme);
  }

  /** Drops the databases this JVM created; failures are logged, not thrown. */
  @Override
  public synchronized void close() {
    for (String name : createdDatabases) {
      String sql =
          engine == Engine.MYSQL
              ? "DROP DATABASE IF EXISTS " + engine.quoted(name)
              // Pooled connections from the tests may still be open.
              : "DROP DATABASE IF EXISTS " + engine.quoted(name) + " WITH (FORCE)";
      try (Connection connection = connectToServer();
          Statement statement = connection.createStatement()) {
        statement.execute(sql);
      } catch (SQLException e) {
        LOG.warn("Failed to drop database {} on {}", name, serverUrl(), e);
      }
    }
    createdDatabases.clear();
  }

  @VisibleForTesting
  String serverUrl() {
    return "jdbc:" + serverUri;
  }

  private String jdbcUrl(String database) {
    String query = serverUri.getRawQuery();
    return String.format(
        "jdbc:%s://%s/%s%s",
        engine.scheme, serverUri.getRawAuthority(), database, query == null ? "" : "?" + query);
  }

  private Connection connectToServer() throws SQLException {
    try {
      return DriverManager.getConnection(serverUrl(), username, password);
    } catch (SQLException e) {
      throw new SQLException(
          String.format(
              "Cannot reach the external %s server at %s (set by %s)",
              engine.scheme, serverUrl(), engine.urlProperty()),
          e);
    }
  }
}
