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

"""Report and ratchet GravitinoEnv migration metrics.

This tool deliberately uses lexical metrics instead of a Java parser so it can run in CI with only
Python's standard library. Comments and Java string/character literals are removed before matching.
"""

import argparse
import json
import re
import sys
from collections import Counter
from pathlib import Path


DEFAULT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_BASELINE = Path(__file__).with_name("gravitino_env_baseline.json")
ENV_PATH = Path("core/src/main/java/org/apache/gravitino/GravitinoEnv.java")
IGNORED_DIRECTORIES = {
    ".git",
    ".gradle",
    ".idea",
    ".venv",
    "build",
    "node_modules",
    "out",
    "target",
}

GET_INSTANCE_PATTERN = re.compile(r"\bGravitinoEnv\s*\.\s*getInstance\s*\(")
FIELD_UTILS_PATTERN = re.compile(
    r"\bFieldUtils\s*\.\s*(?:readField|writeField)\s*\("
)
TREE_LOCK_UTILS_CALL_PATTERN = re.compile(
    r"\bTreeLockUtils\s*\.\s*(doWithTreeLock|doWithRootTreeLock)\s*\("
)
LEGACY_RUNTIME_DEPENDENCIES_PATTERN = re.compile(
    r"\bLegacyRuntimeDependencies\s*(?:\.\s*([A-Za-z_$][\w$]*)|"
    r"::\s*([A-Za-z_$][\w$]*))"
)
PACKAGE_PATTERN = re.compile(r"(?m)^\s*package\s+([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*)\s*;")
CONSTRUCTOR_PATTERN = re.compile(
    r"\bnew\s+(?:[A-Za-z_$][\w$]*\s*\.\s*)*[A-Za-z_$][\w$]*"
    r"\s*(?:<[^;{}()]*>)?\s*\("
)

# These are direct measures of adding more state or service-locator access to the legacy
# environment. Increases fail check mode.
HARD_RATCHETS = (
    "gravitino_env.instance_field_declarations",
    "gravitino_env_get_instance.production.code_occurrences",
    "gravitino_env_get_instance.production.code_files",
    "gravitino_env_get_instance.test.code_occurrences",
    "gravitino_env_get_instance.test.code_files",
    "tree_lock_utils.do_with_tree_lock.legacy_3_arg.production.occurrences",
    "tree_lock_utils.do_with_tree_lock.legacy_3_arg.production.files",
    "tree_lock_utils.do_with_tree_lock.legacy_3_arg.test.occurrences",
    "tree_lock_utils.do_with_tree_lock.legacy_3_arg.test.files",
    "tree_lock_utils.do_with_root_tree_lock.legacy_2_arg.production.occurrences",
    "tree_lock_utils.do_with_root_tree_lock.legacy_2_arg.production.files",
    "tree_lock_utils.do_with_root_tree_lock.legacy_2_arg.test.occurrences",
    "tree_lock_utils.do_with_root_tree_lock.legacy_2_arg.test.files",
)

# These are useful trend indicators but are too syntactic or broad to reject an unrelated change.
INFORMATIONAL_RATCHETS = (
    "gravitino_env.lines",
    "gravitino_env.constructor_calls",
    "field_utils_read_write.production.occurrences",
    "field_utils_read_write.production.files",
    "field_utils_read_write.test.occurrences",
    "field_utils_read_write.test.files",
)


