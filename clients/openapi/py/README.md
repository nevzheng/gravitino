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

# Apache Gravitino OpenAPI Python client

`gravitino_client` is the production Python client generated from Apache
Gravitino's V1 OpenAPI contract. It is a separate package from the existing
hand-written `gravitino` client for the legacy, unversioned API.

Install the development checkout with:

```bash
pip install --editable clients/openapi/py
```

For contract changes, regenerate its source rather than editing files under
`gravitino_client` directly:

```bash
./gradlew :clients:openapi:regeneratePythonClient
```
