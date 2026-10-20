#!/usr/bin/env python3
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#   http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

"""Count dependency-injection anti-patterns in Java sources and keep the counts from rising.

Commands:
  report  print the current counts
  check   fail when a hard count is above its limit in the baseline
  update  write the current counts into the baseline as the new limits

The scan is lexical. Comments and literals are blanked out first, then regular expressions find
the patterns. It needs only the Python standard library.
"""

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
BASELINE = Path("dev/di/baseline.json")
ENV_FILE = "core/src/main/java/org/apache/gravitino/GravitinoEnv.java"
SKIPPED_DIRS = {".git", ".gradle", ".idea", ".venv", "build", "node_modules", "out", "target"}
KINDS = ("production", "test")

# Comments, text blocks, strings and character literals, in that order of precedence.
NON_CODE = re.compile(
    r'//[^\n]*|/\*.*?\*/|"""(?:\\.|[^\\])*?"""|"(?:\\.|[^"\\\n])*"|\'(?:\\.|[^\'\\\n])*\'',
    re.DOTALL,
)
TEST_PATH = re.compile(r"(?:^|/)src/(?:test|integrationTest|testFixtures)/")
PACKAGE = re.compile(r"^\s*package\s+([\w.]+)\s*;", re.MULTILINE)

ENV_LOCATOR = re.compile(r"\b\w*GravitinoEnv\s*\.\s*getInstance\s*\(")
ENV_LOCATOR_CALL = re.compile(r"\s*\w*GravitinoEnv\s*\.\s*getInstance\s*\(\s*\)\s*")
TREE_LOCK = re.compile(r"\bTreeLockUtils\s*\.\s*(doWithTreeLock|doWithRootTreeLock)\s*\(")
LEGACY_TREE_LOCK_ARITY = {"doWithTreeLock": 3, "doWithRootTreeLock": 2}
SINGLETON = re.compile(
    r"\b((?:(?:public|static|final|synchronized)\s+)+)"  # modifiers
    r"(?:<[^;{}()]*?>\s*)?"  # method type parameters
    r"[\w$.]+(?:\s*<[^;{}()]*?>)?(?:\s*\[\s*\])*"  # return type
    r"\s+getInstance\s*\("
)
MOCK_STATIC_ENV = re.compile(r"\bmockStatic\s*\(\s*\w*GravitinoEnv\s*\.\s*class\b")
FIELD_UTILS_ANY = re.compile(r"\bFieldUtils\s*\.\s*(?:read|write)\w*Field\s*\(")
FIELD_UTILS_READ_WRITE = re.compile(r"\bFieldUtils\s*\.\s*(?:readField|writeField)\s*\(")
LEGACY_ENV_ACCESS = re.compile(r"@\s*(?:\w+\s*\.\s*)*LegacyEnvAccess\b")
META_SERVICE_LOCATOR = re.compile(r"\b\w*MetaService\s*\.\s*getInstance\s*\(")
NEW_CALL = re.compile(r"\bnew\s+[\w$.]+\s*(?:<[^;{}()]*>)?\s*\(")


def strip_code(source):
    """Blank out comments and literals, keeping line breaks so positions stay comparable."""
    return NON_CODE.sub(lambda match: re.sub(r"[^\n]", " ", match.group()), source)


def call_arguments(code, open_paren):
    """Split the call starting at code[open_paren] into top-level arguments.

    Returns None when the brackets do not balance. Generic type arguments are not tracked, so
    `new HashMap<A, B>()` inside a call reads as two arguments.
    """
    closers = {"(": ")", "[": "]", "{": "}"}
    expected = []
    arguments = []
    start = open_paren + 1
    for index in range(open_paren, len(code)):
        char = code[index]
        if char in closers:
            expected.append(closers[char])
        elif char in ")]}":
            if not expected or expected.pop() != char:
                return None
            if not expected:
                last = code[start:index]
                if arguments or last.strip():
                    arguments.append(last)
                return arguments
        elif char == "," and len(expected) == 1:
            arguments.append(code[start:index])
            start = index + 1
    return None


def count_legacy_tree_locks(code):
    """Count TreeLockUtils calls that use the overloads which look up the lock manager."""
    count = 0
    for match in TREE_LOCK.finditer(code):
        arguments = call_arguments(code, match.end() - 1)
        if arguments is not None and len(arguments) == LEGACY_TREE_LOCK_ARITY[match.group(1)]:
            count += 1
    return count


