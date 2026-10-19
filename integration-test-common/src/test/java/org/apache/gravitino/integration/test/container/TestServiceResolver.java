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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.apache.gravitino.integration.test.util.CloseableGroup;
import org.apache.gravitino.integration.test.util.TestDatabaseName;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Tests the order in which providers are asked for a service. */
public class TestServiceResolver {

  @Test
  void testFirstProviderThatSuppliesWins() {
    FakeProvider skip = new FakeProvider("skip", null);
    FakeProvider first = new FakeProvider("first", new FakeDatabase());
    FakeProvider second = new FakeProvider("second", new FakeDatabase());
    ServiceResolver resolver = new ServiceResolver(List.of(skip, first, second));

    Assertions.assertSame(first.endpoint, resolver.resolve(ServiceSpec.MYSQL));
    Assertions.assertEquals(List.of(ServiceSpec.MYSQL), skip.asked);
    Assertions.assertTrue(second.asked.isEmpty());
  }

  @Test
  void testEndpointIsKeptUntilCleared() {
    FakeProvider provider = new FakeProvider("only", new FakeDatabase());
    ServiceResolver resolver = new ServiceResolver(List.of(provider));

    resolver.resolve(ServiceSpec.POSTGRESQL);
    resolver.resolve(ServiceSpec.POSTGRESQL);
    Assertions.assertEquals(1, provider.asked.size());

    resolver.clear();
    resolver.resolve(ServiceSpec.POSTGRESQL);
    Assertions.assertEquals(2, provider.asked.size());
  }

  @Test
  void testNoProviderFails() {
    ServiceResolver resolver = new ServiceResolver(List.of(new FakeProvider("skip", null)));
    Assertions.assertThrows(IllegalStateException.class, () -> resolver.resolve(ServiceSpec.MYSQL));
  }

  @Test
  void testExternalProviderNeedsProperties() throws Exception {
    try (CloseableGroup closer = CloseableGroup.create()) {
      ExternalServiceProvider unset = new ExternalServiceProvider(key -> null, closer);
      Assertions.assertTrue(unset.provide(ServiceSpec.MYSQL).isEmpty());

      ExternalServiceProvider set =
          new ExternalServiceProvider(
              key ->
                  switch (key) {
                    case "gravitino.test.mysql.url" -> "jdbc:mysql://db:3306";
                    case "gravitino.test.mysql.user" -> "root";
                    default -> null;
                  },
              closer);
      Assertions.assertTrue(set.provide(ServiceSpec.MYSQL).isPresent());
      Assertions.assertTrue(set.provide(ServiceSpec.POSTGRESQL).isEmpty());
    }
  }

  private static final class FakeProvider implements ServiceProvider {
    private final String name;
    private final ServiceEndpoint endpoint;
    private final List<ServiceSpec<?>> asked = new ArrayList<>();

    private FakeProvider(String name, ServiceEndpoint endpoint) {
      this.name = name;
      this.endpoint = endpoint;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public <E extends ServiceEndpoint> Optional<E> provide(ServiceSpec<E> spec) {
      asked.add(spec);
      return Optional.ofNullable(endpoint).map(spec.endpointType()::cast);
    }
  }

  private static final class FakeDatabase implements DatabaseEndpoint {
    @Override
    public String getJdbcUrl(TestDatabaseName testDatabaseName) {
      return "jdbc:fake:" + testDatabaseName;
    }

    @Override
    public String getUsername() {
      return "user";
    }

    @Override
    public String getPassword() {
      return "password";
    }

    @Override
    public String getDriverClassName(TestDatabaseName testDatabaseName) throws SQLException {
      return "fake.Driver";
    }

    @Override
    public void createDatabase(TestDatabaseName testDatabaseName) {}
  }
}
