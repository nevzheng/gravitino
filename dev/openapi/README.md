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

# Gravitino OpenAPI tooling

Lint, bundle, and codegen-check the OpenAPI spec under
[`docs/open-api`](../../docs/open-api). Run in CI by
[`.github/workflows/openapi.yml`](../../.github/workflows/openapi.yml).

Redocly, Spectral, TypeScript, and generated-client dependency versions live in
[`package.json`](./package.json). OpenAPI Generator is pinned independently in
[`gradle/libs.versions.toml`](../../gradle/libs.versions.toml) at the version
used by [Apache Iceberg's current build](https://github.com/apache/iceberg/blob/main/build.gradle#L39).
Dependabot can update both reproducibly.

For the full picture — objectives, how it works, and how to publish the
artifact — see
[`docs/openapi-validation-and-publishing.md`](../../docs/openapi-validation-and-publishing.md).
For the V1 adapter's place beside the legacy API and core, see
[`docs/open-api/v1/README.md`](../../docs/open-api/v1/README.md).

## Layout

| File                          | Purpose                                                                        |
| ----------------------------- | ------------------------------------------------------------------------------ |
| `package.json`                | Pinned tool versions (`@redocly/cli`, `@stoplight/spectral-cli`, rulesets).    |
| `test-v1-provider-options.mjs` | AJV 2020-12 instance regression test for the bundled V1 provider-options rule. |
| `tsconfig.v1-codegen.json`    | Strict, no-emit check for the generated TypeScript V1 client.                  |
| `../../docs/build.gradle.kts` | Pinned OpenAPI Generator validation, generation, and client compilation tasks. |
| `redocly.yaml`                | The `apis` Redocly lints/bundles (strictness is passed on the CLI).            |
| `.spectral.yaml`              | Governance ruleset: OWASP + documentation + Gravitino house rules.             |

## Usage

```bash
cd dev/openapi
npm ci

npm run lint            # Redocly (recommended-strict) + Spectral (governance)
npm run lint:redocly    # structural validation; add --extends=minimal|recommended
npm run lint:spectral   # governance kitchen sink
npm run bundle          # -> build/openapi.json and build/openapi.yaml

# Once docs/open-api/v1/openapi.yaml exists, run the blocking V1 gate:
npm run lint:v1         # Redocly strict + Spectral, warnings fail
npm run bundle:v1       # -> build/v1/openapi.json and build/v1/openapi.yaml
npm run test:v1:provider-options  # bundled-schema zero/one provider-options cases
npm run codegen:v1      # Gradle-pinned generation + Rust/TypeScript compilation
npm run test:rules:v1   # regression-test V1-specific Spectral rules

# Or invoke the repository's complete Gradle gate from this directory:
../../gradlew -p ../.. :docs:openApiV1Check
```

Requires Node `20.19+` / `22.12+` / `23+` (Redocly v2).

From the repository root, the preferred local entry point is:

```bash
dev/openapi/check-v1.sh
```

It runs the same `:docs:openApiV1Check` aggregate used by CI and forwards any
additional Gradle arguments. Gradle downloads the pinned Node/npm runtime,
uses `npm ci`, then runs every stage below. To isolate a failure:

```bash
cd dev/openapi
npm ci
npm run lint:v1
npm run bundle:v1
npm run test:v1:provider-options

../../gradlew -p ../.. :docs:validateOpenApiV1
../../gradlew -p ../.. :docs:generateOpenApiV1Rust
../../gradlew -p ../.. :docs:generateOpenApiV1TypeScript
../../gradlew -p ../.. :docs:checkOpenApiV1RustClient
../../gradlew -p ../.. :docs:checkOpenApiV1TypeScriptClient
```

## Authoring V1

### Layout and references

Keep the root document small. Put each route in a file whose root value is a
single OpenAPI **Path Item Object**, then reference the whole file directly:

```yaml
# openapi.yaml
paths:
  /api/v1/metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/tables/{table}:
    $ref: ./paths/get-table.yaml
```

```yaml
# paths/get-table.yaml
parameters:
  - $ref: ../components/parameters/metalake.yaml
get:
  operationId: getTable
  # ...
head:
  operationId: headTable
  # ...
```

Do not wrap a path file in another `paths:` object and reach into it with an
encoded JSON Pointer such as
`#/paths/~1api~1v1~1metalakes~1%7Bmetalake%7D...`. Direct Path Item references
are easier to review, rename, bundle, and generate from. Apply the same
single-object convention to reusable schemas and parameters where practical.
Use relative references, never absolute local paths or remote mutable URLs.

A typical V1 tree is:

```text
docs/open-api/v1/
├── openapi.yaml
├── paths/
│   └── get-table.yaml
└── components/
    ├── parameters/
    ├── responses/
    ├── schemas/
    └── security-schemes/
```

### Contract rules

- Route major versions as `/api/v1`. `info.version` is the
  [SemVer](https://semver.org/) release of
  the contract; it does not select a hidden wire variant.
- Use `application/json`. V1 does not use Gravitino vendor media types or a
  version-selection header.
- Keep `operationId` values stable and unique. Name public errors and schemas
  for the API domain, never after Java exception or implementation classes.
- Use camelCase JSON property names except where an incorporated external wire
  standard requires an exact spelling. The current narrow exception is
  Iceberg Expressions Appendix B's
  [`data-type`](https://github.com/apache/iceberg/blob/main/format/expressions-spec.md#appendix-b-json-serialization)
  key.
- Model absence by omitting an optional property. Do not introduce `null`
  unless it is a deliberate third state with documented semantics.
- Keep the directions separate. Request object schemas use
  [`additionalProperties: false`](https://json-schema.org/understanding-json-schema/reference/object#additional-properties)
  unless they are intentional extension/property bags; the server may reject
  unknown request members. Response object schemas use
  `additionalProperties: true`, and clients must ignore unknown response
  members. Do not reuse a response schema as a request schema: introduce
  directional `*Request` and `*Response` schemas (with common components only
  where their validation rules genuinely match). This follows
  [Google AIP-180's compatibility guidance](https://google.aip.dev/180).
- Make `oneOf` alternatives unambiguous and give polymorphic unions explicit,
  stable discriminators. Avoid anonymous inline schemas that generators rename.
- Define collection emptiness explicitly and give all formats, bounds, enums,
  patterns, and examples realistic values. Avoid schema defaults that the
  server does not actually apply.
- Additive optional response fields are minor evolution. Removing a field,
  changing its meaning/type, or making an optional field required is breaking
  and requires a new routed major version. Existing response fields must keep
  their documented absence/nullability rules as well as their type and meaning.
  Publish an additive change with an `info.version` minor bump and its updated
  contract; do not use response openness to ship undocumented fields.
- Treat enum additions and new `oneOf`/discriminator variants as breaking by
  default: generated and strongly typed clients often model those as closed
  sets. A minor release may add one only when the existing schema explicitly
  defines a tested extension mechanism.
- Do not define `Authorization` (in any casing) as a header Parameter Object: the
  [OpenAPI Parameter Object](https://spec.openapis.org/oas/v3.1.2.html#parameter-object)
  ignores the canonical spelling, and HTTP header names are case-insensitive.
  A deployment-specific authentication profile must instead
  declare and validate its `components.securitySchemes` and root or operation
  [`security` requirement](https://spec.openapis.org/oas/v3.1.2.html#security-requirement-object),
  including any `WWW-Authenticate` behavior. The portable base contract stays
  scheme-neutral.
- Every documented error includes the HTTP-aligned `code`, stable `type`, safe
  `message`, `retryable`, `requestId`, and typed `details`. If
  `retryAfterSeconds` is present, it must agree with `Retry-After`.
- Error responses include the same JSON contract for methods that permit a
  body. [`HEAD` responses never carry content](https://www.rfc-editor.org/rfc/rfc9110#section-9.3.2),
  including errors, so V1 documents their status and headers without inventing
  a body solely to satisfy a generic linter rule.

### V1 blocking Spectral policy

V1 extends the standard OpenAPI, documentation, and
[OWASP API Security](https://owasp.org/API-Security/) Spectral rulesets. All
findings at warning or higher block V1, then V1 promotes its applicable
conventions to errors and adds regression-tested contract rules. The rules are
intentionally opinionated about guarantees that every V1 route can actually
make:

- Complete, tagged, documented, uniquely named operations; valid examples;
  bounded schemas; reusable components; an explicit operation security posture;
  and route paths below `/api/v1`.
- Portable `application/json` bodies with a schema and a concrete example;
  explicit status responses rather than `default`; no GET/HEAD request body;
  and no content on HEAD, 204, 205, or 304 responses.
- `X-Request-Id` and `Cache-Control` on every response, plus `Location` for
  201, `ETag` for 304, `WWW-Authenticate` for 401, `Allow` for 405, and
  `Retry-After` for 429. These follow the relevant HTTP semantics in
  [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110).
- A strict, documented public error envelope with the stable machine-readable
  fields (`code`, `type`, `message`, `retryable`, `requestId`, and `details`),
  and directional schemas: `*Request` schemas are closed while `*Response`
  schemas are forward-compatible.

The rules deliberately do **not** invent a global authentication scheme, CORS
policy, rate-limit policy, pagination convention, or streaming protocol. Those
are deployment or route design decisions and become blocking only once V1 has
a real, consistently implemented contract for them. The invalid fixtures in
[`fixtures/`](./fixtures) lock in the V1-specific rules, including the
null-safe enum check; run `npm run test:rules:v1` after changing the ruleset.

### Adding a route or model

1. Add the direct Path Item and component files with an explicit operation ID,
   parameters, security, media type, response headers, and every status the
   server intentionally returns.
2. Add complete success and error examples. Examples must conform to the same
   schemas and demonstrate absence, empty collections, identifiers, timestamps,
   retryability, and typed error details where relevant.
3. Add or update server mapper, exception-translation, route, filter, and real
   HTTP tests. The OpenAPI file leads the implementation, but a V1 slice does
   not merge until runtime behavior matches it.
4. Run `dev/openapi/check-v1.sh`. Inspect generated client failures as contract
   design feedback; do not patch disposable files under `docs/build/`.
5. Review the [`oasdiff`](https://github.com/oasdiff/oasdiff) result. Intentional breaking changes belong in a new
   routed major API, not an exception list for V1.

### Generator scope and limitations

[OpenAPI Generator's Gradle plugin](https://openapi-generator.tech/docs/plugins/)
is pinned to `7.23.0`, matching
[Apache Iceberg's current build choice](https://github.com/apache/iceberg/blob/main/build.gradle#L39).
It is a validator and external-consumer smoke test here; it does not generate
Gravitino's JAX-RS server, public Java wire models, or domain mappers. Its
[OpenAPI compatibility matrix](https://github.com/OpenAPITools/openapi-generator#11-compatibility)
still classifies 3.1 support as beta and does not list OpenAPI 3.2 support, so
V1 uses OpenAPI 3.1.2 until the entire blocking toolchain can advance together.

The 7.23.0 Rust and TypeScript generators can compile a 3.1 contract while
still degrading some JSON Schema primitive unions into loose or empty generated
models. The disposable client gate therefore proves parsing, generation, and
compilation compatibility; it is not a promise that every generated SDK is an
idiomatic or semantically lossless representation. Gravitino follows portable
OpenAPI and tests widely used generators, but does not support their templates
or output as project APIs. A published SDK needs its own serialization and
behavior tests rather than relying on this smoke gate alone.
Generated-client compatibility is best-effort; the OpenAPI contract and its
schema validation remain authoritative.

In particular, the generated Rust and TypeScript smoke clients do not model a
conditional `304 Not Modified` table read as a typed alternative to `200`.
Consumers that send `If-None-Match` must use their HTTP transport's raw status
and headers (or a deliberately tested client wrapper) rather than assuming a
generated `TableResource` method can deserialize an empty `304` body. A
published Gravitino SDK must add an explicit conditional-read behavior test.

Gravitino remains on Gradle 8.2 for this work. OpenAPI Generator 7.23.0 brings
Java 21 multi-release Jackson classes that Gradle 8.2 cannot instrument, so the
docs build aligns the generator's buildscript-only Jackson modules with
Gravitino's pinned Java 17-compatible Jackson version. This does not change
runtime dependencies or downgrade OpenAPI Generator itself.

### Current table-slice mapping boundary

The V1 wire grammar includes the complete [Apache Iceberg Appendix D literal
representation](https://github.com/apache/iceberg/blob/main/format/spec.md#appendix-d-single-value-serialization).
The initial table adapter maps portable scalar literals only;
it fails closed for internal struct, list, map, and union literal values until
Gravitino's internal domain exposes lossless structured values and stable
struct field IDs. It never synthesizes IDs or emits lossy string values. A
future mapper migration, not a narrowing of the V1 grammar, resolves that
boundary.

### Known table-response conformance follow-up

The initial table mapper does not yet enforce every response-schema bound and
shape minimum. For example, catalog-derived comments, properties, and
collections can exceed documented output limits; an empty internal union,
index field list, or partition-value tuple can also conflict with a schema
minimum. This is recorded as follow-up work rather than changed in this slice:
do not silently truncate catalog metadata. Before V1 is released, either make
the wire boundary reject unsupported output with a safe public error or relax
only the constraints that Gravitino intentionally does not guarantee.

## Design: expose-first, and crashes are signal

The CI pipeline is deliberately **warn-only** for now — every check is
non-blocking so the existing backlog surfaces as PR annotations without turning
`main` red. Stages:

1. **Redocly** at three strictness levels (`minimal`, `recommended`,
   `recommended-strict`) as a matrix, so the finding gradient is visible.
2. **Bundle** → single `openapi.json`/`openapi.yaml` artifact.
3. **Spectral** runs against the bundle: `spectral:oas` + OWASP API-security +
   documentation completeness + Gravitino house rules. Bundled linting avoids
   false schema findings for valid direct external Path Item references.
4. **OpenAPI Generator 7.23.0** validates the bundle and generates disposable
   Rust and TypeScript clients under `docs/build/`.
5. **Client compilation** runs `cargo check --locked` and a pinned TypeScript
   no-emit check, so generation alone is not treated as success.
6. **[oasdiff 1.17.0](https://github.com/oasdiff/oasdiff/releases/tag/v1.17.0)**
   performs the breaking-change diff vs the PR base branch.

V1 retains the OWASP ruleset except where a rule would make the contract claim
undeployed behavior. Universal CORS-response headers are disabled because CORS
is an optional deployment filter. Universal rate-limit headers are disabled
until Gravitino adopts a concrete server-side rate-limit policy. Those are
explicit governance decisions, not ignored linter failures. The universal
read-authentication rule is also disabled for the base contract: Gravitino
deployments select Basic, Bearer, Negotiate, custom, or anonymous behavior at
runtime. Before publishing a hosted API reference or SDK, that deployment must
provide and validate an OpenAPI overlay with its exact security scheme,
security requirement, and
[`WWW-Authenticate` challenge behavior](https://www.rfc-editor.org/rfc/rfc9110#section-15.5.2).
The universal
string-pattern rule is also disabled because human-readable messages cannot
have a meaningful regex or semantic format. String bounds remain blocking, and
identifiers, enums, timestamps, and other constrained domains still require an
appropriate pattern, enum, or format.

Spectral 6.16.1's `duplicated-entry-in-enum` selector dereferences `.enum` on
every node and crashes on a valid JSON `null`, including V1's typed null-literal
example. V1 replaces that inherited selector with a null-safe equivalent over
the actual enum arrays while retaining the same `uniqueItems: true`
enforcement. `npm run test:rules:v1` proves that a document containing JSON
null does not crash and that a duplicate enum still fails. Other Spectral
crashes remain blocking signals to investigate rather than mute.

## Enforcing V1 from its first commit

The legacy API is expose-first and warn-only while its existing backlog is
triaged. The authoritative V1 contract is different: when
`docs/open-api/v1/openapi.yaml` exists, CI runs `:docs:openApiV1Check`. That
aggregate task blocks on Redocly, Spectral, the OpenAPI Generator validator,
Rust and TypeScript generation, and compilation of both generated clients.
Pull requests also run a blocking `oasdiff` comparison against the base branch
once that branch contains V1. The first V1 pull request has no prior V1
artifact, so only that baseline comparison is skipped.

Run exactly the same gate locally with the commands above before opening a V1
contract pull request.

## Promoting legacy checks to enforcing

Once the backlog is burned down:

- Drop `continue-on-error` from the Redocly step to make structural validation
  block merges.
- Remove the `exit 0` guard from the Spectral step (and promote individual
  house/security/docs rules from `warn` to `error`).
- Drop `continue-on-error` from the codegen and oasdiff steps.

## Artifact

`npm run bundle` resolves the ~30 cross-referenced spec files into a single
`build/openapi.json` (and `.yaml`). CI uploads it as the `gravitino-openapi-spec`
artifact — the clean, single-file input SDK generators and mock servers should
consume. Versioning is at `HEAD` only for now; per-release bundles can be added
later via `workflow_dispatch`/tags, and the artifact can be published to a stable
URL on the website (see the doc linked above).
