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

"""Unit tests for di_ratchet.py."""

import contextlib
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))

import di_ratchet  # noqa: E402


def counts_of(java):
    """Return the per-file metric counts for a Java snippet."""
    code = di_ratchet.strip_code(java)
    return {name: counter(code) for name, (_, counter) in di_ratchet.FILE_METRICS.items()}


class TestPatterns(unittest.TestCase):
    """Each metric counts what it should and nothing in comments or literals."""

    def test_strip_code_blanks_comments_and_literals(self):
        java = '''
            // GravitinoEnv.getInstance();
            /* GravitinoEnv.getInstance(); */
            String s = "GravitinoEnv.getInstance()";
            char c = '"';
            String t = """
                GravitinoEnv.getInstance();
                """;
            String u = "escaped \\" GravitinoEnv.getInstance()";
            Object real = GravitinoEnv.getInstance();
            '''
        code = di_ratchet.strip_code(java)

        self.assertEqual(java.count("\n"), code.count("\n"))
        self.assertEqual(1, counts_of(java)["gravitino_env_get_instance"])

    def test_env_locator_includes_subclasses(self):
        java = """
            GravitinoEnv.getInstance();
            DatastratoGravitinoEnv.getInstance();
            ExtendedDatastratoGravitinoEnv . getInstance ( );
            GravitinoEnvironment.getInstance();
            """
        self.assertEqual(3, counts_of(java)["gravitino_env_get_instance"])

    def test_call_arguments(self):
        code = "f(a(b, c), new int[] {1, 2}, () -> { x(); }) g() h(a"
        self.assertEqual(3, len(di_ratchet.call_arguments(code, 1)))
        self.assertEqual([], di_ratchet.call_arguments(code, code.index("g(") + 1))
        self.assertIsNone(di_ratchet.call_arguments(code, code.index("h(") + 1))
        self.assertIsNone(di_ratchet.call_arguments("f(a]", 1))

    def test_legacy_tree_lock_overloads(self):
        java = """
            TreeLockUtils.doWithTreeLock(ident, LockType.READ, () -> load(a, b));
            TreeLockUtils.doWithTreeLock(lockManager, ident, LockType.READ, () -> load(a));
            TreeLockUtils.doWithRootTreeLock(LockType.WRITE, Worker::run);
            TreeLockUtils.doWithRootTreeLock(lockManager, LockType.WRITE, Worker::run);
            doWithTreeLock(ident, LockType.READ, action);
            """
        self.assertEqual(2, counts_of(java)["tree_lock_legacy_overloads"])

    def test_singleton_declarations(self):
        java = """
            public static Foo getInstance() {}
            public static synchronized Foo getInstance() {}
            static public final Map<String, List<Bar>> getInstance(Config c) {}
            public static <T> Holder<T> getInstance(Class<T> type) {}
            public static org.apache.Foo getInstance() {}
            private static Foo getInstance() {}
            public Foo getInstance() {}
            public static Foo getInstanceOf() {}
            Foo foo = Foo.getInstance();
            """
        self.assertEqual(5, counts_of(java)["singleton_get_instance"])

    def test_mock_static_env(self):
        java = """
            mockStatic(GravitinoEnv.class);
            Mockito.mockStatic(GravitinoEnv.class, CALLS_REAL_METHODS);
            mockStatic( DatastratoGravitinoEnv . class );
            mockStatic(Other.class);
            """
        self.assertEqual(3, counts_of(java)["mock_static_gravitino_env"])

    def test_field_utils_on_env(self):
        java = """
            FieldUtils.writeField(GravitinoEnv.getInstance(), "a", a, true);
            FieldUtils.writeField(
                DatastratoGravitinoEnv.getInstance(),
                "b",
                new LockManager(config),
                true);
            FieldUtils.readField(GravitinoEnv.getInstance(), "c", true);
            FieldUtils.writeDeclaredField(GravitinoEnv.getInstance(), "d", d, true);
            FieldUtils.writeField(GravitinoEnv.getInstance().catalogManager(), "e", e, true);
            FieldUtils.writeField(target, "env", GravitinoEnv.getInstance(), true);
            FieldUtils.readField(target, "f", true);
            """
        counts = counts_of(java)
        self.assertEqual(4, counts["field_utils_gravitino_env"])
        self.assertEqual(6, counts["field_utils_read_write"])

    def test_legacy_env_access_and_meta_service(self):
        java = """
            @LegacyEnvAccess
            @org.apache.gravitino.annotation.LegacyEnvAccess(reason = "x")
            class LegacyEnvAccessUser {
              Object a = UserMetaService.getInstance();
              Object b = IdpGroupMetaService.getInstance().list();
              Object c = MetaServices.getInstance();
            }
            """
        counts = counts_of(java)
        self.assertEqual(2, counts["legacy_env_access"])
        self.assertEqual(2, counts["meta_service_get_instance"])

    def test_instance_fields(self):
        java = """
            public class GravitinoEnv {
              private static final GravitinoEnv INSTANCE = new GravitinoEnv();
              private Config config;
              private final Map<String, Object> cache = new HashMap<>();
              private volatile boolean started = false;
              public Config config() { Object local = null; return config; }
              private static class Holder { private Object nested; }
              abstract void hook();
            }
            """
        self.assertEqual(3, di_ratchet.count_instance_fields(di_ratchet.strip_code(java)))

    def test_glob_regex(self):
        cases = [
            ("**/com/datastrato/**", "core/src/main/java/com/datastrato/A.java", True),
            ("**/com/datastrato/**", "com/datastrato/A.java", True),
            ("**/com/datastrato/**", "core/src/main/java/com/other/A.java", False),
            ("core-extension/", "core-extension/src/A.java", True),
            ("core-extension/**", "core/src/A.java", False),
            ("server/*/A.java", "server/src/A.java", True),
            ("server/*/A.java", "server/src/main/A.java", False),
            ("a.b/?.java", "a.b/X.java", True),
            ("a.b/?.java", "aXb/X.java", False),
        ]
        for pattern, path, expected in cases:
            with self.subTest(pattern=pattern, path=path):
                self.assertEqual(expected, bool(di_ratchet.glob_regex(pattern).fullmatch(path)))


