<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements. See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership. The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License. You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied. See the License for the
  specific language governing permissions and limitations
  under the License.
-->

# Design: Experimental Features in Apache Gravitino

| Field    | Value                          |
| -------- | ------------------------------ |
| Status   | Draft                          |
| Authors  | Nevin Zheng (@nevzheng)        |
| Shepherd | Jerry Shao                     |
| Scope    | Gravitino API and Iceberg REST |
| Format   | SPIP                           |

Control plane for **preview** API behavior in Gravitino (REST/Java/Python and
IRC). Not feature semantics, not authz, and **not** capability negotiation on
stable APIs—stable contracts must not depend on `X-Gravitino-Experiments`.

**Read restrictions** and **materialized views** illustrate the general case
(made-up option names in the appendices are illustrative only). Per-feature
models belong in separate designs.

---

## Q1. What are you trying to do?

Two **orthogonal** gates. Expose experimental semantics only when **both**
are true. Opt-in means accepting instability until stabilization. Features own
request/response behavior (and may add stricter rules).

| Dimension            | Approach | Question                         | Owner            |
| -------------------- | -------- | -------------------------------- | ---------------- |
| Server enablement    | **A**    | May this env expose the feature? | Operator / admin |
| Client understanding | **B**    | Can this caller consume it?      | Client / engine  |

**Example:** experimental `read-restrictions` or `materialized-views` behavior
only when that feature’s Approach A `enable` is true **and** the client lists
the matching token on Approach B; otherwise the stable API.

---

## Q2. What problem is this proposal NOT designed to solve?

- Security/authz (headers are spoofable).
- Per-feature data models or protocol fields.
- Upstream ratification (Gravitino may preview earlier).
- Small incremental changes that do not need a preview phase.

---

## Q3. How is it done today, and what are the limits of current practice?

Gravitino does **not** support this kind of feature preview today. There is no
shared way to turn on unstable API/IRC semantics in an environment, let
callers declare they understand them, and keep everyone else on the stable
contract. Previews, if any, are one-off or undocumented.

**Limit:** without that control plane, Gravitino cannot safely preview—and
later change or graduate—user-facing semantics without risking mixed client
fleets or baking instability into the stable API.

---

## Q4. What is new in your approach and why do you think it will be successful?

What is new is a **general ability to preview user-facing semantics**—and to
change them while experimental—without forcing those changes on clients that
did not opt in. Two orthogonal gates:

| Label | Approach                                                       | Feasible?                                 |
| ----- | -------------------------------------------------------------- | ----------------------------------------- |
| **A** | `gravitino.conf` per-feature namespace + EXPERIMENTAL comments | Yes — existing conf style                 |
| **B** | `X-Gravitino-Experiments` (comma-separated feature tokens)     | Yes — existing request `header.*` pattern |

```text
expose_experimental(f) = A.enable(f) ∧ B.contains(f)
```

Reuses conf + headers; separates operator rollout from client understanding;
scales to many previews without parallel experimental APIs. API shape, header
rules, and examples are in **Appendix A**; feasibility sketch in **Appendix B**.

---

## Q5. Who cares? If you are successful, what difference will it make?

Operators get an env kill switch; clients get an explicit opt-in; authors
share one gate; non-opted-in callers keep a stable contract.

---

## Q6. What are the risks?

Server on without clients ready; leftover experimental keys/tokens; overuse;
treating the header as trust. Mitigate with default-off, A∧B tests, and
dropping tokens at stabilization.

---

## Q7. How long will it take?

1. Framework (conf + header plumbing, lifecycle docs).
2. Adoption (read restrictions first, then others).
3. Stabilization (release/tag, remove experimental gates).

---

## Q8. What are the mid-term and final “exams” to check for success?

**Mid-term:** an adopter uses A+B; tests cover off / on+no header /
on+token / unexpected or duplicate token → reject; docs mark unstable.

**Final:** multiple features share A+B; at least one graduates at a named
release/tag; stale tokens reject as unexpected; stable callers need no header.

---

## Lifecycle

Features may add extra semantics on top of A∧B (omit vs reject when B is
missing, extra Approach A options, and so on).

### Experiment phase

Use A+B. Opt-in **accepts instability**. Docs mark **experimental**. Prefer
preserve-stable when B is absent unless the feature documents fail-closed.

### Stabilization

Docs and semantics freeze at a **tag or release**. Feature joins the stable
API/IRC contract; A/B no longer required for normal use; move config out of
`gravitino.experimental.<feature>` (aliases then remove). Drop the token from
the experimental header set—stale sends get **unexpected feature**.

