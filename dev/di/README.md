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

# Dependency-injection migration checks

`gravitino_env_metrics.py` provides a deterministic scorecard and ratchet for the gradual removal
of `GravitinoEnv`. It uses only the Python standard library and does not require `rg` in CI.

## Usage

From the repository root, print the current scorecard:

```shell
python3 dev/di/gravitino_env_metrics.py report
python3 dev/di/gravitino_env_metrics.py report --format json
```

Check it against the committed baseline:

```shell
python3 dev/di/gravitino_env_metrics.py check
```

Run the tool's unit tests:

```shell
python3 -m unittest discover -s dev/di -p 'test_*.py'
```

Both commands are part of the required Gradle verification lifecycle. Run only this fast gate with:

```shell
./gradlew checkGravitinoEnvMigration
```

The Gradle tasks use `python3` by default. Set `-PdiMigrationPython=/path/to/python` when a different
Python 3 executable is required.

The checked-in baseline contains `migrated_packages`. Once a package and its descendants have been
migrated, add its Java package name to that list. Check mode then requires both production and test
sources in that package tree to contain no `GravitinoEnv.getInstance()` or
`FieldUtils.readField/writeField` calls, or calls to the legacy `TreeLockUtils` overloads. A package
can be evaluated before changing the baseline:

```shell
python3 dev/di/gravitino_env_metrics.py check \
  --migrated-package org.apache.gravitino.tag
```

## Metric policy

The scorecard preserves raw textual counts so its numbers can be reproduced with a simple search.
It also reports code-only counts after removing comments and literals. For example, the initial 458
textual `GravitinoEnv.getInstance()` occurrences include one comment, leaving 457 code occurrences.

The scorecard also classifies qualified `TreeLockUtils` calls by method arity. The legacy overloads
hide `GravitinoEnv.getInstance().lockManager()` behind three-argument `doWithTreeLock` and
two-argument `doWithRootTreeLock` calls. Four-argument `doWithTreeLock` and three-argument
`doWithRootTreeLock` calls make the `LockManager` dependency explicit. Counts and affected-file
counts are split between production and test source sets. Calls with another arity, or with
unbalanced delimiters, are reported as unclassified. The TreeLockUtils baseline was captured at the
revision recorded in `metric_source_revisions`; the older metrics retain their original
`source_revision` baseline.

Check mode fails when either of these legacy patterns increases:

- Non-static field declarations in `GravitinoEnv`.
- Production or test code-only `GravitinoEnv.getInstance()` occurrences or affected-file counts.
- Production or test code-only legacy `TreeLockUtils` occurrences or affected-file counts.
- Any legacy occurrence in a package declared migrated.

The following remain report-only because a lightweight lexical metric cannot reliably establish
architectural intent:

- Physical lines and `new` expressions in `GravitinoEnv`.
- Repository-wide `FieldUtils.readField/writeField` calls. `FieldUtils` also has legitimate uses
  unrelated to `GravitinoEnv`.

Use `check --strict-informational` when evaluating a migration commit to promote increases in these
report-only metrics to failures. A deliberate baseline update remains reviewable in the same commit
as the production change.

The scanner removes comments and Java string, character, and text-block literals before matching.
For TreeLockUtils calls, it balances parentheses, brackets, and braces so nested calls, array
initializers, lambda parameter lists and bodies, and method references do not inflate the outer
arity. It is not a Java parser or type resolver: static imports, unqualified calls, imported aliases,
wrapper helpers, reflective calls, unusual generic type-argument expressions, and indirectly stored
singleton references are outside its scope. This metric is a lexical floor for hidden lock-manager
lookups, not proof that every hidden locator has been found. The scorecard supports code review; it
does not replace behavior, instance-identity, or lifecycle tests.

## Compatibility-bridge quarantine

`LegacyRuntimeDependencies` is treated as a temporary compatibility bridge, not a general-purpose
service locator. Check mode rejects every production qualified member use, method reference, or
static import unless its stable `path#member` identifier appears in the baseline's optional
`legacy_runtime_dependencies_allowed_callsites` list. Repeated identifiers preserve multiplicity,
so adding a second use in an otherwise approved file still fails. For example:

```json
"legacy_runtime_dependencies_allowed_callsites": [
  "core/src/main/java/org/apache/gravitino/hook/TagHookDispatcher.java#ownerDispatcher"
]
```

The allowlist defaults to empty. Current production and test identifiers are available in the JSON
report under `legacy_runtime_dependencies`; test callsites are visible but report-only. Removing an
approved production use does not fail the gate; the stale allowlist entry should be deleted with
that migration commit.