class TestCommands(unittest.TestCase):
    """report, check and update against a small temporary repository."""

    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.baseline = self.root / "dev/di/baseline.json"
        self.baseline.parent.mkdir(parents=True)
        self.write(
            di_ratchet.ENV_FILE,
            """
            package org.apache.gravitino;
            public class GravitinoEnv {
              private Config config;
              public static GravitinoEnv getInstance() { return INSTANCE; }
            }
            """,
        )
        self.write(
            "core/src/main/java/org/apache/gravitino/tag/TagManager.java",
            "package org.apache.gravitino.tag;\nclass TagManager {}\n",
        )
        self.write(
            "core/src/test/java/org/apache/gravitino/TestThing.java",
            """
            package org.apache.gravitino;
            class TestThing {
              void setUp() {
                mockStatic(GravitinoEnv.class);
                FieldUtils.writeField(GravitinoEnv.getInstance(), "config", config, true);
              }
            }
            """,
        )
        self.write(
            "server/src/main/java/com/datastrato/Enterprise.java",
            """
            package com.datastrato;
            class Enterprise {
              Object a = DatastratoGravitinoEnv.getInstance();
              Object b = GravitinoEnv.getInstance();
            }
            """,
        )
        patcher = mock.patch.object(di_ratchet, "git_revision", return_value="abc1234")
        patcher.start()
        self.addCleanup(patcher.stop)

    def write(self, path, text):
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def run_tool(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            status = di_ratchet.main(["--root", str(self.root), *argv])
        return status, out.getvalue(), err.getvalue()

    def read(self, path):
        return json.loads(Path(path).read_text(encoding="utf-8"))

    def write_overlay(self, name="enterprise", paths=("**/com/datastrato/**",), **extra):
        path = self.baseline.parent / f"{name}.json"
        document = {"schema_version": 1, "extends": "baseline.json", "name": name, "paths": paths}
        document.update(extra)
        path.write_text(json.dumps(document), encoding="utf-8")
        return path

    def test_report_counts_production_and_test_separately(self):
        status, out, _ = self.run_tool("report", "--format", "json")

        counts = json.loads(out)["base"]
        self.assertEqual(0, status)
        self.assertEqual(2, counts["gravitino_env_get_instance.production.occurrences"])
        self.assertEqual(1, counts["gravitino_env_get_instance.production.files"])
        self.assertEqual(1, counts["gravitino_env_get_instance.test.occurrences"])
        self.assertEqual(1, counts["mock_static_gravitino_env.test.occurrences"])
        self.assertEqual(1, counts["field_utils_gravitino_env.test.occurrences"])
        self.assertEqual(1, counts["singleton_get_instance.production.files"])
        self.assertEqual(1, counts["gravitino_env.instance_fields"])

    def test_update_writes_limits_and_check_passes(self):
        status, _, _ = self.run_tool("update", "--description", "first")

        document = self.read(self.baseline)
        self.assertEqual(0, status)
        self.assertEqual("first", document["description"])
        self.assertEqual("abc1234", document["source_revision"])
        self.assertEqual([], document["migrated_packages"])
        self.assertEqual(
            2, document["metrics"]["gravitino_env_get_instance.production.occurrences"]
        )
        self.assertEqual(0, self.run_tool("check")[0])

    def test_update_keeps_other_keys_and_lowers_limits(self):
        self.baseline.write_text(
            json.dumps(
                {
                    "schema_version": 1,
                    "description": "kept",
                    "migrated_packages": ["org.apache.gravitino.tag"],
                    "note": "kept",
                    "metrics": {"gravitino_env_get_instance.production.occurrences": 50},
                }
            ),
            encoding="utf-8",
        )

        self.run_tool("update")

        document = self.read(self.baseline)
        self.assertEqual("kept", document["description"])
        self.assertEqual("kept", document["note"])
        self.assertEqual(["org.apache.gravitino.tag"], document["migrated_packages"])
        self.assertEqual(
            2, document["metrics"]["gravitino_env_get_instance.production.occurrences"]
        )

    def test_update_without_git_keeps_old_revision(self):
        self.baseline.write_text('{"schema_version": 1, "source_revision": "old"}', "utf-8")

        with mock.patch.object(di_ratchet, "git_revision", return_value=None):
            self.run_tool("update")

        self.assertEqual("old", self.read(self.baseline)["source_revision"])

    def test_check_fails_when_a_hard_count_rises(self):
        self.run_tool("update")
        self.write(
            "core/src/main/java/New.java", "class New { Object e = GravitinoEnv.getInstance(); }"
        )

        status, _, err = self.run_tool("check")

        self.assertEqual(1, status)
        self.assertIn("gravitino_env_get_instance.production.occurrences rose from 2 to 3", err)
        self.assertIn("gravitino_env_get_instance.production.files rose from 1 to 2", err)

    def test_check_warns_on_report_only_rise_unless_strict(self):
        self.run_tool("update")
        self.write(
            "core/src/main/java/Meta.java",
            "class Meta { Object m = UserMetaService.getInstance(); }",
        )

        status, _, err = self.run_tool("check")
        strict_status, _, strict_err = self.run_tool("check", "--strict-informational")

        self.assertEqual(0, status)
        self.assertIn("WARNING: meta_service_get_instance.production.occurrences rose", err)
        self.assertEqual(1, strict_status)
        self.assertIn("ERROR: meta_service_get_instance.production.occurrences rose", strict_err)

    def test_check_fails_on_missing_limit(self):
        self.run_tool("update")
        document = self.read(self.baseline)
        del document["metrics"]["legacy_env_access.test.files"]
        self.baseline.write_text(json.dumps(document), encoding="utf-8")

        status, _, err = self.run_tool("check")

        self.assertEqual(1, status)
        self.assertIn("legacy_env_access.test.files has no limit", err)

    def test_check_fails_without_baseline(self):
        status, _, err = self.run_tool("check")

        self.assertEqual(2, status)
        self.assertIn("baseline.json", err)

    def test_migrated_package_must_be_clean(self):
        self.run_tool("update")
        self.assertEqual(
            0, self.run_tool("check", "--migrated-package", "org.apache.gravitino.tag")[0]
        )
        self.write(
            "core/src/test/java/org/apache/gravitino/tag/sub/TestTag.java",
            "package org.apache.gravitino.tag.sub;\n"
            "class TestTag { Object e = GravitinoEnv.getInstance(); }\n",
        )
        document = self.read(self.baseline)
        document["metrics"]["gravitino_env_get_instance.test.occurrences"] = 99
        document["metrics"]["gravitino_env_get_instance.test.files"] = 99
        document["migrated_packages"] = ["org.apache.gravitino.tag"]
        self.baseline.write_text(json.dumps(document), encoding="utf-8")

        status, _, err = self.run_tool("check")

        self.assertEqual(1, status)
        self.assertIn(
            "TestTag.java is in a migrated package but has gravitino_env_get_instance=1", err
        )

    def test_overlay_splits_counts_by_path(self):
        overlay = self.write_overlay(description="enterprise")

        self.assertEqual(0, self.run_tool("update", "--overlay", str(overlay))[0])

        base = self.read(self.baseline)["metrics"]
        own = self.read(overlay)
        self.assertEqual("enterprise", own["description"])
        self.assertEqual(["**/com/datastrato/**"], own["paths"])
        self.assertEqual(0, base["gravitino_env_get_instance.production.occurrences"])
        self.assertEqual(2, own["metrics"]["gravitino_env_get_instance.production.occurrences"])
        self.assertEqual(1, base["gravitino_env.instance_fields"])
        self.assertEqual(0, own["metrics"]["gravitino_env.instance_fields"])
        self.assertEqual(0, self.run_tool("check", "--overlay", str(overlay))[0])

    def test_overlay_growth_is_reported_in_its_scope(self):
        overlay = self.write_overlay()
        self.run_tool("update", "--overlay", str(overlay))
        self.write(
            "server/src/main/java/com/datastrato/More.java",
            "class More { Object e = GravitinoEnv.getInstance(); }",
        )

        status, out, err = self.run_tool("check", "--overlay", str(overlay))

        self.assertEqual(1, status)
        self.assertIn("[enterprise]", out)
        self.assertIn(
            "[enterprise] gravitino_env_get_instance.production.occurrences rose from 2 to 3", err
        )
        self.assertNotIn("[base] gravitino_env_get_instance", err)

    def test_update_overlays_only_leaves_the_base_unchanged(self):
        self.baseline.write_text('{"schema_version": 1, "note": "upstream"}', "utf-8")
        before = self.baseline.read_text(encoding="utf-8")
        overlay = self.write_overlay()

        status, out, _ = self.run_tool("update", "--overlays-only", "--overlay", str(overlay))

        self.assertEqual(0, status)
        self.assertEqual(before, self.baseline.read_text(encoding="utf-8"))
        self.assertNotIn(str(self.baseline), out)
        own = self.read(overlay)["metrics"]
        self.assertEqual(2, own["gravitino_env_get_instance.production.occurrences"])

    def test_update_overlays_only_needs_an_overlay(self):
        status, _, err = self.run_tool("update", "--overlays-only")

        self.assertEqual(2, status)
        self.assertIn("--overlays-only needs at least one --overlay", err)

    def test_without_overlay_base_limits_cover_every_file(self):
        overlay = self.write_overlay()
        self.run_tool("update", "--overlay", str(overlay))

        status, _, err = self.run_tool("check")

        self.assertEqual(1, status)
        self.assertIn("gravitino_env_get_instance.production.occurrences rose from 0 to 2", err)

    def test_overlay_migrated_packages_apply_to_its_files_only(self):
        overlay = self.write_overlay(migrated_packages=["com.datastrato"])
        self.run_tool("update", "--overlay", str(overlay))

        status, _, err = self.run_tool("check", "--overlay", str(overlay))

        self.assertEqual(1, status)
        self.assertIn("[enterprise] server/src/main/java/com/datastrato/Enterprise.java", err)
        self.assertNotIn("[base]", err)

    def test_overlay_must_extend_the_base(self):
        self.run_tool("update")
        overlay = self.write_overlay(extends="other.json")

        status, _, err = self.run_tool(
            "check", "--baseline", str(self.baseline), "--overlay", str(overlay)
        )

        self.assertEqual(2, status)
        self.assertIn("extends other.json", err)

    def test_invalid_overlays_are_rejected(self):
        cases = [
            ({"paths": []}, "paths must be a non-empty list"),
            ({"paths": "**"}, "paths must be a non-empty list"),
            ({"name": ""}, "name must be a non-empty string"),
            ({"name": "base"}, "reserved"),
            ({"schema_version": 2}, "schema_version must be 1"),
        ]
        for extra, message in cases:
            with self.subTest(message=message):
                status, _, err = self.run_tool(
                    "check", "--overlay", str(self.write_overlay(**extra))
                )
                self.assertEqual(2, status)
                self.assertIn(message, err)

    def test_file_owned_by_two_overlays_fails(self):
        self.run_tool("update")
        first = self.write_overlay("first", ["server/**"])
        second = self.write_overlay("second", ["**/com/datastrato/**"])
        overlays = ("--overlay", str(first), "--overlay", str(second))

        update_status, _, update_err = self.run_tool("update", *overlays)
        check_status, _, check_err = self.run_tool("check", *overlays)

        self.assertEqual(1, update_status)
        self.assertNotIn("metrics", self.read(first))
        self.assertEqual(1, check_status)
        for err in (update_err, check_err):
            self.assertIn("Enterprise.java (first, second)", err)

    def test_report_with_overlay_has_one_section_per_scope(self):
        overlay = self.write_overlay()

        status, out, _ = self.run_tool("report", "--format", "json", "--overlay", str(overlay))

        self.assertEqual(0, status)
        self.assertEqual({"base", "enterprise"}, set(json.loads(out)))


if __name__ == "__main__":
    unittest.main()