def strip_comments_and_literals(source):
    """Replace comments and literals with spaces while preserving source line positions."""
    result = []
    index = 0
    state = "code"

    while index < len(source):
        char = source[index]
        following = source[index + 1] if index + 1 < len(source) else ""

        if state == "code":
            if source.startswith('"""', index):
                result.extend("   ")
                index += 3
                state = "text_block"
            elif char == "/" and following == "/":
                result.extend("  ")
                index += 2
                state = "line_comment"
            elif char == "/" and following == "*":
                result.extend("  ")
                index += 2
                state = "block_comment"
            elif char == '"':
                result.append(" ")
                index += 1
                state = "string"
            elif char == "'":
                result.append(" ")
                index += 1
                state = "character"
            else:
                result.append(char)
                index += 1
            continue

        if state == "line_comment":
            if char == "\n":
                result.append("\n")
                state = "code"
            else:
                result.append(" ")
            index += 1
            continue

        if state == "block_comment":
            if char == "*" and following == "/":
                result.extend("  ")
                index += 2
                state = "code"
            else:
                result.append("\n" if char == "\n" else " ")
                index += 1
            continue

        if state == "text_block":
            if source.startswith('"""', index):
                result.extend("   ")
                index += 3
                state = "code"
            else:
                result.append("\n" if char == "\n" else " ")
                index += 1
            continue

        delimiter = '"' if state == "string" else "'"
        if char == "\\" and following:
            result.extend("\n " if following == "\n" else "  ")
            index += 2
        elif char == delimiter:
            result.append(" ")
            index += 1
            state = "code"
        else:
            result.append("\n" if char == "\n" else " ")
            index += 1

    return "".join(result)


def top_level_statements(class_source):
    """Return semicolon-terminated statements directly inside GravitinoEnv."""
    class_match = re.search(r"\bclass\s+GravitinoEnv\b[^\{]*\{", class_source)
    if not class_match:
        return []

    statements = []
    buffer = []
    depth = 1
    index = class_match.end()
    while index < len(class_source) and depth:
        char = class_source[index]
        if depth == 1:
            if char == "{":
                # A method, nested type, initializer block, or anonymous-class field initializer.
                # The last case is intentionally not counted by this lightweight scanner.
                depth += 1
                buffer = []
            elif char == "}":
                depth -= 1
                buffer = []
            elif char == ";":
                statements.append("".join(buffer).strip())
                buffer = []
            else:
                buffer.append(char)
        else:
            if char == "{":
                depth += 1
            elif char == "}":
                depth -= 1
                if depth == 1:
                    buffer = []
        index += 1
    return statements


def count_instance_field_declarations(class_source):
    """Count non-static field declarations directly inside GravitinoEnv."""
    count = 0
    for statement in top_level_statements(class_source):
        normalized = " ".join(statement.split())
        if not normalized or re.search(r"\bstatic\b", normalized):
            continue

        # A parenthesis before an initializer indicates an abstract/native method declaration.
        declaration = normalized.split("=", 1)[0]
        if "(" in declaration:
            continue
        count += 1
    return count


def count_call_arguments(code, opening_parenthesis):
    """Count top-level arguments in a stripped call, or return None if it is unbalanced."""
    if opening_parenthesis >= len(code) or code[opening_parenthesis] != "(":
        return None

    closing_delimiters = {"(": ")", "[": "]", "{": "}"}
    stack = [")"]
    commas = 0
    has_argument = False
    for char in code[opening_parenthesis + 1 :]:
        if char in closing_delimiters:
            stack.append(closing_delimiters[char])
            has_argument = True
        elif char in ")]}":
            if not stack or char != stack[-1]:
                return None
            stack.pop()
            if not stack:
                return commas + 1 if has_argument else 0
        elif char == "," and len(stack) == 1:
            commas += 1
        elif not char.isspace():
            has_argument = True
    return None


def find_tree_lock_utils_calls(code):
    """Return qualified TreeLockUtils calls as method-name/argument-count pairs."""
    calls = []
    for match in TREE_LOCK_UTILS_CALL_PATTERN.finditer(code):
        calls.append((match.group(1), count_call_arguments(code, match.end() - 1)))
    return calls


def find_legacy_runtime_dependencies_callsites(code, relative_path):
    """Return stable path/member identifiers for compatibility-bridge usages."""
    callsites = []
    for match in LEGACY_RUNTIME_DEPENDENCIES_PATTERN.finditer(code):
        member = match.group(1) or match.group(2)
        callsites.append(f"{relative_path.as_posix()}#{member}")
    return callsites


