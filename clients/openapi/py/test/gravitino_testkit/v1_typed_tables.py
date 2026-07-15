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

"""Transport-neutral serializers for the typed V1 table-create direction.

These helpers intentionally model the proposed public table contract rather
than the current legacy-compatible ``properties`` map.  They live beside the
existing CRUD test helpers so endpoint and CUJ tests can make the desired wire
shape explicit while the generated client catches up with the contract.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from copy import deepcopy
from typing import Any

from gravitino_testkit.v1_crud import JSON_MEDIA_TYPE, V1Request, table_path, tables_path


_OPTION_KEYS = frozenset(
    {"icebergOptions", "hiveOptions", "clickhouseOptions", "mysqlOptions"}
)
_STORAGE_KEYS = frozenset(
    {"ownership", "tableFormat", "location", "fileFormat"}
)
_OWNERSHIP_VALUES = frozenset({"MANAGED", "EXTERNAL"})


def typed_table_create_body(
    name: str,
    columns: Sequence[Mapping[str, Any]],
    *,
    comment: str | None = None,
    storage: Mapping[str, Any] | None = None,
    option_blocks: Mapping[str, Mapping[str, Any]] | None = None,
    partitioning: Sequence[Mapping[str, Any]] | None = None,
    distribution: Mapping[str, Any] | None = None,
    sort_orders: Sequence[Mapping[str, Any]] | None = None,
    indexes: Sequence[Mapping[str, Any]] | None = None,
) -> dict[str, Any]:
    """Builds a typed V1 create body without a generic public properties map."""
    body: dict[str, Any] = {
        "name": _identifier(name, "name"),
        "columns": _objects(columns, "columns"),
        "partitioning": _objects(partitioning or (), "partitioning"),
        "sortOrders": _objects(sort_orders or (), "sort_orders"),
        "indexes": _objects(indexes or (), "indexes"),
    }
    if comment is not None:
        body["comment"] = _identifier(comment, "comment")
    if storage is not None:
        body["storage"] = _storage(storage)
    if distribution is not None:
        body["distribution"] = _object(distribution, "distribution")
    body.update(_option_blocks(option_blocks))
    return body


def typed_table_update_body(
    *,
    comment: str | None,
    columns: Sequence[Mapping[str, Any]],
    storage: Mapping[str, Any] | None = None,
    option_blocks: Mapping[str, Mapping[str, Any]] | None = None,
    partitioning: Sequence[Mapping[str, Any]],
    distribution: Mapping[str, Any] | None = None,
    sort_orders: Sequence[Mapping[str, Any]],
    indexes: Sequence[Mapping[str, Any]],
) -> dict[str, Any]:
    """Builds a full desired-state V1 table PUT body without raw properties."""
    body = typed_table_create_body(
        "table-name-belongs-in-the-route",
        columns,
        comment=comment,
        storage=storage,
        option_blocks=option_blocks,
        partitioning=partitioning,
        distribution=distribution,
        sort_orders=sort_orders,
        indexes=indexes,
    )
    body.pop("name")
    return body


def create_typed_table(
    metalake: str,
    catalog: str,
    schema: str,
    name: str,
    columns: Sequence[Mapping[str, Any]],
    *,
    comment: str | None = None,
    storage: Mapping[str, Any] | None = None,
    option_blocks: Mapping[str, Mapping[str, Any]] | None = None,
    partitioning: Sequence[Mapping[str, Any]] | None = None,
    distribution: Mapping[str, Any] | None = None,
    sort_orders: Sequence[Mapping[str, Any]] | None = None,
    indexes: Sequence[Mapping[str, Any]] | None = None,
    request_id: str | None = None,
) -> V1Request:
    """Serializes a typed V1 table create request for endpoint-level tests."""
    return _request(
        "POST",
        tables_path(metalake, catalog, schema),
        request_id=request_id,
        body=typed_table_create_body(
            name,
            columns,
            comment=comment,
            storage=storage,
            option_blocks=option_blocks,
            partitioning=partitioning,
            distribution=distribution,
            sort_orders=sort_orders,
            indexes=indexes,
        ),
    )


def update_typed_table(
    metalake: str,
    catalog: str,
    schema: str,
    table: str,
    *,
    comment: str | None,
    columns: Sequence[Mapping[str, Any]],
    storage: Mapping[str, Any] | None = None,
    option_blocks: Mapping[str, Mapping[str, Any]] | None = None,
    partitioning: Sequence[Mapping[str, Any]],
    distribution: Mapping[str, Any] | None = None,
    sort_orders: Sequence[Mapping[str, Any]],
    indexes: Sequence[Mapping[str, Any]],
    if_match: str,
    request_id: str | None = None,
) -> V1Request:
    """Serializes a full typed V1 table PUT using the current strong ETag."""
    return _request(
        "PUT",
        table_path(metalake, catalog, schema, table),
        request_id=request_id,
        if_match=if_match,
        body=typed_table_update_body(
            comment=comment,
            columns=columns,
            storage=storage,
            option_blocks=option_blocks,
            partitioning=partitioning,
            distribution=distribution,
            sort_orders=sort_orders,
            indexes=indexes,
        ),
    )


def typed_option_keys() -> frozenset[str]:
    """Returns the top-level typed option blocks recognized by the V1 POC."""
    return _OPTION_KEYS


def _identifier(value: str, name: str) -> str:
    """Validates a minimal closed-string value before adding it to a wire body."""
    if not isinstance(value, str) or not value or value != value.strip():
        raise ValueError(f"{name} must be a non-empty string without surrounding whitespace.")
    return value


def _objects(values: Sequence[Mapping[str, Any]], name: str) -> list[dict[str, Any]]:
    """Copies a required ordered list of JSON objects."""
    if isinstance(values, (str, bytes)):
        raise TypeError(f"{name} must be an ordered collection of JSON objects.")
    return [_object(value, f"{name}[{index}]") for index, value in enumerate(values)]


def _object(value: Mapping[str, Any], name: str) -> dict[str, Any]:
    """Copies one JSON object without leaking caller mutation into a test request."""
    if not isinstance(value, Mapping):
        raise TypeError(f"{name} must be a JSON object.")
    return deepcopy(dict(value))


def _storage(value: Mapping[str, Any]) -> dict[str, Any]:
    """Validates the closed shared storage envelope used by typed table creates."""
    storage = _object(value, "storage")
    unknown = set(storage).difference(_STORAGE_KEYS)
    if unknown:
        raise ValueError(f"storage has unknown fields: {sorted(unknown)!r}.")
    ownership = storage.get("ownership")
    if ownership is not None and ownership not in _OWNERSHIP_VALUES:
        raise ValueError("storage.ownership must be MANAGED or EXTERNAL.")
    for field in ("tableFormat", "location", "fileFormat"):
        if field in storage:
            storage[field] = _identifier(storage[field], f"storage.{field}")
    return storage


def _option_blocks(
    value: Mapping[str, Mapping[str, Any]] | None,
) -> dict[str, dict[str, Any]]:
    """Copies zero or one closed typed provider-options block for a table request."""
    if value is None:
        return {}
    if not isinstance(value, Mapping):
        raise TypeError("option_blocks must be a JSON object keyed by typed option block.")
    unknown = set(value).difference(_OPTION_KEYS)
    if unknown:
        raise ValueError(f"option_blocks has unknown keys: {sorted(unknown)!r}.")
    if len(value) > 1:
        raise ValueError("V1 table requests allow at most one typed provider-options block.")
    return {key: _object(option, key) for key, option in value.items()}


def _request(
    method: str,
    path: str,
    *,
    request_id: str | None,
    body: dict[str, Any],
    if_match: str | None = None,
) -> V1Request:
    """Builds one JSON request with the route-versioned V1 standard headers."""
    headers = {"Accept": JSON_MEDIA_TYPE, "Content-Type": JSON_MEDIA_TYPE}
    if request_id is not None:
        headers["X-Request-Id"] = _identifier(request_id, "request_id")
    if if_match is not None:
        if not _is_strong_etag(if_match):
            raise ValueError("if_match must be one strong quoted ETag such as '\"table-v1\"'.")
        headers["If-Match"] = if_match
    return V1Request(method=method, path=path, headers=headers, json_body=deepcopy(body))


def _is_strong_etag(value: str) -> bool:
    """Returns whether a value is a single strong quoted entity tag."""
    return (
        isinstance(value, str)
        and len(value) >= 3
        and value.startswith('"')
        and value.endswith('"')
        and '"' not in value[1:-1]
        and "\\" not in value[1:-1]
        and "\r" not in value
        and "\n" not in value
    )
