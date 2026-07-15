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

"""Bind typed V1 table contract Gherkin scenarios to pytest-bdd."""

from pathlib import Path

from pytest_bdd import scenarios

from cuj.steps import v1_typed_table as v1_typed_table_steps
from cuj.steps.v1_typed_table import *  # noqa: F401,F403


_FEATURE = Path(__file__).parent / "features" / "v1_typed_table" / "lifecycle.feature"

# Import the module above before scenarios() so pytest-bdd discovers every
# fixture/step definition from the same test module as its generated tests.
assert v1_typed_table_steps is not None
scenarios(str(_FEATURE))