def is_test_source(path):
    """Return whether a repository-relative path is under a conventional test source set."""
    normalized = "/" + path.as_posix() + "/"
    return any(
        marker in normalized
        for marker in ("/src/test/", "/src/integrationTest/", "/src/testFixtures/")
    )


def java_files(root):
    """Yield repository Java sources in deterministic path order."""
    for path in sorted(root.rglob("*.java")):
        relative = path.relative_to(root)
        if not any(part in IGNORED_DIRECTORIES for part in relative.parts):
            yield relative, path


def empty_usage():
    """Create an empty occurrence/file count grouped by source kind."""
    return {
        "all": {"occurrences": 0, "files": 0, "code_occurrences": 0, "code_files": 0},
        "production": {
            "occurrences": 0,
            "files": 0,
            "code_occurrences": 0,
            "code_files": 0,
        },
        "test": {"occurrences": 0, "files": 0, "code_occurrences": 0, "code_files": 0},
    }


def empty_code_usage():
    """Create an empty code-only occurrence/file count grouped by source kind."""
    return {
        "all": {"occurrences": 0, "files": 0},
        "production": {"occurrences": 0, "files": 0},
        "test": {"occurrences": 0, "files": 0},
    }


def empty_tree_lock_utils_metrics():
    """Create metrics for legacy, explicit, and unclassified TreeLockUtils signatures."""
    return {
        "do_with_tree_lock": {
            "legacy_3_arg": empty_code_usage(),
            "explicit_lock_manager_4_arg": empty_code_usage(),
        },
        "do_with_root_tree_lock": {
            "legacy_2_arg": empty_code_usage(),
            "explicit_lock_manager_3_arg": empty_code_usage(),
        },
        "unclassified": empty_code_usage(),
    }


def record_usage(usage, textual_count, code_count, test_source):
    """Add one file's matches to a grouped usage count."""
    category = "test" if test_source else "production"
    for key in ("all", category):
        usage[key]["occurrences"] += textual_count
        usage[key]["code_occurrences"] += code_count
        if textual_count:
            usage[key]["files"] += 1
        if code_count:
            usage[key]["code_files"] += 1


def record_code_usage(usage, code_count, test_source):
    """Add one file's code-only matches to a grouped usage count."""
    category = "test" if test_source else "production"
    for key in ("all", category):
        usage[key]["occurrences"] += code_count
        if code_count:
            usage[key]["files"] += 1


def tree_lock_metric_path(method_name, argument_count):
    """Classify a TreeLockUtils call by method and argument count."""
    signatures = {
        ("doWithTreeLock", 3): ("do_with_tree_lock", "legacy_3_arg"),
        ("doWithTreeLock", 4): (
            "do_with_tree_lock",
            "explicit_lock_manager_4_arg",
        ),
        ("doWithRootTreeLock", 2): (
            "do_with_root_tree_lock",
            "legacy_2_arg",
        ),
        ("doWithRootTreeLock", 3): (
            "do_with_root_tree_lock",
            "explicit_lock_manager_3_arg",
        ),
    }
    return signatures.get((method_name, argument_count), ("unclassified",))


def package_matches(package_name, package_prefix):
    """Return whether a Java package is the prefix or one of its descendants."""
    return package_name == package_prefix or package_name.startswith(package_prefix + ".")


