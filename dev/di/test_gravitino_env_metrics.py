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

"""Tests for the GravitinoEnv migration metric checker."""

import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import gravitino_env_metrics as metrics  # noqa: E402


class TestGravitinoEnvMetrics(unittest.TestCase):
    """Verify lexical scanning and ratchet behavior."""

    def test_strip_comments_and_literals(self):
        source = r'''
          // GravitinoEnv.getInstance();
          String value = "FieldUtils.readField(target, name)";
          String block = """
              GravitinoEnv.getInstance();
              """;
          Object actual = GravitinoEnv.getInstance();
          /* FieldUtils.writeField(target, name, value); */
          Object field = FieldUtils.readField(target, name);
        '''

        code = metrics.strip_comments_and_literals(source)

        self.assertEqual(1, len(metrics.GET_INSTANCE_PATTERN.findall(code)))
        self.assertEqual(1, len(metrics.FIELD_UTILS_PATTERN.findall(code)))
        self.assertEqual(source.count("\n"), code.count("\n"))

    def test_env_metrics_count_top_level_instance_fields_and_constructors(self):
        source = """
          public class GravitinoEnv {
            private static final Object STATIC = new Object();
            private Object first;
            private final Object second = new Object();

            private static class Holder {
              private Object nested;
            }

            public Object method() {
              Object local = new Object();
              return local;
            }
          }
        """
        code = metrics.strip_comments_and_literals(source)

        self.assertEqual(2, metrics.count_instance_field_declarations(code))
        self.assertEqual(3, len(metrics.CONSTRUCTOR_PATTERN.findall(code)))

    def test_collect_metrics_groups_sources_and_checks_migrated_package(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            self.write_java(
                root,
                "core/src/main/java/org/apache/gravitino/GravitinoEnv.java",
                """
                package org.apache.gravitino;
                public class GravitinoEnv {
                  private Object dependency;
                  private static Object staticDependency;
                  Object make() { return new Object(); }
                }
                """,
            )
            self.write_java(
                root,
                "core/src/main/java/org/apache/gravitino/tag/TagService.java",
                """
                package org.apache.gravitino.tag;
                class TagService {
                  Object env = GravitinoEnv.getInstance();
                }
                """,
            )
            self.write_java(
                root,
                "core/src/test/java/org/apache/gravitino/tag/TestTagService.java",
                """
                package org.apache.gravitino.tag;
                class TestTagService {
                  Object env = GravitinoEnv.getInstance();
                  Object field = FieldUtils.readField(env, "tagDispatcher");
                }
                """,
            )

            current = metrics.collect_metrics(root, ["org.apache.gravitino.tag"])

            self.assertEqual(1, current["gravitino_env"]["instance_field_declarations"])
            self.assertEqual(1, current["gravitino_env"]["constructor_calls"])
            self.assertEqual(
                {
                    "occurrences": 2,
                    "files": 2,
                    "code_occurrences": 2,
                    "code_files": 2,
                },
                current["gravitino_env_get_instance"]["all"],
            )
            self.assertEqual(
                {
                    "occurrences": 1,
                    "files": 1,
                    "code_occurrences": 1,
                    "code_files": 1,
                },
                current["gravitino_env_get_instance"]["test"],
            )
            violation = current["migrated_package_violations"][0]
            self.assertEqual(2, violation["get_instance_occurrences"])
            self.assertEqual(1, violation["field_utils_occurrences"])
            self.assertEqual(2, len(violation["files"]))

    def test_compare_metrics_separates_hard_and_informational_regressions(self):
        baseline = self.metric_fixture()
        current = self.metric_fixture()
        current["gravitino_env_get_instance"]["production"]["code_occurrences"] += 1
        current["field_utils_read_write"]["test"]["occurrences"] += 1

        errors, warnings = metrics.compare_metrics(current, baseline)

        self.assertEqual(1, len(errors))
        self.assertIn("gravitino_env_get_instance.production.code_occurrences", errors[0])
        self.assertEqual(1, len(warnings))
        self.assertIn("field_utils_read_write.test.occurrences", warnings[0])

    def test_compare_metrics_rejects_migrated_package_legacy_access(self):
        baseline = self.metric_fixture()
        current = self.metric_fixture()
        current["migrated_package_violations"] = [
            {
                "package": "org.apache.gravitino.tag",
                "get_instance_occurrences": 1,
                "field_utils_occurrences": 2,
                "files": ["core/src/test/java/org/apache/gravitino/tag/TestTag.java"],
            }
        ]

        errors, warnings = metrics.compare_metrics(current, baseline)

        self.assertEqual([], warnings)
        self.assertEqual(1, len(errors))
        self.assertIn("migrated package org.apache.gravitino.tag", errors[0])

    @staticmethod
    def write_java(root, relative_path, source):
        """Write a Java fixture under a temporary repository root."""
        path = root / relative_path
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(source, encoding="utf-8")

    @staticmethod
    def metric_fixture():
        """Create a complete minimal metric tree for comparison tests."""
        return {
            "gravitino_env": {
                "path": metrics.ENV_PATH.as_posix(),
                "lines": 10,
                "instance_field_declarations": 2,
                "constructor_calls": 2,
            },
            "gravitino_env_get_instance": {
                "all": {
                    "occurrences": 2,
                    "files": 2,
                    "code_occurrences": 2,
                    "code_files": 2,
                },
                "production": {
                    "occurrences": 1,
                    "files": 1,
                    "code_occurrences": 1,
                    "code_files": 1,
                },
                "test": {
                    "occurrences": 1,
                    "files": 1,
                    "code_occurrences": 1,
                    "code_files": 1,
                },
            },
            "field_utils_read_write": {
                "all": {
                    "occurrences": 2,
                    "files": 2,
                    "code_occurrences": 2,
                    "code_files": 2,
                },
                "production": {
                    "occurrences": 1,
                    "files": 1,
                    "code_occurrences": 1,
                    "code_files": 1,
                },
                "test": {
                    "occurrences": 1,
                    "files": 1,
                    "code_occurrences": 1,
                    "code_files": 1,
                },
            },
            "migrated_package_violations": [],
        }


if __name__ == "__main__":
    unittest.main()
