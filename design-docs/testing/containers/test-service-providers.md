<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->


# SPIP: Test service providers

| | |
|---|---|
| **Status** | Draft. Proof of concept for MySQL and PostgreSQL stacked on this document |
| **Issue** | [apache/gravitino#13962](https://github.com/apache/gravitino/issues/13962), part of [#13900](https://github.com/apache/gravitino/issues/13900) |
| **Scope** | `integration-test-common` container classes, `ContainerSuite`, tests that use them, CI |
| **Format** | Apache Spark SPIP (Heilmeier Catechism) |
| **Related** | Test container framework design (`@<Service>Fixture`, `ContainerExtension`, `<Service>Spec`) in this folder |

## Q1. What are you trying to do?

Let a test ask for a service it needs (MySQL, PostgreSQL, Hive, Kafka, S3-compatible storage,
Trino and others) and get it from whatever the environment offers: a Testcontainers container
by default, or a service that already runs, such as a Kubernetes service, a CI service container
or a shared server. The test does not know or care which.

## Q2. What problem is this proposal NOT designed to solve?

1. **Running Gravitino's tests on Kubernetes.** This adds the seam; it ships no Kubernetes
   manifests, operator or pod-creating provider.
2. **Container-to-container traffic.** Trino, Spark and Flink containers that dial a database by
   network alias keep doing so. External services for those tests come later (task 3).
3. **Which images and versions to use.** That stays with `TestImages` in the framework design.
4. **Test isolation inside a service.** Database reset stays with `@DatabaseFixture`.

## Q3. How is it done today, and what are the limits of current practice?

- Every service comes from `ContainerSuite`, which starts a Testcontainers container on the
  `gravitino-ci-network` Docker network. There is no other source.
- Tests hold the container class (`MySQLContainer`, `HiveContainer`, ...), not an interface, and
  most reach it by container IP (about 92 files) rather than host and mapped port (9 files).
- Core database tests hard-code the `root/root` login.

Limits: tests cannot run where Docker is unavailable or a service is already provided (Kubernetes
pods, CI service containers); macOS needs special Docker networking; each service type would need
its own ad hoc switch. Draft PR #13838 added such a switch for the core database tests through
environment variables, with no shared abstraction.

## Q4. What is new in your approach, and why do you think it will be successful?

Three small pieces, modelled on Spring Boot's `ConnectionDetails` (one interface per service,
filled from a container or from properties) and Quarkus Dev Services (start a container only when
the service is not configured):

1. **Endpoint per service.** `ServiceEndpoint` is the marker; each service has a subtype with what
   tests need, e.g. `DatabaseEndpoint` (JDBC URL, login, driver, create database). Container
   classes implement it, and so do external implementations.
2. **`ServiceSpec<E>`** names a service and its endpoint type (`ServiceSpec.MYSQL`). It is the key
   the framework design's `<Service>Spec` uses.
3. **`ServiceProvider`** supplies services: `Optional<E> provide(ServiceSpec<E>)`. `ContainerSuite`
   asks providers in a fixed order and keeps the first answer per spec:
   1. providers found through `java.util.ServiceLoader` (plug-ins, e.g. a Kubernetes provider);
   2. `ExternalServiceProvider`: services named by `gravitino.test.<service>.*` properties;
   3. `ContainerServiceProvider`: today's containers (the default).

Tests get endpoints through `ContainerSuite.getService(spec)` now, and through
`@<Service>Fixture` parameters once `ContainerExtension` exists; the extension asks the same
providers. Existing tests keep working unchanged.

### Alternatives considered