def collect_metrics(root, migrated_packages=()):
    """Collect migration metrics from a repository root."""
    root = Path(root).resolve()
    get_instance = empty_usage()
    field_utils = empty_usage()
    tree_lock_utils = empty_tree_lock_utils_metrics()
    legacy_runtime_dependencies_callsites = {"production": [], "test": []}
    violations = {
        package: {
            "get_instance_occurrences": 0,
            "field_utils_occurrences": 0,
            "legacy_tree_lock_occurrences": 0,
            "files": set(),
        }
        for package in sorted(set(migrated_packages))
    }

    for relative, absolute in java_files(root):
        source = absolute.read_text(encoding="utf-8")
        code = strip_comments_and_literals(source)
        textual_get_count = len(GET_INSTANCE_PATTERN.findall(source))
        get_count = len(GET_INSTANCE_PATTERN.findall(code))
        textual_field_utils_count = len(FIELD_UTILS_PATTERN.findall(source))
        field_utils_count = len(FIELD_UTILS_PATTERN.findall(code))
        test_source = is_test_source(relative)
        record_usage(get_instance, textual_get_count, get_count, test_source)
        record_usage(field_utils, textual_field_utils_count, field_utils_count, test_source)
        legacy_runtime_category = "test" if test_source else "production"
        legacy_runtime_dependencies_callsites[legacy_runtime_category].extend(
            find_legacy_runtime_dependencies_callsites(code, relative)
        )

        tree_lock_counts = {}
        for method_name, argument_count in find_tree_lock_utils_calls(code):
            metric_path = tree_lock_metric_path(method_name, argument_count)
            tree_lock_counts[metric_path] = tree_lock_counts.get(metric_path, 0) + 1
        for metric_path, count in tree_lock_counts.items():
            usage = tree_lock_utils
            for segment in metric_path:
                usage = usage[segment]
            record_code_usage(usage, count, test_source)
        legacy_tree_lock_count = sum(
            count
            for metric_path, count in tree_lock_counts.items()
            if metric_path
            in {
                ("do_with_tree_lock", "legacy_3_arg"),
                ("do_with_root_tree_lock", "legacy_2_arg"),
            }
        )

        package_match = PACKAGE_PATTERN.search(code)
        package_name = package_match.group(1) if package_match else ""
        for package, violation in violations.items():
            if package_matches(package_name, package):
                violation["get_instance_occurrences"] += get_count
                violation["field_utils_occurrences"] += field_utils_count
                violation["legacy_tree_lock_occurrences"] += legacy_tree_lock_count
                if get_count or field_utils_count or legacy_tree_lock_count:
                    violation["files"].add(relative.as_posix())

    env_file = root / ENV_PATH
    if env_file.exists():
        env_source = env_file.read_text(encoding="utf-8")
        env_code = strip_comments_and_literals(env_source)
        env_metrics = {
            "path": ENV_PATH.as_posix(),
            "lines": len(env_source.splitlines()),
            "instance_field_declarations": count_instance_field_declarations(env_code),
            "constructor_calls": len(CONSTRUCTOR_PATTERN.findall(env_code)),
        }
    else:
        env_metrics = {
            "path": ENV_PATH.as_posix(),
            "lines": 0,
            "instance_field_declarations": 0,
            "constructor_calls": 0,
        }

    return {
        "gravitino_env": env_metrics,
        "gravitino_env_get_instance": get_instance,
        "field_utils_read_write": field_utils,
        "tree_lock_utils": tree_lock_utils,
        "legacy_runtime_dependencies": {
            "production_callsites": sorted(
                legacy_runtime_dependencies_callsites["production"]
            ),
            "test_callsites": sorted(legacy_runtime_dependencies_callsites["test"]),
        },
        "migrated_package_violations": [
            {
                "package": package,
                "get_instance_occurrences": violation["get_instance_occurrences"],
                "field_utils_occurrences": violation["field_utils_occurrences"],
                "legacy_tree_lock_occurrences": violation[
                    "legacy_tree_lock_occurrences"
                ],
                "files": sorted(violation["files"]),
            }
            for package, violation in violations.items()
        ],
    }


def nested_value(mapping, dotted_path):
    """Read a nested dictionary value using a dotted path."""
    value = mapping
    for segment in dotted_path.split("."):
        value = value[segment]
    return value


