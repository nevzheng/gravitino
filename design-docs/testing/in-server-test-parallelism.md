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

# SPIP: In-server approaches to core DB test parallelism

| | |
|---|---|
| **Status** | Partial: container sharing + DB tuning implemented on PR [apache/gravitino#13553](https://github.com/apache/gravitino/pull/13553); thread/classloader-scoped parallelism still a spike, no PR yet |
| **Scope** | `core` module database test execution: `buildSrc/.../SharedDbContainerService.java`, `core/build.gradle.kts` (`coreDatabaseThreaded`, `coreDatabaseForks` properties), DB container tuning |
| **Format** | Apache Spark SPIP (Heilmeier Catechism) |

## Q1. What are you trying to do?

Relax the per-fork memory-budget cap that limits core DB integration test
concurrency to 2, on any hardware, regardless of real CPU headroom — by
attacking the problem from inside the test server rather than by adding more
forks. Three angles evaluated:

- **Container sharing + DB tuning**: run every fork of a backend against one
  shared DB container instead of one-per-fork, with the container's own
  durability settings relaxed since a container that dies at the end of every
  run has no crash-safety requirement.
- **Thread-scoped parallelism (ScopedReference)**: replace per-fork *process*
  isolation with per-thread isolation inside one JVM, via 4 production
  singletons made ThreadLocal-scope-aware.
- **Classloader-scoped parallelism**: same isolation goal, but give each
  concurrent lane its own classloader instead of its own thread scope or
  process — every class's static state, including singletons we don't even
  know about yet, gets a separate copy per lane for free, with zero
  production code changes.

## Q2. What problem is this proposal NOT designed to solve?

- **Not changing the fork-count auto-detection formula itself.** The
  memory-budget-driven cap (`CORE-DB-CONCURRENCY`, `limitingFactor=ROLLOUT`)
  stays as the default; `-PcoreDatabaseForks=N` already exists as a manual
  override and this work reuses it rather than replacing it.
- **Not a general-purpose in-process test-isolation framework.** The two
  in-server mechanisms (thread-scoping, classloader-scoping) are scoped
  narrowly to the 4 core DB singletons implicated in cross-test DB state
  leakage; neither is a drop-in replacement for JUnit's own parallelism
  model outside this problem.
- **Not deciding between thread-scoping and classloader-scoping yet.** Both
  are still active spikes as of this writing (see Q7); this document records
  the comparison methodology and the data gathered so far, not a final
  choice.
- **Not re-tuning PostgreSQL or H2 containers** in the same pass as MySQL —
  MySQL's tuning is measured and shipped; PostgreSQL's equivalent settings
  (`fsync=off`, `synchronous_commit=off`, `full_page_writes=off`,
  `autovacuum=off`, `wal_level=minimal`) are specified but not yet re-run
  through the full validation methodology below. H2 needed no tuning — it's
  already in-memory/MVStore with no fsync path to disable.

## Q3. How is it done today, and what are the limits of current practice?

Before this work, core DB tests ran one DB container per fork, fork count
capped at 2 by a per-fork memory-budget formula regardless of CPU count —
confirmed identical on an 8-vCPU CI-class runner and a 14-core dev machine.
Adding CPU cores to the runner does nothing for this baseline: the limiter
was never CPU.

Two-container MySQL baseline: 26m25s, 520/520.

## Q4. What is new in your approach, and why do you think it will be successful?

### Container sharing + DB tuning (implemented, #13553)

Container sharing alone is a *cost* win (fewer containers to start/stop/
health-check), not a speed win — measured 5.6% *slower* untuned (27m53s)
because forks now contend for one server's fsync-per-commit path that used
to be parallel across dedicated containers. Adding DB tuning (durability
flags off, buffer pool sized up, `--tmpfs` for the data directory) removes
that contention source directly rather than just reducing startup overhead:
**22m15s, 15.8% faster than the original two-container baseline**, 520/520.

```
--innodb-flush-log-at-trx-commit=0
--innodb-doublewrite=0
--skip-log-bin
--performance-schema=OFF
--innodb-buffer-pool-size=2G
--innodb-log-buffer-size=64M
--tmpfs /var/lib/mysql:rw
```

### Thread-scoped parallelism, ScopedReference (spike)

- H2: **1.7x faster** (54.85s → 31s), 520/520 both.
- MySQL: roughly matches forked speed, 520/520, after fixing a real
  cross-thread DB-corruption bug that surfaced in 5 legacy test classes.
- Cost: 4 production singletons permanently modified to be scope-aware, plus
  a new public class in the published `gravitino-core` artifact. Two
  independent reviews (correctness/thread-safety, production-risk/
  maintainability) landed real fixes (save/restore instead of blind
  `unbind()`, `finally`-block leak protection on the error path, thread-local
  H2 file paths to close a shared-file race) but the maintainability
  objection below is why this hasn't shipped.

### Classloader-scoped parallelism (spike)

- H2: measured **57.91s vs 54.85s default — a wash**, 520/520 both. Same
  isolation guarantee as ScopedReference (no cross-test DB state leakage),
  no speed win on this backend: classloader isolation doesn't eliminate the
  cost ScopedReference eliminates (JVM boot per fork) — each lane still pays
  real classloading cost, just inside one JVM instead of across processes.
- MySQL: validation in progress as of this writing. Open question: whether
  the JDBC driver's `DriverManager`/`ServiceLoader` registration,
  Testcontainers' Docker client plumbing, and MyBatis's per-classloader
  dynamic-proxy generation survive the classloader boundary cleanly — a
  classic failure mode for this mechanism, not yet ruled out.
- Zero production code changes — the isolation is a test-harness property
  instead of a source-code property, which is the whole reason it's being
  evaluated as an alternative to ScopedReference rather than accepted as
  strictly worse just because H2 alone shows no speedup.

## Q5. Who cares? If you are successful, what difference will it make?

Every contributor running `:core:coreMySQLTest` locally or in CI pays the
current wall-clock cost on every run. The tuning alone (Q4, shipped) is a
15.8% reduction on the slowest backend lane with zero code risk. If either
thread- or classloader-scoped parallelism ships on top of it, concurrency
stops being capped at 2 regardless of hardware — meaningful for anyone
running on a larger box, and meaningful for CI cost if the team scales
runner size later (see Q8/future-work note below).

## Q6. What are the risks?

- **Comparing numbers across mismatched hardware silently inflates or
  erases a real effect.** Caught once mid-investigation (a dev laptop
  number was about to get compared against CI-class-hardware numbers) —
  mitigated by provisioning matched Cloud Workstations via Terraform for
  every comparison in this document.
- **DB tuning correctness**: relaxing durability flags is safe *only*
  because these containers are ephemeral and test-only; the same flags would
  be a data-loss risk on any persistent deployment. Confined entirely to
  `SharedDbContainerService.java` (test-only build code), never referenced
  from production configuration.
- **ScopedReference's production-code surface** is a standing maintenance
  cost for as long as it ships — the two independent reviews already found
  real bugs in the first draft (unbind-scope errors, an error-path leak, a
  shared-file race), which is itself evidence this class of mechanism is
  easy to get subtly wrong.
- **Classloader isolation's MySQL risk is unresolved.** If the JDBC driver
  registration doesn't survive the boundary, this approach may simply not
  be viable for any Docker-backed backend, only for H2 — which would make it
  a non-starter regardless of its zero-production-diff appeal.

## Q7. How long will it take?

- Container sharing + DB tuning: **done**, shipped on #13553.
- Thread-scoped parallelism: spike complete, 2 review passes applied; not
  scheduled to ship pending the classloader-isolation comparison below.
- Classloader-scoped parallelism: H2 validated; MySQL validation in
  progress. A tuned-DB concurrency sweep (2/4/6/8 lanes) on CI-class
  hardware is the remaining step for whichever mechanism is chosen, to
  answer "what concurrency level is actually achievable" empirically instead
  of by estimate.

## Q8. What are the mid-term and final "exams" to check for success?

- **Mid-term**: classloader isolation either passes or fails its MySQL
  validation cleanly (JDBC/Testcontainers/MyBatis boundary survives or it
  doesn't) — this alone resolves whether Q4's "thread vs. classloader"
  choice is even live for Docker-backed backends.
- **Final**: a tuned-DB concurrency sweep on matched CI-class hardware,
  same box for every leg, ≥2 repetitions per data point, pass/fail counts
  reported alongside every wall-clock number — producing the actual
  diminishing-returns curve rather than an estimate.
- **Resource-scaling exam (forward-looking, not yet run)**: the three
  mechanisms respond to different resource axes, so "buy a bigger machine"
  does not uniformly help.
  - DB tuning is bound by the *container's* own allocated memory/IO, not the
    runner's core count — a bigger machine with the container's resource
    allocation unchanged gives this lever nothing.
  - Thread/classloader parallelism is bound by runner CPU core count, up to
    the point the single shared DB container itself saturates on lock
    contention or I/O — a bigger machine should extend this lever's ceiling
    directly, until that point.
  - Today's plain-fork baseline doesn't scale with a bigger machine at all —
    its cap is a memory-budget formula, not CPU, so more cores are a pure
    no-op without one of the above also landing.
  - Implication: if CI hardware is upsized later, DB tuning needs
    re-validating against the new container allocation (it does not
    automatically improve), while whichever concurrency mechanism ships
    should be re-measured to find its new ceiling — these are two
    independently tunable levers, not one setting to pick once.
