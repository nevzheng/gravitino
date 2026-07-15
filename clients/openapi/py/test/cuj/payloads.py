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

"""Documented create-table request payloads, keyed by use case.

This module is the extension point for connector-specific coverage. Each builder
returns the JSON request body for one documented use case; the harness posts it
verbatim. A builder receives the resolved table name so it can be generated per
scenario for isolation.

Only the provider-agnostic ``minimal`` and ``comment_and_properties`` payloads
plus the invalid-request payloads are modeled here. The remaining documented use
cases named by the feature files (partitioning, indexes, defaults, lifecycle
modes, complex schemas, ...) are connector-specific and are added incrementally;
requesting one raises :class:`PendingUseCase`, which the steps surface as a skip
with a clear reason rather than a false failure.
"""

from collections.abc import Callable

# JSON request body type alias kept intentionally loose: payloads mirror the wire
# contract, not the generated pydantic models, so a journey can inspect the exact
# bytes it sends.
Payload = dict[str, object]


class PendingUseCase(Exception):
    """Raised when a documented use case has no payload builder yet."""


def _minimal(table_name: str) -> Payload:
    return {
        "name": table_name,
        "columns": [
            {"name": "id", "type": "long", "nullable": False, "comment": "primary id"}
        ],
    }


def _comment_and_properties(table_name: str) -> Payload:
    payload = _minimal(table_name)
    payload["comment"] = "a documented create-table journey table"
    payload["properties"] = {"cuj.owner": "openapi-cuj", "cuj.managed": "true"}
    return payload


# Documented positive use cases with a provider-agnostic payload.
_CREATE_BUILDERS: dict[str, Callable[[str], Payload]] = {
    "minimal": _minimal,
    "comment_and_properties": _comment_and_properties,
}


def _invalid_blank_table_name(table_name: str) -> Payload:
    payload = _minimal(table_name)
    payload["name"] = ""
    return payload


def _invalid_missing_columns(table_name: str) -> Payload:
    payload = _minimal(table_name)
    payload["columns"] = []
    return payload


def _invalid_duplicate_column_names(table_name: str) -> Payload:
    payload = _minimal(table_name)
    payload["columns"] = [
        {"name": "dup", "type": "long", "nullable": False},
        {"name": "dup", "type": "string", "nullable": True},
    ]
    return payload


def _invalid_unknown_layout_field(table_name: str) -> Payload:
    payload = _minimal(table_name)
    payload["not_a_real_field"] = {"unexpected": True}
    return payload


def _invalid_auto_increment(table_name: str) -> Payload:
    payload = _minimal(table_name)
    # auto-increment is only valid on an integral, non-nullable column.
    payload["columns"] = [
        {
            "name": "flag",
            "type": "boolean",
            "nullable": True,
            "autoIncrement": True,
        }
    ]
    return payload


# Documented invalid-request use cases exercised by errors.feature.
_INVALID_BUILDERS: dict[str, Callable[[str], Payload]] = {
    "blank_table_name": _invalid_blank_table_name,
    "missing_columns": _invalid_missing_columns,
    "duplicate_column_names": _invalid_duplicate_column_names,
    "unknown_layout_field": _invalid_unknown_layout_field,
    "invalid_auto_increment": _invalid_auto_increment,
}


def build_create_payload(use_case: str, table_name: str) -> Payload:
    """Builds a documented positive create-table payload.

    Raises :class:`PendingUseCase` for a documented use case that is not yet
    modeled so the caller can skip rather than fail.
    """
    builder = _CREATE_BUILDERS.get(use_case)
    if builder is None:
        raise PendingUseCase(
            f"create-table use case '{use_case}' has no payload builder yet"
        )
    return builder(table_name)


def build_invalid_payload(use_case: str, table_name: str) -> Payload:
    """Builds a documented invalid create-table payload.

    Raises :class:`PendingUseCase` for an invalid use case that is not yet
    modeled so the caller can skip rather than fail.
    """
    builder = _INVALID_BUILDERS.get(use_case)
    if builder is None:
        raise PendingUseCase(
            f"invalid create-table use case '{use_case}' has no payload builder yet"
        )
    return builder(table_name)