def count_singletons(code):
    """Count `public static ... getInstance(` method declarations."""
    return sum(
        1
        for match in SINGLETON.finditer(code)
        if {"public", "static"} <= set(match.group(1).split())
    )


def count_field_utils_on_env(code):
    """Count FieldUtils read/write calls whose first argument is `*GravitinoEnv.getInstance()`."""
    count = 0
    for match in FIELD_UTILS_ANY.finditer(code):
        arguments = call_arguments(code, match.end() - 1)
        if arguments and ENV_LOCATOR_CALL.fullmatch(arguments[0]):
            count += 1
    return count


def count_instance_fields(code):
    """Count non-static fields declared directly in the GravitinoEnv class body."""
    match = re.search(r"\bclass\s+GravitinoEnv\b[^{]*\{", code)
    if not match:
        return 0
    depth = 1
    statement = []
    count = 0
    for char in code[match.end() :]:
        if char == "{":
            depth += 1
            statement = []  # a method, initializer or nested type: its header is not a field
        elif char == "}":
            depth -= 1
            if depth == 0:
                break
        elif depth == 1:
            if char == ";":
                text = "".join(statement)
                declaration = text.split("=", 1)[0]
                if text.strip() and not re.search(r"\bstatic\b", text) and "(" not in declaration:
                    count += 1
                statement = []
            else:
                statement.append(char)
    return count


def pattern_counter(pattern):
    return lambda code: len(pattern.findall(code))


# name -> (hard, counter). Hard metrics fail `check` when they rise; the others only warn.
FILE_METRICS = {
    "gravitino_env_get_instance": (True, pattern_counter(ENV_LOCATOR)),
    "tree_lock_legacy_overloads": (True, count_legacy_tree_locks),
    "singleton_get_instance": (True, count_singletons),
    "mock_static_gravitino_env": (True, pattern_counter(MOCK_STATIC_ENV)),
    "field_utils_gravitino_env": (True, count_field_utils_on_env),
    "legacy_env_access": (True, pattern_counter(LEGACY_ENV_ACCESS)),
    "field_utils_read_write": (False, pattern_counter(FIELD_UTILS_READ_WRITE)),
    "meta_service_get_instance": (False, pattern_counter(META_SERVICE_LOCATOR)),
}
# Metrics of GravitinoEnv.java itself, counted in the scope that owns that file.
ENV_METRICS = {
    "gravitino_env.instance_fields": True,
    "gravitino_env.lines": False,
    "gravitino_env.new_calls": False,
}


def is_hard(key):
    """Return whether a flat metric key such as `x.production.files` is a hard limit."""
    if key in ENV_METRICS:
        return ENV_METRICS[key]
    return FILE_METRICS[key.split(".")[0]][0]


def glob_regex(pattern):
    """Compile a repository path glob. `**` spans directories; `*` and `?` stay in one segment."""
    if pattern.endswith("/"):
        pattern += "**"
    tokens = re.split(r"(\*\*/|\*\*|\*|\?)", pattern)
    replacements = {"**/": "(?:.*/)?", "**": ".*", "*": "[^/]*", "?": "[^/]"}
    return re.compile("".join(replacements.get(token, re.escape(token)) for token in tokens))


class Scope:
    """A set of files with its own limits: the base baseline or one overlay."""

    def __init__(self, name, document, globs=()):
        self.name = name
        self.document = document
        self.globs = [glob_regex(glob) for glob in globs]
        self.migrated_packages = list(document.get("migrated_packages", []))
        self.counts = empty_counts()
        self.violations = []

    def owns(self, path):
        return any(regex.fullmatch(path) for regex in self.globs)

    def add(self, path, source):
        code = strip_code(source)
        kind = "test" if TEST_PATH.search(path) else "production"
        found = {}
        for name, (hard, counter) in FILE_METRICS.items():
            count = counter(code)
            if count:
                found[name] = count
                self.counts[f"{name}.{kind}.occurrences"] += count
                self.counts[f"{name}.{kind}.files"] += 1
        if path == ENV_FILE:
            self.counts["gravitino_env.instance_fields"] = count_instance_fields(code)
            self.counts["gravitino_env.lines"] = len(source.splitlines())
            self.counts["gravitino_env.new_calls"] = len(NEW_CALL.findall(code))

        package = PACKAGE.search(code)
        legacy = {name: count for name, count in found.items() if FILE_METRICS[name][0]}
        if (
            package
            and legacy
            and any(
                package.group(1) == migrated or package.group(1).startswith(migrated + ".")
                for migrated in self.migrated_packages
            )
        ):
            details = ", ".join(f"{name}={count}" for name, count in sorted(legacy.items()))
            self.violations.append(f"{path} is in a migrated package but has {details}")


