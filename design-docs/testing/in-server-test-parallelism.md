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
| **Status** | Closed. Container sharing + DB tuning shipped on PR [apache/gravitino#13553](https://github.com/apache/gravitino/pull/13553) (7m04s vs. 26m25s baseline). Thread-scoped (ScopedReference) and classloader-scoped parallelism were both spiked, measured, and rejected — see Q4/Q7 for why. No further work planned on either concurrency mechanism; the resource-scaling knob in Q8 stays documented as a future option. |
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
that contention source directly rather than just reducing startup overhead.
Two rounds of measurement: an initial 4-flag pass landed **22m15s** (15.8%
faster than baseline); adding `--tmpfs` plus 3 more durability flags on top
landed **7m04s** (3.7x faster than baseline), independently reproduced on
CI-matched hardware. 520/520 on every run.

```
--innodb-flush-log-at-trx-commit=0
--innodb-doublewrite=0
--skip-log-bin
--performance-schema=OFF
--innodb-buffer-pool-size=2G
--innodb-log-buffer-size=64M
--tmpfs /var/lib/mysql:rw
```

### Thread-scoped parallelism, ScopedReference (spike, rejected)

- H2: **1.7x faster** (54.85s → 31s), 520/520 both — the only backend where
  this mechanism showed a real win.
- MySQL, on the tuned baseline above (7m04s / 3m27s at fork=2 depending on
  exact CI hardware): a concurrency sweep at parallelism 2/4/6/8 was run to
  find where threading nets out. **Parallelism=2 failed at 35m54s** — 5x
  *slower* than the tuned-fork baseline it was supposed to beat, with 17
  real test failures, all in `TestRoleMembershipWrites` /
  `TestOwnerAssignmentWrites` (concurrency-serialization tests: "waits for
  uncommitted delete," "serializes on the owner row," etc.). Parallelism
  4/6/8 all fast-failed in ~5s each, never completing a real run. Verdict:
  **rejected**. Root cause: collapsing per-fork process isolation into
  per-thread isolation inside one JVM breaks the transactional wait/lock
  semantics those tests depend on — a genuine correctness bug in the
  mechanism, not a tuning problem, and it's slower besides.
- Cost already paid before rejection: 4 production singletons permanently
  modified to be scope-aware, plus a new public class in the published
  `gravitino-core` artifact. Two independent reviews (correctness/
  thread-safety, production-risk/maintainability) landed real fixes
  (save/restore instead of blind `unbind()`, `finally`-block leak protection
  on the error path, thread-local H2 file paths to close a shared-file race)
  — evidence this class of mechanism is easy to get subtly wrong even before
  the fatal MySQL concurrency-correctness finding above.

### Classloader-scoped parallelism (spike, rejected)

- H2: measured **57.91s vs 54.85s default — a wash**, 520/520 both. Same
  isolation guarantee as ScopedReference (no cross-test DB state leakage),
  no speed win on this backend.
- MySQL: **rejected.** `NoClassDefFoundError:
  com/github/dockerjava/api/model/PruneType` inside Testcontainers'
  `JVMHookResourceReaper`, reproduced on a clean checkout (no confounders).
  The failure is nondeterministic — it's a race between the shutdown-hook
  crash and the test task's result-collection: most runs fail the build
  outright (520 tests, 1 failure); one run happened to finish collecting
  results before the crash fired, so the build reported 520/520 green while
  the identical `NoClassDefFoundError` still fired in both lanes' shutdown
  threads. That is a worse failure mode than a deterministic one — it means
  a build can go green while its container resource-cleanup silently didn't
  happen cleanly (Testcontainers' external Ryuk reaper is the only
  remaining backstop in that case, not the in-JVM hook). Root cause:
  Testcontainers' Docker-client plumbing (`docker-java`'s `PruneType` class,
  loaded by the reaper's shutdown thread) does not reliably resolve across
  the per-lane classloader boundary.
- Zero production code changes — the isolation was a test-harness property
  instead of a source-code property, which was the whole reason it was
  evaluated as an alternative to ScopedReference. Rejected anyway: a
  zero-production-diff mechanism that flakily corrupts CI signal and only
  works on the one backend (H2) that doesn't touch Testcontainers/Docker is
  not a viable general solution.

## Q5. Who cares? If you are successful, what difference will it make?

Every contributor running `:core:coreMySQLTest` locally or in CI pays the
current wall-clock cost on every run. The tuning alone (Q4, shipped) is a
**3.7x reduction** (26m25s → 7m04s) on the slowest backend lane with zero
production-code risk — that is the actual, delivered outcome of this
investigation. Neither concurrency mechanism shipped on top of it (Q4); the
memory-budget fork cap (Q2) stays as today's concurrency ceiling.

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
- **Both concurrency mechanisms were rejected on correctness grounds, not
  just performance.** ScopedReference broke real transactional-serialization
  tests under concurrency; classloader isolation broke Testcontainers'
  resource cleanup nondeterministically. Neither failure mode is something
  more engineering time would likely paper over cheaply — both are structural
  mismatches between "share DB test infrastructure across concurrent units
  smaller than a process" and libraries (JDBC connection semantics,
  Testcontainers' Docker client) that were not designed for that.

## Q7. How long will it take?

All work here is complete; nothing further is scheduled.

- Container sharing + DB tuning: **done and shipped**, #13553 (7m04s vs.
  26m25s baseline, 3.7x).
- Thread-scoped parallelism (ScopedReference): spiked, measured, **rejected**
  — real correctness failures under concurrency, and slower than the tuned
  baseline besides (35m54s vs. ~3-7min).
- Classloader-scoped parallelism: spiked, measured, **rejected** — breaks
  Testcontainers' Docker-client resource cleanup nondeterministically for any
  Docker-backed backend (MySQL, PostgreSQL); only viable on H2, which doesn't
  need it (no speed win there either).

## Q8. What are the mid-term and final "exams" to check for success?

Both exams below were run to completion; this section is now a record of
what was checked, not a forward-looking plan.

- **Mid-term** (classloader isolation's MySQL validation): **failed
  cleanly** — see Q4. Resolved the "thread vs. classloader" choice by
  rejecting both.
- **Final** (tuned-DB concurrency sweep on matched CI-class hardware,
  parallelism 2/4/6/8, ≥2 repetitions, pass/fail reported alongside every
  wall-clock number): **run to completion** — parallelism=2 failed with
  real correctness bugs at 35m54s, parallelism 4/6/8 never got a clean run.
  No diminishing-returns curve to report because the mechanism doesn't work
  at any concurrency level tested.
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
