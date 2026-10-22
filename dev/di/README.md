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

# Dependency-injection ratchet

Gravitino moved from the `GravitinoEnv` service locator to dependency injection with Dagger, and
`GravitinoEnv` is gone. `di_ratchet.py` counts the code patterns that the migration still has to
remove. `baseline.json` stores the
counts as limits, and `./gradlew check` fails when a count goes above its limit. Counts can go
down, but not up.

The tool needs only Python 3 and its standard library. The design is in
[Dependency injection with Dagger][design], section 7.

## Commands

Run these from the repository root:

```shell
python3 dev/di/di_ratchet.py report                 # print the counts
python3 dev/di/di_ratchet.py report --format json
python3 dev/di/di_ratchet.py check                  # compare the counts with the limits
python3 dev/di/di_ratchet.py update                 # write the counts as the new limits
python3 -m unittest discover -s dev/di -p 'test_*.py'
```

Gradle has two tasks, and `check` depends on both:

- `./gradlew testDiRatchet` runs the unit tests.
- `./gradlew checkDiRatchet` runs the unit tests, then `di_ratchet.py check`.

They run `python3`. Use `-PdiRatchetPython=/path/to/python3` to pick another interpreter. When the
interpreter cannot be found, both tasks fail with a message that says so; skip them with
`-x checkDiRatchet -x testDiRatchet`. Both tasks declare their inputs (the scripts, the baseline
and the scanned Java files) and write a stamp under `build/di-ratchet`, so they are up to date
when nothing changed.

## What it counts

The tool reads every `.java` file outside build output. It blanks out comments and string,
character and text-block literals, so text in them does not count. Files under `src/test`,
`src/integrationTest` and `src/testFixtures` count as test code. All other files count as
production code.

For each metric it records the number of matches and the number of files with a match, for
production and test code separately.

Hard metrics fail `check` when they rise:

- `singleton_get_instance`: declarations of `public static <Type> getInstance(`.

The metrics of `GravitinoEnv` itself (its `getInstance()` calls, `mockStatic` and `FieldUtils` on
it, `@LegacyEnvAccess` and the `TreeLockUtils` overloads that read it) reached 0 and were removed
with the class: code that uses it no longer compiles.

Report-only metrics print a warning when they rise. `check --strict-informational` turns the
warnings into failures.

- `field_utils_read_write`: all `FieldUtils.readField` and `FieldUtils.writeField` calls.
- `meta_service_get_instance`: calls to `\w*MetaService.getInstance(`.

The tool matches text. It does not parse Java or resolve types, so it misses static imports,
unqualified calls, helper methods that wrap a lookup, and generic type arguments that contain
commas inside a call. Treat the counts as a lower bound.

## Updating the baseline

When a change removes legacy code, run `update` and commit `baseline.json` in the same change.
This lowers the limits, so the removed code cannot come back.

`update` replaces `metrics` and sets `source_revision` to `git rev-parse --short HEAD`. It keeps
all other keys. Use `--description` to change the description.

Do not use `update` to make a failing `check` pass. Remove the new code instead.

## Migrated packages

`migrated_packages` in `baseline.json` lists Java packages that no longer use the legacy
patterns. It starts empty. In a listed package and its subpackages, `check` fails on any hard
per-file metric match, in production or test code. Try a package before you add it:

```shell
python3 dev/di/di_ratchet.py check --migrated-package org.apache.gravitino.tag
```

## Overlays

A downstream fork can keep its own limits for the files it owns. It adds an overlay file next to
`baseline.json` with its own name, so syncing from upstream never overwrites it:

```json
{
  "schema_version": 1,
  "extends": "baseline.json",
  "name": "enterprise",
  "paths": ["**/com/datastrato/**", "core-extension/"],
  "migrated_packages": []
}
```

- `extends` is the base baseline, relative to the overlay file.
- `paths` are globs relative to the repository root. `**` matches any number of directories. `*`
  and `?` match inside one path segment. A trailing `/` matches everything below a directory.
- The overlay owns the files that its `paths` match. The base owns all other files. A file that
  two overlays match is an error.
- `migrated_packages` in a file applies only to the files that file owns.

Pass each overlay with `--overlay`. Without `--baseline`, the base is the file the overlay
extends. `update` writes the limits for the base and for each overlay:

```shell
python3 dev/di/di_ratchet.py update --overlay dev/di/enterprise_baseline.json   # seed or lower limits
python3 dev/di/di_ratchet.py check --overlay dev/di/enterprise_baseline.json
python3 dev/di/di_ratchet.py report --overlay dev/di/enterprise_baseline.json
```

A fork must not rewrite the base, because the base comes from upstream and the next sync would
conflict with it. Use `--overlays-only` to write only the overlay files:

```shell
python3 dev/di/di_ratchet.py update --overlays-only --overlay dev/di/enterprise_baseline.json
```

Without `--overlay`, the base limits apply to every file.

## Migration pattern (strangler fig)

`GravitinoEnv` stayed in place as a facade while each area moved to Dagger:

1. Build the area in a Dagger module of the single `GravitinoComponent`.
2. Make the matching `GravitinoEnv` getter a one-line delegate to the component.
3. Change callers to take the dependency in their constructor, and change tests to use test
   modules instead of `FieldUtils.writeField` and `mockStatic`.
4. Run `update` and commit the lower limits. When the package has no matches left, add it to
   `migrated_packages`.

When every getter delegated and every `GravitinoEnv` metric was 0, the facade was removed. The
same steps apply to the remaining `getInstance()` singletons. Section 6 of the [design doc][design]
lists the PRs and the exit criteria.

[design]: ../../design-docs/architecture/dependency-injection/dagger/README.md
