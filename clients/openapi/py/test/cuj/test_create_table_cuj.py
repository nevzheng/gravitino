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

"""Bind the complete V1 create-table CUJ Gherkin inventory to pytest-bdd."""

from pathlib import Path

import pytest
from pytest_bdd import scenarios

from cuj.steps import create_table as create_table_steps
from cuj.steps.create_table import *  # noqa: F401,F403


_FEATURE_DIRECTORY = Path(__file__).parent / "features" / "create_table"
_INVENTORY_ERRORS = create_table_steps.feature_inventory_errors(_FEATURE_DIRECTORY)
if _INVENTORY_ERRORS:
    raise pytest.UsageError(
        "Create-table CUJ feature inventory drifted from provider profiles:\n- "
        + "\n- ".join(_INVENTORY_ERRORS)
    )


scenarios(
    str(_FEATURE_DIRECTORY / "common.feature"),
    str(_FEATURE_DIRECTORY / "schema.feature"),
    str(_FEATURE_DIRECTORY / "layout.feature"),
    str(_FEATURE_DIRECTORY / "lifecycle.feature"),
    str(_FEATURE_DIRECTORY / "errors.feature"),
)
