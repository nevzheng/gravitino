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

## Guidance Map

`AGENTS.md` is the shared baseline for repository agents. Load only the
additional guidance relevant to the task:

- **GitHub artifacts**: Follow the GitHub Artifact Workflow below.
- **Design documents**: Read and apply
  `.claude/skills/gravitino-design-doc/SKILL.md` for document content.
- **Documentation style refinement**: Read and apply
  `.claude/skills/gravitino-docs-refine/SKILL.md` and its `STYLE.md` when
  refining Markdown structure or style under `docs/`.
- **Explicit operational tasks**: When the user asks to manage a release, read
  and apply `agent-skills/gravitino-release/SKILL.md`. For Trino integration
  tests, read and apply `agent-skills/trino-test/SKILL.md`. Do not invoke the
  release workflow for unrelated tasks.

Agent-specific entry points must route to this file and should contain only
tool-specific additions or a minimal fallback for surfaces that do not load it.
Shared safety and approval rules in this file still apply when more specific
guidance is loaded. Native templates and forms control artifact structure,
task-specific guides control domain content, and the GitHub writing guide
controls artifact framing. Do not duplicate those guides here.

## General Coding Standards
- **Language**: Use English for all code, comments, and documentation.
- **Style**: Follow rigid Google Java Style. Run `./gradlew spotlessApply` to format.
- **Python**: Follow PEP 8 and use type hints.
- **Dependencies**: Do not add a dependency without explicit user approval.
- **Javadoc**: All new `public` and `protected` classes, methods, and fields must have Javadoc. Missing Javadoc fails the checkstyle CI step (runs with `-Werror`).
- **Imports**: Always use normal `import` statements instead of Fully Qualified Class Names (FQN) in Java code whenever possible.
  - **Bad**: `org.apache.gravitino.rel.Table table = ...;`
  - **Good**: `Table table = ...;` (with `import org.apache.gravitino.rel.Table;`)
  - Do not write inline types like `java.nio.file.Paths` or `org.apache.xxx.Table` unless there is a real class name conflict that cannot be resolved cleanly.
  - If two classes share the same simple name, prefer imports plus small refactors over keeping FQNs throughout the code.
- **Safety**: Use `@Nullable` annotations, validate Java arguments with
  `Preconditions.checkArgument`, catch specific exceptions instead of generic
  `Exception`, and handle resources with try-with-resources.
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

## GitHub Artifact Workflow

- **Structure and style**: Before drafting or revising a pull request title and
  description, GitHub Issue, or GitHub Discussion, read the applicable native
  template or form and `.github/WRITING_GUIDE.md`. Use
  `.github/PULL_REQUEST_TEMPLATE.md` by default and
  `.github/PULL_REQUEST_TEMPLATE/design_document.md` only for its documented
  design-only route. Select the appropriate Issue form from
  `.github/ISSUE_TEMPLATE/`, and preserve supplied Discussion category or
  template requirements. Preserve the selected title syntax, required fields,
  headings, and order.
- **Conciseness**: Apply only the guide sections relevant to the artifact, then
  run its Conciseness Pass before showing a complete title and body.
- **Preview and approval**: Before creating a pull request, Issue, or
  Discussion, or externally updating its title or body, show the exact proposed
  title and body and wait for explicit user approval.
- **Freshness**: When a title or body exists, after significant code changes or
  material changes to scope, behavior, user-facing boundaries, decisions,
  issue or stack relationships, or verification evidence, compare it with the
  current state before requesting review or publishing an update. If it may be
  stale, warn the user and show a refreshed, concise preview first.

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
