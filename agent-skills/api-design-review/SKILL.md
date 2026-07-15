---
name: api-design-review
description: Review and improve HTTP/REST, RPC-over-HTTP, and OpenAPI API designs and implementations. Use when asked to review an API contract, endpoint, schema, error model, versioning, compatibility, client experience, or API proposal; produce evidence-backed findings and cite relevant standards and best-practice sources.
---

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

# API Design Review

Review the external contract first. Do not infer a public contract from an
implementation, generated client, or current behavior without evidence.

## Review workflow

1. Establish the review target and the intended audience: a new design, an
   existing endpoint, an OpenAPI document, an implementation, or a migration.
   State assumptions and the compatibility baseline.
2. Inspect the available contract, real server behavior, client use, tests, and
   deployment constraints. Separate observed behavior from a proposed rule.
3. Read [`references/standards.md`](references/standards.md). Select only the
   sources relevant to the request. Prefer the primary source; browse its
   current publication when a version or normative detail matters.
4. Review only applicable concerns. Mark non-applicable concerns as such rather
   than inventing pagination, streaming, asynchronous work, or bulk behavior.
5. Make a decision-ready recommendation and specify how it would be verified.

## Review dimensions

Evaluate these topics when relevant:

- Resource identity, URI hierarchy, names, and ownership.
- HTTP method semantics, safe/idempotent behavior, status codes, headers,
  content negotiation, and cache/precondition behavior.
- Request and response shapes: requiredness, absent versus `null`, defaults,
  unknown fields, formats, limits, polymorphism, examples, and validation.
- Errors: stable machine-readable types, safe human messages, public details,
  retryability, correlation IDs, and information disclosure.
- Authorization and authentication boundaries; never put credentials in paths
  or bodies when standard request headers apply.
- Mutations: idempotency, concurrency controls, atomicity/visibility,
  conflicts, partial failure, retries, and side effects.
- Collections and search: filtering, ordering, pagination, field selection,
  consistency, and result limits.
- Long-running work, polling, callbacks, streaming, bulk operations, and
  cancellation when the operation is not a bounded synchronous request.
- Compatibility, deprecation, API and contract versioning, server/client
  release combinations, migration, and changelog requirements.
- Operability: rate and size limits, caching, timeouts, observability,
  supportability, generated-client interoperability, and conformance tests.

## Use standards deliberately

Treat standards and style guides as evidence, not as a substitute for product
decisions. For every substantial recommendation, say whether it is:

- a protocol requirement;
- an adopted project policy;
- a compatibility constraint; or
- a recommended convention with viable alternatives.

Link the supporting primary source beside the finding. Do not claim that an
industry convention is a protocol requirement. If the API deliberately differs
from a guide, record the rationale and the interoperability consequence.

## Improve design documents, not just code

When the task produces or revises a proposal, design document, API reference,
or review comment:

- Add short inline Markdown links for every external standard, protocol rule,
  or borrowed convention that materially informs a decision. Prefer an exact
  section link when it exists.
- State the source's role: normative protocol rule, project policy,
  compatibility evidence, or non-binding example. Do not make a link dump.
- Put a compact **References** section at the end only when the document has
  several external inputs; keep the decision and its supporting link together
  in the body as well.
- Distinguish an API promise from a future-work idea. For example, do not
  document a rate-limit header, generated SDK guarantee, or asynchronous
  operation as part of the public contract until the service actually supports
  it.

## Produce a concise review

Lead with the outcome. Use this shape unless the user requests another:

```markdown
## Verdict

<ready / revise before implementation / needs a decision>

## Findings

1. **[P0–P3] Title** — evidence, client impact, recommendation, and source.
2. ...

## Decisions to record

- Decision, owner or open question, and compatibility consequence.

## Verification

- Contract lint/diff, unit and route tests, generated-client smoke test, and
  targeted end-to-end or property/fuzz tests where they reduce meaningful risk.
```

Use priority only when it helps actionability: P0 blocks correctness/security,
P1 blocks a safe public contract, P2 materially improves interoperability or
operability, and P3 is a non-blocking refinement. Keep findings bounded; do not
turn a narrow endpoint review into an unsolicited API redesign.

## Implementation review

When code is in scope, check that runtime behavior conforms to the contract:

1. Trace one success, one expected domain failure, and one unexpected failure
   across routing, validation, authorization, domain mapping, and serialization.
2. Confirm framework-generated failures (for example malformed JSON, unknown
   routes, method/content negotiation failures) use the documented public error
   behavior where the contract promises it.
3. Check that wire/domain mapping is explicit where internal models are not
   intended as stable external types.
4. Require examples and tests to serialize and validate against the advertised
   schema. Treat schema drift as a release and compatibility concern.

For a proposal rather than code, state which of these checks are deferred and
what a vertical proof of concept needs to demonstrate.
