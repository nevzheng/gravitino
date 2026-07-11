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
fixtures created by fixtures.sh, so generated requests target real entities.

Why this matters: any request to a *nonexistent* metalake returns 500 (the
authorization interceptor auto-provisions the current user into the metalake
before the not-found check runs, and that write assumes the metalake exists).
Unpinned, that one bug produces ~100 of ~130 500s and drowns every other
finding. Pinning routes around it so real conformance issues surface.

Two param names are pinned: metalake-level CRUD paths use `{name}` while every
sub-resource path uses `{metalake}` -- a path-parameter naming inconsistency in
the spec itself.

`map_path_parameters` only transforms the *fuzzing* generation phase; the
`coverage` phase injects deterministic values (e.g. "0") that bypass it, so
run.sh restricts to `--phases fuzzing` to keep the pin effective.
"""

import schemathesis

FIXTURES = {
    "name": "test_ml",       # metalake-level CRUD paths use {name}
    "metalake": "test_ml",   # sub-resource paths use {metalake}
    "catalog": "test_cat",
    "schema": "test_sch",
}


@schemathesis.hook
def map_path_parameters(context, path_parameters):
    if path_parameters:
        for key, value in FIXTURES.items():
            if key in path_parameters:
                path_parameters[key] = value
    return path_parameters
