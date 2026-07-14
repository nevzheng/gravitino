---
name: gravitino-api-design-review
description: Review, design, or propose Gravitino public REST and OpenAPI contracts. Use for endpoint design, OpenAPI V1 migration, resource and route design, versioning, wire/domain type boundaries, error contracts, compatibility, or API design-document reviews before implementation.
---

# Gravitino API Design Review

Design public contracts as products. Separate what clients observe from internal Java and catalog implementation details.

## Start with evidence

1. Confirm whether the request is a design review, a design proposal, or a bounded implementation slice.
2. Inspect both the current server route and its OpenAPI entry before making claims. The legacy sources are normally:
   - `server/src/main/java/org/apache/gravitino/server/web/rest/`
   - `docs/open-api/`
   - the applicable dispatcher and public API types under `core/` and `api/`
3. State observed behavior separately from the proposed V1 behavior. Do not present an inventory count or legacy documentation as proof of runtime conformance.
4. When citing external practice, use a primary source and make clear that it is a reference, not a Gravitino decision.

## Review the contract

Review the smallest relevant set of topics. Do not turn a single endpoint review into a full API redesign.

| Topic | Questions to answer |
| --- | --- |
| Resource model | Is the resource named, hierarchical, and independent of internal storage types? |
| Routes and methods | Does the HTTP method describe the operation? Is a custom action genuinely needed? |
| Inputs | Are path/query/body fields, requiredness, absence, nullability, defaults, formats, and unknown fields explicit? |
| Outputs | Is the response shape public and stable? Are server-generated and normalized fields documented? |
| Errors | Are status codes, stable public error types, messages, retry semantics, and sensitive-data boundaries defined? |
| Mutation semantics | Are commit/visibility, idempotency, conflicts, preconditions, and catalog-specific limits explicit? |
| Collections | Are pagination, filtering, sorting, limits, and field selection decided where applicable? |
| Compatibility | Is the change additive, deprecated, or breaking? Can an existing client continue to work? |
| Operations | Are authorization, caching, observability, rate/size limits, and asynchronous behavior addressed when relevant? |

## Gravitino V1 boundary

Keep the V1 external contract distinct from current internal Java types.

1. Define public V1 wire/API-domain objects in the OpenAPI contract.
2. Keep JAX-RS as the current HTTP hosting and routing layer unless a separate server-runtime decision changes it.
3. Use explicit, reviewed mappers between V1 wire objects and internal domain/service objects.
4. Translate internal exceptions at the V1 boundary into documented public errors. Never expose Java exception names, stack traces, or catalog implementation details.
5. Treat generated server stubs as optional tooling, never as the definition of the public contract. Prefer validation, bundling, compatibility checks, client-generation smoke tests, and runtime conformance tests.

## Produce a decision-ready review

Lead with a recommendation. For each material finding, provide:

- **Evidence:** current route, specification, or implementation behavior.
- **Decision:** the client-visible rule to adopt.
- **Rationale:** compatibility, interoperability, or operational reason.
- **Scope:** what is deliberately deferred.
- **Verification:** contract, unit, route, or end-to-end tests that prove the rule.

For a design document, group decisions by topic rather than listing an unbounded set of questions. Preserve open questions only when an answer is needed before implementation.

## Plan a vertical-slice PoC

For an OpenAPI V1 proof of concept, choose one endpoint and one representative failure path. Define:

1. the V1 OpenAPI operation and schemas;
2. the `/api/v1/...` route and `application/json` behavior;
3. wire objects and the mapper to current internal types;
4. public error mapping and HTTP status;
5. compatibility handling between legacy `/api/...` and V1;
6. tests for success, expected failure, schema/serialization behavior, and version/media-type behavior.

Avoid migrating a whole resource area, changing the server framework, or committing to broad client generation in the same PoC.

## Finish with verification

For OpenAPI changes, run `./gradlew :docs:build`. For server logic, run the narrowest relevant unit and route tests, then report what was run and any gaps. Use a contract diff or breaking-change check when an existing public contract changes.