| Approach | Pros | Cons | Decision |
|---|---|---|---|
| Per-service env-var switch (#13838) | Smallest change | No shared seam; each service re-implements it | Rejected |
| Testcontainers only, with remote Docker or Kubernetes Docker-in-Docker | No new code | Still needs a Docker daemon; does not use services the environment runs | Rejected |
| A DI container (Spring, Guice) | Mature injection | Heavy dependency for tests; JUnit 5 `ParameterResolver` does the injection we need | Rejected |
| Static field injection like Testcontainers `@Container` | Familiar | Ties tests to container classes | Rejected |
| Provider chain + endpoint interfaces (this) | Small, pluggable, tests unchanged | Endpoint types per service to write | **Chosen** |

## Q5. Who cares? If you are successful, what difference will it make?

Contributors and CI can run database tests without Docker; teams running tests on Kubernetes can
plug in their own provider without changing Gravitino; macOS developers need less Docker
networking once tests use host and mapped port.

## Q6. What are the risks?

- **Shared servers.** External services may be shared, so a run names what it creates with a
  random per-JVM token and removes only that (`ExternalDatabase.close()`).
- **Silent fallback.** If the external settings are wrong, a test must fail, not fall back to
  Docker. `ExternalDatabase` fails on a bad URL or missing user; the CI job checks that no
  container started.
- **Version drift.** An external server may differ from the pinned image. The run logs
  `[TEST-SERVICE-SOURCE] service=<name> provider=<name>` once per service.

## Q7. How long will it take?

One PR per task below; the database step is small (proof of concept stacked on this document).

## Q8. What are the mid-term and final "exams" to check for success?

- Mid-term: `coreMySQLTest` and `corePostgreSQLTest` pass against GitHub Actions service
  containers with Docker tests off, and start no container (`core-test-external-db` job).
- Final: every service in `ContainerSuite` has an endpoint type and an external implementation,
  and a test module can run with all its services external.

## Appendix A: API

```java
public interface ServiceEndpoint {}

public interface DatabaseEndpoint extends ServiceEndpoint {
  String getJdbcUrl(TestDatabaseName name);
  String getUsername();
  String getPassword();
  String getDriverClassName(TestDatabaseName name) throws SQLException;
  void createDatabase(TestDatabaseName name);
  default String getDatabaseName(TestDatabaseName name) { return name.toString(); }
}

public final class ServiceSpec<E extends ServiceEndpoint> {
  public static final ServiceSpec<DatabaseEndpoint> MYSQL = ...;
  public static final ServiceSpec<DatabaseEndpoint> POSTGRESQL = ...;
}

public interface ServiceProvider {
  String name();
  <E extends ServiceEndpoint> Optional<E> provide(ServiceSpec<E> spec);
}

// ContainerSuite
public <E extends ServiceEndpoint> E getService(ServiceSpec<E> spec);
```

Settings for the external provider, passed by the core lanes from the environment at execution
time so they stay out of the build cache key:

| Environment variable | System property | Meaning |
|---|---|---|
| `GRAVITINO_TEST_MYSQL_URL` | `gravitino.test.mysql.url` | Server JDBC URL, no database |
| `GRAVITINO_TEST_MYSQL_USER` | `gravitino.test.mysql.user` | User allowed to create databases |
| `GRAVITINO_TEST_MYSQL_PASSWORD` | `gravitino.test.mysql.password` | Its password |

PostgreSQL uses the same names with `POSTGRESQL` / `postgresql`.

## Appendix B: Adding a service

1. Add its endpoint interface (e.g. `KafkaEndpoint` with bootstrap servers) and make the
   container class implement it, reached by host and mapped port.
2. Add a `ServiceSpec` constant.
3. Add its external implementation and register it in `ExternalServiceProvider`.
4. Register the container in `ContainerServiceProvider`.
5. Move tests from the container class to the endpoint.

## Task breakdown

1. [ ] This document.
2. [ ] Provider layer with MySQL and PostgreSQL; core lanes and `core-test-external-db` CI job
   (proof of concept stacked on this document; supersedes #13838).
3. [ ] Reach services from the test JVM by host and mapped port instead of container IP, one
   module per PR (prerequisite for non-database services).
4. [ ] Pilot one non-database service (Kafka or Hive Metastore).
5. [ ] `ContainerExtension` asks the provider chain for `@<Service>Fixture` parameters.
6. [ ] Remaining services, one PR each.
