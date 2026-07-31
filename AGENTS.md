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

# Gravitino Agent Guidelines

## General Coding Standards
- **Language**: Use English for all code, comments, and documentation.
- **Style**: Follow rigid Google Java Style. Run `./gradlew spotlessApply` to format.
- **Javadoc**: All new `public` and `protected` classes, methods, and fields must have Javadoc. Missing Javadoc fails the checkstyle CI step (runs with `-Werror`).
- **Imports**: Always use normal `import` statements instead of Fully Qualified Class Names (FQN) in Java code whenever possible.
  - **Bad**: `org.apache.gravitino.rel.Table table = ...;`
  - **Good**: `Table table = ...;` (with `import org.apache.gravitino.rel.Table;`)
  - Do not write inline types like `java.nio.file.Paths` or `org.apache.xxx.Table` unless there is a real class name conflict that cannot be resolved cleanly.
  - If two classes share the same simple name, prefer imports plus small refactors over keeping FQNs throughout the code.
- **Safety**: Use `@Nullable` annotations. Handle resources with try-with-resources.
- **Logging**: Use SLF4J. No `System.out.println`.
- **Testing**:
  - Write unit tests for ALL new logic. NO tests = NO merge.
  - Use `TestXxx` naming pattern (e.g., `TestCatalogService`).
  - Run tests: `./gradlew test -PskipITs`.
  - Docker tests: Tag with `@Tag("gravitino-docker-test")`; run them with `-PskipDockerTests=false`.
- **Class Member Ordering**: Follow the order:
  1. `static` constants (e.g., `LOG`).
  2. `static` fields.
  3. Instance fields.
  4. Constructors.
  5. Methods (Group by visibility, putting `private` methods at the end).

## Create Issue and PR Guidelines
[IMPORTANT] Before creating an issue or PR using the gh command or the GitHub MCP server, please show a preview of the PR/issue first. Submit it only after I confirm. The issue/PR format should follow the reference and keep the content concise and clear.
- **Issue Templates**: Use the appropriate template from `.github/ISSUE_TEMPLATE/`
- **PR Description**: Follow the template in `.github/PULL_REQUEST_TEMPLATE`

## Project Structure
- `api/`: Public interfaces.
- `common/`: Shared utilities/DTOs.
- `core/`: Main server logic.
- `server/`: REST API implementation.
- `catalogs/`: Catalog implementations (Hive, Iceberg, MySQL, etc.).
- `clients/`: Java/Python clients.

## Build Commands
- **Build**: `./gradlew build -PskipDockerTests=false`
- **Format**: `./gradlew spotlessApply`
- **Unit Tests**: `./gradlew test -PskipITs -PskipDockerTests=false`
- **Integration Tests**: `./gradlew test -PskipTests -PskipDockerTests=false`
- **OpenAPI Docs Validation**: `./gradlew :docs:build` — Run this after any changes to `docs/open-api/*.yaml` to validate OpenAPI specification correctness.

## Claude Memory Usage
- Before starting any task, use mcp-search to check if similar work has been done before.
  When encountering unfamiliar code or configuration, search memory for prior context.
- When hitting a problem, search memory first for known solutions before debugging from scratch.
- After completing a task, save key findings and solutions to claude-mem for future reference.
- Use multiple keyword combinations when searching (e.g., module name + issue type, class name + error).

## Cursor Cloud specific instructions

These notes describe non-obvious details for running this repo in the Cursor Cloud VM. Standard build/test commands are documented above and in `docs/how-to-build.md`.

- **JDK**: The VM's default `java`/`javac` are set to **JDK 17** (via `update-alternatives`). This is required because Gradle 8.2 (see `gradle/wrapper/gradle-wrapper.properties`) does **not** support running under JDK 21+. The VM also has JDK 21 installed; do **not** launch Gradle with JDK 21. If a command uses the wrong JDK, run it with `JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64` (also exported in the agent's `~/.bashrc`). Gradle's Java Toolchain still auto-downloads other JDKs it needs for specific modules (e.g. the Trino connector).
- **Server (primary product)**: Build a runnable distribution with `./gradlew compileDistribution -x test`, then run from `distribution/package`:
  - Start (background): `./bin/gravitino.sh start` — Stop: `./bin/gravitino.sh stop` — Status: `./bin/gravitino.sh status` — Foreground (dev): `./bin/gravitino.sh run`.
  - Logs: `distribution/package/logs/gravitino-server.out`.
  - The server listens on **http://localhost:8090** and serves both the REST API (`/api/...`) and the bundled Web UI. It uses an **embedded H2** entity store by default, so **no external database/service is required** to run and exercise the metadata API + UI end to end. Health check: `curl http://localhost:8090/api/health`.
  - `./gradlew clean` deletes the `distribution/` directory, so re-run `compileDistribution` after a clean.
- **Web UI dev (hot-reload)**: only needed when editing the front-end; otherwise the UI is already bundled into the server at 8090. Run `pnpm install && pnpm dev` in `web-v2/web` (Next.js dev server on **:3000**, proxies `/api` to the server at :8090 via `NEXT_PUBLIC_API_URL`). `web/web` is the legacy v1 UI.
- **Docker-based integration tests are skipped by default** (`skipDockerTests=true`); Docker is not set up in this VM. Catalogs like Hive/Kafka/JDBC and query-engine connectors (Trino/Spark/Flink) only need their external services for those specific integration tests, not for running the server.
