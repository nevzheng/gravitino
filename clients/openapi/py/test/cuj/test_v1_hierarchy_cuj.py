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

"""Bind the V1 hierarchy lifecycle Gherkin inventory to pytest-bdd.

The step module deliberately uses only Playwright's APIRequestContext. These
scenarios are therefore real API CUJs, not a helper-only inventory or a browser
test that happens to mention the API.
"""

from pathlib import Path

from pytest_bdd import scenarios

from cuj.steps.v1_hierarchy import *  # noqa: F401,F403


_FEATURE_DIRECTORY = Path(__file__).parent / "features" / "v1_hierarchy"

# pytest-bdd creates its step fixtures in the module that defines them. The
# wildcard import intentionally copies those fixture markers into this test
# module before scenarios() creates its pytest functions.

scenarios(
    str(_FEATURE_DIRECTORY / "lifecycle.feature"),
    str(_FEATURE_DIRECTORY / "errors.feature"),
)
