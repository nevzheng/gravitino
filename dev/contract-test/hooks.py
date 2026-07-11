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

"""Schemathesis hooks for the Gravitino contract test.

Pins the metalake -> catalog -> schema hierarchy path parameters to the
fixtures created by fixtures.sh, so generated requests reach real entities
instead of exercising the (separately tracked) "operation on a nonexistent
metalake returns 500" bug on every request. Path params without a fixture
(table, fileset, topic, ...) keep their generated values and yield the
documented 404s.
"""

import schemathesis

FIXTURES = {
    "metalake": "test_ml",
    "catalog": "test_cat",
    "schema": "test_sch",
}


@schemathesis.hook
def before_generate_path_parameters(context, strategy):
    def _pin(params):
        for key, value in FIXTURES.items():
            if key in params:
                params[key] = value
        return params

    return strategy.map(_pin)