def compare_metrics(current, baseline, allowed_legacy_runtime_callsites=()):
    """Return hard errors and informational warnings relative to a baseline."""
    errors = []
    warnings = []
    for dotted_path in HARD_RATCHETS:
        before = nested_value(baseline, dotted_path)
        after = nested_value(current, dotted_path)
        if after > before:
            errors.append(f"{dotted_path} increased from {before} to {after}")

    for dotted_path in INFORMATIONAL_RATCHETS:
        before = nested_value(baseline, dotted_path)
        after = nested_value(current, dotted_path)
        if after > before:
            warnings.append(f"{dotted_path} increased from {before} to {after}")

    unexpected_legacy_runtime_callsites = list(
        (
            Counter(
                current["legacy_runtime_dependencies"]["production_callsites"]
            )
            - Counter(allowed_legacy_runtime_callsites)
        ).elements()
    )
    if unexpected_legacy_runtime_callsites:
        errors.append(
            "LegacyRuntimeDependencies contains unapproved callsite(s): "
            + ", ".join(sorted(unexpected_legacy_runtime_callsites))
        )

    for violation in current["migrated_package_violations"]:
        if (
            violation["get_instance_occurrences"]
            or violation["field_utils_occurrences"]
            or violation["legacy_tree_lock_occurrences"]
        ):
            errors.append(
                "migrated package {package} contains {get_count} GravitinoEnv.getInstance "
                "occurrence(s), {field_count} FieldUtils read/write occurrence(s), and "
                "{tree_lock_count} legacy TreeLockUtils occurrence(s) in: {files}".format(
                    package=violation["package"],
                    get_count=violation["get_instance_occurrences"],
                    field_count=violation["field_utils_occurrences"],
                    tree_lock_count=violation["legacy_tree_lock_occurrences"],
                    files=", ".join(violation["files"]),
                )
            )
    return errors, warnings


def text_report(metrics):
    """Format metrics for human-readable command output."""
    env = metrics["gravitino_env"]
    lines = [
        "GravitinoEnv migration metrics",
        f"  lines: {env['lines']}",
        f"  instance field declarations: {env['instance_field_declarations']}",
        f"  constructor calls: {env['constructor_calls']}",
    ]
    for label, key in (
        ("GravitinoEnv.getInstance", "gravitino_env_get_instance"),
        ("FieldUtils.readField/writeField", "field_utils_read_write"),
    ):
        lines.append(label)
        for category in ("all", "production", "test"):
            usage = metrics[key][category]
            lines.append(
                "  {category}: {occurrences} textual / {code_occurrences} code occurrence(s), "
                "{files} textual / {code_files} code file(s)".format(
                    category=category,
                    occurrences=usage["occurrences"],
                    code_occurrences=usage["code_occurrences"],
                    files=usage["files"],
                    code_files=usage["code_files"],
                )
            )

    lines.append("Qualified TreeLockUtils calls (classified by argument count)")
    for label, metric_path in (
        (
            "doWithTreeLock legacy (3 args)",
            ("do_with_tree_lock", "legacy_3_arg"),
        ),
        (
            "doWithTreeLock explicit LockManager (4 args)",
            ("do_with_tree_lock", "explicit_lock_manager_4_arg"),
        ),
        (
            "doWithRootTreeLock legacy (2 args)",
            ("do_with_root_tree_lock", "legacy_2_arg"),
        ),
        (
            "doWithRootTreeLock explicit LockManager (3 args)",
            ("do_with_root_tree_lock", "explicit_lock_manager_3_arg"),
        ),
        ("unclassified", ("unclassified",)),
    ):
        usage = metrics["tree_lock_utils"]
        for segment in metric_path:
            usage = usage[segment]
        lines.append(f"  {label}")
        for category in ("all", "production", "test"):
            category_usage = usage[category]
            lines.append(
                "    {category}: {occurrences} occurrence(s), {files} file(s)".format(
                    category=category,
                    occurrences=category_usage["occurrences"],
                    files=category_usage["files"],
                )
            )

    lines.append("LegacyRuntimeDependencies compatibility-bridge callsites")
    for category in ("production", "test"):
        callsites = metrics["legacy_runtime_dependencies"][f"{category}_callsites"]
        lines.append(f"  {category}: {len(callsites)}")
        for callsite in callsites:
            lines.append(f"    {callsite}")

    if metrics["migrated_package_violations"]:
        lines.append("Migrated package checks")
        for violation in metrics["migrated_package_violations"]:
            lines.append(
                (
                    "  {package}: getInstance={get_count}, FieldUtils={field_count}, "
                    "legacy TreeLockUtils={tree_lock_count}, files={files}"
                ).format(
                    package=violation["package"],
                    get_count=violation["get_instance_occurrences"],
                    field_count=violation["field_utils_occurrences"],
                    tree_lock_count=violation["legacy_tree_lock_occurrences"],
                    files=len(violation["files"]),
                )
            )
    return "\n".join(lines)