def empty_counts():
    counts = {key: 0 for key in ENV_METRICS}
    for name in FILE_METRICS:
        for kind in KINDS:
            counts[f"{name}.{kind}.occurrences"] = 0
            counts[f"{name}.{kind}.files"] = 0
    return counts


def java_files(root):
    """Yield (repository-relative POSIX path, absolute path) for each Java file, sorted."""
    for directory, subdirectories, files in os.walk(root):
        subdirectories[:] = sorted(d for d in subdirectories if d not in SKIPPED_DIRS)
        for name in sorted(files):
            if name.endswith(".java"):
                absolute = Path(directory, name)
                yield absolute.relative_to(root).as_posix(), absolute


def scan(root, base, overlays=()):
    """Add every Java file to the first overlay that owns it, or else to the base scope.

    Returns the paths owned by more than one overlay.
    """
    conflicts = []
    for path, absolute in java_files(Path(root)):
        owners = [overlay for overlay in overlays if overlay.owns(path)]
        if len(owners) > 1:
            conflicts.append(f"{path} ({', '.join(owner.name for owner in owners)})")
        scope = owners[0] if owners else base
        scope.add(path, absolute.read_text(encoding="utf-8"))
    return conflicts


def compare(counts, limits):
    """Return (errors, warnings) for counts above their limits."""
    errors = []
    warnings = []
    for key, count in counts.items():
        if key not in limits:
            errors.append(f"{key} has no limit in the baseline; run update")
        elif count > limits[key]:
            message = f"{key} rose from {limits[key]} to {count}"
            (errors if is_hard(key) else warnings).append(message)
    return errors, warnings


def format_counts(counts):
    """Format counts as a small table."""
    lines = [f"{'metric':<34}{'production':>16}{'test':>16}"]
    for name, (hard, _) in FILE_METRICS.items():
        cells = [
            f"{counts[f'{name}.{kind}.occurrences']} in {counts[f'{name}.{kind}.files']}"
            for kind in KINDS
        ]
        label = name if hard else f"{name} (report)"
        lines.append(f"{label:<34}{cells[0]:>16}{cells[1]:>16}")
    for key, hard in ENV_METRICS.items():
        label = key if hard else f"{key} (report)"
        lines.append(f"{label:<34}{counts[key]:>16}")
    lines.append("(occurrences in files)")
    return "\n".join(lines)


def read_json(path, overlay=False):
    """Read a baseline or overlay file and check its required keys."""
    document = json.loads(Path(path).read_text(encoding="utf-8"))
    if document.get("schema_version") != 1:
        raise ValueError(f"{path}: schema_version must be 1")
    if not isinstance(document.get("migrated_packages", []), list):
        raise ValueError(f"{path}: migrated_packages must be a list")
    if overlay:
        for key in ("extends", "name"):
            if not isinstance(document.get(key), str) or not document[key]:
                raise ValueError(f"{path}: {key} must be a non-empty string")
        if document["name"] == "base":
            raise ValueError(f"{path}: the name 'base' is reserved")
        paths = document.get("paths")
        if not paths or not isinstance(paths, list):
            raise ValueError(f"{path}: paths must be a non-empty list of globs")
    return document


def load_scopes(args, create_missing=False):
    """Build the base scope and the overlay scopes named on the command line."""
    overlays = []
    for path in args.overlay:
        document = read_json(path, overlay=True)
        extends = (Path(path).resolve().parent / document["extends"]).resolve()
        overlays.append((Path(path), document, extends))

    if args.baseline:
        base_path = Path(args.baseline).resolve()
    elif overlays:
        base_path = overlays[0][2]
    else:
        base_path = (Path(args.root) / BASELINE).resolve()

    names = {"base"}
    for path, document, extends in overlays:
        if extends != base_path:
            raise ValueError(f"{path} extends {document['extends']}, not {base_path}")
        if document["name"] in names:
            raise ValueError(f"{path}: overlay name {document['name']!r} is used twice")
        names.add(document["name"])

    if create_missing and not base_path.exists():
        base_document = {"schema_version": 1}
    else:
        base_document = read_json(base_path)
    base = Scope("base", base_document)
    for package in getattr(args, "migrated_package", []):
        base.migrated_packages.append(package)
    scopes = [Scope(document["name"], document, document["paths"]) for _, document, _ in overlays]
    for scope in scopes:
        scope.migrated_packages.extend(getattr(args, "migrated_package", []))
    paths = {"base": base_path, **{doc["name"]: path for path, doc, _ in overlays}}
    return base, scopes, paths


