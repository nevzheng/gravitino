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

# Gravitino OpenAPI clients

This directory contains production client libraries generated from Gravitino's
authoritative OpenAPI contracts. It is intentionally separate from
[`clients/client-python`](../client-python), the existing hand-written legacy
Python client. Both packages can be installed in the same Python environment.

## Python V1 client

[`py/gravitino_client`](./py/gravitino_client) is generated from
[`docs/open-api/v1`](../../docs/open-api/v1). Its distribution name is
`apache-gravitino-openapi`; its import namespace is `gravitino_client`.

The package source is checked in so it can be reviewed and released as a normal
Python library. Do not edit generated files by hand. Regenerate and verify them
with:

```bash
./gradlew :clients:openapi:regeneratePythonClient
./gradlew :clients:openapi:verifyGeneratedPythonClient
./gradlew -PskipWeb=true compileDistribution -x test
./gradlew :clients:openapi:pytestEndpoint
```

`OpenAPI Generator` is pinned in the repository version catalog. The
regeneration task uses the bundled V1 contract, so external references and
generator inputs are reproducible. The endpoint contract task uses the
prebuilt distribution, keeping the Python suite independent from unrelated
server-module test execution.

By default, the endpoint task starts and stops its own local server. To target
an externally managed server instead, pass an explicit base URL; the task will
not attempt to own its lifecycle:

```bash
./gradlew -PgravitinoOpenapiBaseUrl=http://localhost:8090 \
  :clients:openapi:pytestEndpointContract
```

The first E2E slice will exercise this package against a real Gravitino server.
A future `gravitino_cli` package may provide a hand-written command-line or
interactive interface over this library; it is not generated and is deliberately
out of scope for the library's initial API surface.

Python API tests live beneath [`py/test`](./py/test). The reusable
`gravitino_testkit` package provides shared fixtures and assertions. Endpoint
tests are grouped into `endpoint/unit/` (no server), `endpoint/contract/`
(real server), and the future opt-in `endpoint/fuzz/` suite. `cuj/` is reserved
for multi-endpoint customer-journey tests.

When a test describes a confirmed contract or generated-client gap, make the
gap visible rather than silently skipping it:

```python
import pytest


@pytest.mark.xfail(
    strict=True,
    reason=(
        "OpenAPI Generator 7.23.0 cannot deserialize this valid recursive value; "
        "tracked in https://github.com/apache/gravitino/issues/12345"
    ),
)
def test_recursive_literal_response():
    ...
```

The `reason` must name the failure and its tracking issue or TODO. A strict
`pytest.xfail` is non-blocking while the confirmed generated-client or server
contract gap is known, but an unexpected pass fails CI so the marker must be
removed or reclassified. Tests for behavior expected to work remain blocking.