def load_baseline(path):
    """Load and minimally validate a checked-in baseline file."""
    with Path(path).open(encoding="utf-8") as baseline_file:
        baseline = json.load(baseline_file)
    if baseline.get("schema_version") != 1 or "metrics" not in baseline:
        raise ValueError("baseline must use schema_version 1 and contain metrics")
    allowed_callsites = baseline.get(
        "legacy_runtime_dependencies_allowed_callsites", []
    )
    if not isinstance(allowed_callsites, list) or not all(
        isinstance(callsite, str) for callsite in allowed_callsites
    ):
        raise ValueError(
            "legacy_runtime_dependencies_allowed_callsites must be a list of strings"
        )
    return baseline


def build_parser():
    """Build the command-line parser."""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=DEFAULT_ROOT, help="repository root")
    subparsers = parser.add_subparsers(dest="command", required=True)

    report = subparsers.add_parser("report", help="print current metrics")
    report.add_argument("--format", choices=("text", "json"), default="text")
    report.add_argument("--migrated-package", action="append", default=[])

    check = subparsers.add_parser("check", help="check current metrics against the baseline")
    check.add_argument("--baseline", type=Path, default=DEFAULT_BASELINE)
    check.add_argument("--migrated-package", action="append", default=[])
    check.add_argument(
        "--strict-informational",
        action="store_true",
        help="promote increases in informational metrics to failures",
    )
    return parser


def main(arguments=None):
    """Run the command-line program and return its exit status."""
    args = build_parser().parse_args(arguments)
    if args.command == "report":
        metrics = collect_metrics(args.root, args.migrated_package)
        if args.format == "json":
            print(json.dumps(metrics, indent=2, sort_keys=True))
        else:
            print(text_report(metrics))
        return 0

    try:
        baseline = load_baseline(args.baseline)
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"Unable to load baseline: {error}", file=sys.stderr)
        return 2

    migrated_packages = sorted(
        set(baseline.get("migrated_packages", [])) | set(args.migrated_package)
    )
    current = collect_metrics(args.root, migrated_packages)
    errors, warnings = compare_metrics(
        current,
        baseline["metrics"],
        baseline.get("legacy_runtime_dependencies_allowed_callsites", []),
    )
    print(text_report(current))
    for warning in warnings:
        print(f"WARNING: {warning}", file=sys.stderr)
    if args.strict_informational:
        errors.extend(warnings)
    for error in errors:
        print(f"ERROR: {error}", file=sys.stderr)
    if errors:
        print(f"FAILED: {len(errors)} migration metric check(s)", file=sys.stderr)
        return 1
    print("PASS: GravitinoEnv migration ratchets did not regress")
    return 0


if __name__ == "__main__":
    sys.exit(main())
