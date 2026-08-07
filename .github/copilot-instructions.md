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

# Copilot Instructions for Gravitino

Read and follow the root `AGENTS.md` as the shared repository policy. If it is
not already in context, load it before acting. For pull requests, GitHub Issues,
and GitHub Discussions, load the selected native structure and
`.github/WRITING_GUIDE.md` as routed there. The checks below supplement the
shared policy. Keep overlapping guidance consistent, and surface any conflict.

## Copilot Review Additions

- **License/Legal**: New files must include Apache License 2.0 header; dependency/license changes must update LICENSE/NOTICE as required.
- **Compatibility**: Avoid breaking changes unless explicitly justified and documented (including migrations when needed).
- **Java hygiene**: No wildcard imports; close resources (try-with-resources); do not leave TODO/FIXME without an issue reference.
- **Security**: No hardcoded secrets; validate external inputs; prevent injection/path traversal/SSRF where applicable; do not log sensitive data.
- **APIs**: Public APIs require JavaDoc; REST APIs must enforce authentication/authorization and return correct HTTP status codes with consistent error payloads.
- **Testing**: Cover error paths and edge cases; keep tests isolated/non-flaky; Docker-dependent tests must be tagged with `@Tag("gravitino-docker-test")`.