def git_revision(root):
    """Return `git rev-parse --short HEAD`, or None outside a git checkout."""
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--short", "HEAD"],
            cwd=root,
            capture_output=True,
            text=True,
            check=True,
        )
    except (OSError, subprocess.CalledProcessError):
        return None
    return result.stdout.strip() or None


def run_report(args):
    base, overlays, _ = load_scopes(args, create_missing=True)
    conflicts = scan(args.root, base, overlays)
    scopes = [base, *overlays]
    if args.format == "json":
        print(json.dumps({scope.name: scope.counts for scope in scopes}, indent=2))
    else:
        for scope in scopes:
            if overlays:
                print(f"[{scope.name}]")
            print(format_counts(scope.counts))
    for conflict in conflicts:
        print(f"WARNING: owned by more than one overlay: {conflict}", file=sys.stderr)
    return 0


def run_check(args):
    base, overlays, _ = load_scopes(args)
    errors = [f"owned by more than one overlay: {path}" for path in scan(args.root, base, overlays)]
    warnings = []
    for scope in [base, *overlays]:
        prefix = f"[{scope.name}] " if overlays else ""
        scope_errors, scope_warnings = compare(scope.counts, scope.document.get("metrics", {}))
        errors += [prefix + message for message in scope_errors + scope.violations]
        warnings += [prefix + message for message in scope_warnings]
        if overlays:
            print(f"[{scope.name}]")
        print(format_counts(scope.counts))
    if args.strict_informational:
        errors += warnings
        warnings = []
    for message in warnings:
        print(f"WARNING: {message}", file=sys.stderr)
    for message in errors:
        print(f"ERROR: {message}", file=sys.stderr)
    if errors:
        print(f"FAILED: {len(errors)} problem(s)", file=sys.stderr)
        return 1
    print("PASS: no dependency-injection anti-pattern count rose")
    return 0


def run_update(args):
    base, overlays, paths = load_scopes(args, create_missing=True)
    conflicts = scan(args.root, base, overlays)
    if conflicts:
        for conflict in conflicts:
            print(f"ERROR: owned by more than one overlay: {conflict}", file=sys.stderr)
        return 1
    if args.overlays_only and not overlays:
        print("ERROR: --overlays-only needs at least one --overlay", file=sys.stderr)
        return 2
    revision = git_revision(args.root)
    for scope in overlays if args.overlays_only else [base, *overlays]:
        document = dict(scope.document, metrics=scope.counts)
        document.setdefault("migrated_packages", [])
        if revision:
            document["source_revision"] = revision
        if scope is base and args.description:
            document["description"] = args.description
        text = json.dumps(document, indent=2, sort_keys=True) + "\n"
        paths[scope.name].write_text(text, encoding="utf-8")
        print(f"updated {paths[scope.name]}")
    return 0


def parse_args(argv):
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--root", type=Path, default=ROOT, help="repository root")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("report", "check", "update"):
        command = commands.add_parser(name)
        command.add_argument("--baseline", help="base baseline (default: dev/di/baseline.json)")
        command.add_argument(
            "--overlay", action="append", default=[], help="overlay file; can repeat"
        )
    commands.choices["report"].add_argument("--format", choices=("text", "json"), default="text")
    commands.choices["check"].add_argument(
        "--migrated-package", action="append", default=[], help="treat a package as migrated"
    )
    commands.choices["check"].add_argument(
        "--strict-informational", action="store_true", help="fail on report-only rises too"
    )
    commands.choices["update"].add_argument("--description", help="set the base description")
    commands.choices["update"].add_argument(
        "--overlays-only",
        action="store_true",
        help="write only the overlay files and leave the base baseline unchanged",
    )
    return parser.parse_args(argv)


def main(argv=None):
    args = parse_args(argv)
    args.root = Path(args.root).resolve()
    runners = {"report": run_report, "check": run_check, "update": run_update}
    try:
        return runners[args.command](args)
    except (OSError, ValueError) as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
