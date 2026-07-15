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

"""Collection-level coverage for the V1 hierarchy pytest-bdd feature binding."""

from pathlib import Path
import subprocess
import sys

import pytest


@pytest.mark.cuj
def test_v1_hierarchy_feature_scenarios_collect_through_pytest_bdd() -> None:
    """Each hierarchy Gherkin scenario is bound to a real pytest collection item."""
    project_root = Path(__file__).resolve().parents[3]
    result = subprocess.run(
        [
            sys.executable,
            "-m",
            "pytest",
            "--collect-only",
            "-q",
            "test/cuj/test_v1_hierarchy_cuj.py",
        ],
        cwd=project_root,
        capture_output=True,
        check=False,
        text=True,
    )

    assert result.returncode == 0, (
        "V1 hierarchy Gherkin collection failed.\n"
        f"stdout:\n{result.stdout}\n"
        f"stderr:\n{result.stderr}"
    )
    collected = [
        line
        for line in result.stdout.splitlines()
        if "test_v1_hierarchy_cuj.py::" in line
    ]
    # One parent lifecycle, twelve writable-provider table lifecycles, three
    # parent precondition examples, and one schema capability scenario.
    assert len(collected) == 17, result.stdout