After it joins the stable surface, further change follows Gravitino’s
**normal evolution process** (compatibility expectations, deprecation, and
iteration like any other stable API)—not experimental “may break anytime”
rules.

---

## Appendix A. Proposed API Changes

Backward compatible when experimental features stay disabled and the header
is absent (today’s stable behavior).

### Approach A — server (`gravitino.conf`)

```text
# EXPERIMENTAL: <description>. May change until stabilized.
gravitino.experimental.<feature>.enable = false
gravitino.experimental.<feature>.<option> = ...
```

- `enable` defaults to **false**.
- Feature-owned options live in the same namespace (ignored when `enable` is
  false unless a feature says otherwise).
- Applies to Gravitino API, IRC, or both.

Illustrative multi-feature config (option names are examples only):

```text
# EXPERIMENTAL: preview read-restriction API behavior. May change until stabilized.
gravitino.experimental.read-restrictions.enable = true
gravitino.experimental.read-restrictions.missing-opt-in = reject
gravitino.experimental.read-restrictions.max-row-filter-depth = 8

# EXPERIMENTAL: preview materialized-view / storage-table API behavior.
gravitino.experimental.materialized-views.enable = true
gravitino.experimental.materialized-views.allow-create = true
gravitino.experimental.materialized-views.refresh-mode = manual
```

### Approach B — client (`X-Gravitino-Experiments`)

Comma-separated **set** of strings. Only **exact** matches to the documented
**experimental** token set are valid (case-sensitive, trim after `,` split).

| Rule                       | Behavior                                                                          |
| -------------------------- | --------------------------------------------------------------------------------- |
| Absent / empty             | No experimental understanding; stable API                                         |
| Header copies              | **At most one** `X-Gravitino-Experiments` field; more than one → invalid argument |
| Known experimental token   | Client accepts unstable semantics for that feature                                |
| Unexpected / unknown token | Reject: **unexpected feature** (invalid argument); point at the token             |
| Duplicate / empty segment  | Reject: invalid argument (must be a set; no empty elements)                       |
| Authz                      | Not trust or authorization                                                        |

Each request may carry **at most one** `X-Gravitino-Experiments` field.
Examples of valid single-request values:

**Request opting into read-restrictions only:**

```http
X-Gravitino-Experiments: read-restrictions
```

**Request opting into materialized-views only:**

```http
X-Gravitino-Experiments: materialized-views
```

**Request opting into both** (one header, comma-separated token set):

```http
X-Gravitino-Experiments: read-restrictions,materialized-views
```

Spark (same single-header, multi-token value):

```text
spark.sql.catalog.<name>.header.X-Gravitino-Experiments=read-restrictions,materialized-views
```

Illustrative tokens: `read-restrictions`, `materialized-views`. Listing only
one does not opt into the other. After stabilization, drop the token from the
experimental set; a stale token is **unexpected feature**.

---

## Appendix B. Design Sketch

```
  Approach A                         Approach B
  gravitino.conf                     X-Gravitino-Experiments
  experimental.<f>.enable            set of feature tokens
                 \                         /
                  v                       v
              expose_experimental(f) = A.enable(f) ∧ B.contains(f)
                              |
                              v
                   Gravitino API and/or IRC
                   (feature-owned semantics)
```

Shared helpers: Approach A “enabled?” and Approach B “token present / validate
header?”. Feature modules decide open vs closed behavior when the gate fails.

---

## Appendix C. Rejected Designs

| Label  | Approach                           | Why not                                                     |
| ------ | ---------------------------------- | ----------------------------------------------------------- |
| **R1** | A without B                        | Mixed client fleets unsafe                                  |
| **R2** | B without A                        | No env kill switch                                          |
| **R3** | One global experimental mode       | Couples unrelated previews                                  |
| **R4** | Header as trust/security           | Spoofable                                                   |
| **R5** | Rich parameters only in the header | Keep tokens coarse; params in conf / feature APIs           |
| **R6** | Ignore unknown tokens              | Client may expect an unsupported capability; reject instead |
| **R7** | Dynamic flags / OpenFeature        | Too complex for now; static `gravitino.conf`                |
| **R8** | Full parallel experimental APIs    | Too complex for now; gate existing paths                    |

---

## References

- **Iceberg `X-Iceberg-Client-Capabilities`**
  ([discuss](https://mail-archive.com/dev@iceberg.apache.org/msg13614.html),
  [PR #16394](https://github.com/apache/iceberg/pull/16394), closed unmerged):
  prior art for REST client capability ads; not adopted upstream. Gravitino’s
  header is **preview opt-in**, not stable API negotiability.
- Read-restrictions and materialized-views feature designs (or similar) own
  semantics and reference this framework for A+B.
