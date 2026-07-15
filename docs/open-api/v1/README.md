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

# Gravitino V1 API architecture

The V1 OpenAPI surface is an additional inbound adapter. It does not replace,
reinterpret, or silently route through the existing unversioned `/api` API.
Both surfaces remain independently addressable while V1 grows under its
versioned contract.

```mermaid
flowchart LR
  LegacyClient["Existing clients"] --> LegacyRoute["Legacy /api REST adapter"]
  V1Client["V1 clients and generated SDKs"] --> Contract["OpenAPI V1 contract"]
  Contract --> V1Route["V1 /api/v1 JAX-RS adapter"]

  LegacyRoute --> Core{{"Gravitino core domain and dispatchers"}}
  V1Route --> Mapping["V1 wire ↔ API-domain mapping"]
  Mapping --> Core

  Core --> Ports["Catalog / metadata ports"]
  Ports --> Connectors["Catalog connectors and metadata stores"]

```

The V1 adapter owns public HTTP behavior: strict JSON input, V1 resource and
error representations, conditional request handling, and translation of
internal errors into the documented public error domain. The core and connector
layers retain their internal types and capabilities. A V1 route only exposes a
mutation when the mapping can make its behavior explicit and correct; otherwise
it returns the documented capability error rather than falling back to legacy
behavior.

`openapi.yaml` is the authoritative public contract. It feeds client generation
and contract checks, while the server adapter remains an ordinary JAX-RS
implementation that maps the V1 wire/API-domain model to the existing core.

## Table storage and provider options

V1 table create uses the normal `POST .../tables` resource route for every
provider. The request has portable table state under `storage`:

- `ownership`: `MANAGED` or `EXTERNAL`;
- `tableFormat`: the table metadata format, when the catalog uses one;
- `location`: a URI or connector-recognized filesystem location; and
- `fileFormat`: the default for future data writes, not a claim
  that all historical files share one format.

`storage` is omitted for JDBC-style tables whose backing database owns physical
storage. An external table requires `storage.location`. Delta uses the normal
create route with `storage.ownership: EXTERNAL`, `storage.tableFormat: DELTA`,
and a location; it has no separate registration route or `deltaOptions` block.

Connector-specific persistent state is a named, typed envelope:
`icebergOptions`, `hiveOptions`, `clickhouseOptions`, or `mysqlOptions`. A
JSON Schema 2020-12 `dependentSchemas` constraint permits zero or one named
envelope, never a mixture. The server separately validates that the chosen
block is compatible with the configured catalog and table format. It maps
public fields to internal connector properties; the legacy arbitrary table
`properties` map is not a V1 input or response member.

Each `*OptionsInput` object is closed. The corresponding `*OptionsResponse`
object is open, so a future V1 minor can add response-only metadata without
breaking readers. Storage and options are persistent immutable state in the
initial slice: GET returns them and a full PUT carries them unchanged. A
requested change is rejected until a correct core mutation exists.

The zero-or-one rule uses OpenAPI 3.1 / JSON Schema 2020-12 composition rather
than a generator-specific discriminator. It keeps standard generated models
usable for both no-options and one-options requests. Generated clients remain
convenience bindings: OpenAPI Generator 7.23.0 renders these fields as
optional but does not enforce this cross-field rule during direct model
construction. Bundled schema validation and the server's strict V1 input
boundary are authoritative.
