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

"""Transport-neutral V1 CRUD request and public-error helpers for endpoint tests.

The helpers model the intended route-versioned public API, rather than a
legacy Gravitino request DTO or an SDK implementation detail.  They are safe
to use in unit tests: no network client is imported or created here.  Contract
tests can pass a :class:`V1Request` directly to a raw HTTP transport or compare
it with a generated client's serialized request once that operation exists.
"""

from __future__ import annotations

from collections.abc import Mapping, Sequence
from copy import deepcopy
from dataclasses import dataclass
from typing import Any, TypeAlias
from urllib.parse import quote


JsonValue: TypeAlias = (
    str | int | float | bool | None | list["JsonValue"] | dict[str, "JsonValue"]
)
JsonObject: TypeAlias = dict[str, JsonValue]

API_PREFIX = "/api/v1"
JSON_MEDIA_TYPE = "application/json"


@dataclass(frozen=True)
class V1Request:
    """An HTTP request serialized against the target V1 public contract."""

    method: str
    path: str
    headers: Mapping[str, str]
    json_body: JsonObject | None = None

    def body(self) -> JsonObject | None:
        """Returns a safe copy of the JSON request body, if this request has one."""
        return deepcopy(self.json_body)


@dataclass(frozen=True)
class V1ErrorExpectation:
    """A stable V1 public error expected by an endpoint contract test."""

    status: int
    error_type: str
    retryable: bool
    detail_kind: str | None = None

    def __post_init__(self) -> None:
        """Validates that this expectation denotes an HTTP error response."""
        if not 400 <= self.status <= 599:
            raise ValueError("status must be an HTTP error status.")
        if not self.error_type:
            raise ValueError("error_type must not be empty.")


TABLE_ALREADY_EXISTS = V1ErrorExpectation(
    status=409,
    error_type="TABLE_ALREADY_EXISTS",
    retryable=False,
    detail_kind="RESOURCE_INFO",
)

PRECONDITION_FAILED = V1ErrorExpectation(
    status=412,
    error_type="PRECONDITION_FAILED",
    retryable=False,
    detail_kind="RESOURCE_INFO",
)


def _identifier(value: str, name: str) -> str:
    """Validates a public identifier before it is placed in a URL or body."""
    if not isinstance(value, str) or not value or value != value.strip():
        raise ValueError(f"{name} must be a non-empty identifier without surrounding whitespace.")
    return value


def _path_segment(value: str, name: str) -> str:
    """Escapes one public identifier as a single V1 URL path segment."""
    return quote(_identifier(value, name), safe="")


def _properties(properties: Mapping[str, str] | None) -> dict[str, str]:
    """Copies strict string-to-string public resource properties."""
    copied: dict[str, str] = {}
    for key, value in (properties or {}).items():
        copied[_identifier(key, "property key")] = _identifier(value, "property value")
    return copied


def _object(value: Mapping[str, Any], name: str) -> JsonObject:
    """Copies a JSON object supplied as a typed V1 sub-resource."""
    if not isinstance(value, Mapping):
        raise TypeError(f"{name} must be a JSON object.")
    return deepcopy(dict(value))


def _objects(values: Sequence[Mapping[str, Any]], name: str) -> list[JsonObject]:
    """Copies an ordered collection of typed V1 sub-resource objects."""
    return [_object(value, f"{name}[{index}]") for index, value in enumerate(values)]


def _required_properties(properties: Mapping[str, str]) -> dict[str, str]:
    """Copies a required V1 properties object without conflating it with absent."""
    if not isinstance(properties, Mapping):
        raise TypeError("properties must be a JSON object.")
    return _properties(properties)


def _strong_etag(value: str) -> str:
    """Validates the single strong entity tag accepted by V1 mutations."""
    if (
        not isinstance(value, str)
        or len(value) < 3
        or not value.startswith('"')
        or not value.endswith('"')
        or '"' in value[1:-1]
        or "\\" in value[1:-1]
        or "\r" in value
        or "\n" in value
    ):
        raise ValueError("if_match must be one strong quoted ETag such as '\"table-v1\"'.")
    return value


def _headers(
    *, request_id: str | None, if_match: str | None = None, has_body: bool
) -> dict[str, str]:
    """Returns the portable JSON headers required by the route-versioned API."""
    headers = {"Accept": JSON_MEDIA_TYPE}
    if has_body:
        headers["Content-Type"] = JSON_MEDIA_TYPE
    if if_match is not None:
        headers["If-Match"] = _strong_etag(if_match)
    if request_id is not None:
        headers["X-Request-Id"] = _identifier(request_id, "request_id")
    return headers


def _request(
    method: str,
    path: str,
    *,
    request_id: str | None = None,
    if_match: str | None = None,
    json_body: JsonObject | None = None,
) -> V1Request:
    """Builds a target V1 request with standard JSON media negotiation."""
    return V1Request(
        method=method,
        path=path,
        headers=_headers(
            request_id=request_id,
            if_match=if_match,
            has_body=json_body is not None,
        ),
        json_body=deepcopy(json_body),
    )


def metalakes_path() -> str:
    """Returns the V1 metalake collection route."""
    return f"{API_PREFIX}/metalakes"


def metalake_path(metalake: str) -> str:
    """Returns the V1 route for one metalake."""
    return f"{metalakes_path()}/{_path_segment(metalake, 'metalake')}"


def catalogs_path(metalake: str) -> str:
    """Returns the V1 catalog collection route for a metalake."""
    return f"{metalake_path(metalake)}/catalogs"


def catalog_path(metalake: str, catalog: str) -> str:
    """Returns the V1 route for one catalog."""
    return f"{catalogs_path(metalake)}/{_path_segment(catalog, 'catalog')}"


def schemas_path(metalake: str, catalog: str) -> str:
    """Returns the V1 schema collection route for a catalog."""
    return f"{catalog_path(metalake, catalog)}/schemas"


def schema_path(metalake: str, catalog: str, schema: str) -> str:
    """Returns the V1 route for one schema."""
    return f"{schemas_path(metalake, catalog)}/{_path_segment(schema, 'schema')}"


def tables_path(metalake: str, catalog: str, schema: str) -> str:
    """Returns the V1 table collection route for a schema."""
    return f"{schema_path(metalake, catalog, schema)}/tables"


def table_path(metalake: str, catalog: str, schema: str, table: str) -> str:
    """Returns the V1 route for one relational table."""
    return f"{tables_path(metalake, catalog, schema)}/{_path_segment(table, 'table')}"


def metalake_create_body(
    name: str,
    *,
    comment: str | None = None,
    properties: Mapping[str, str] | None = None,
) -> JsonObject:
    """Builds a strict V1 metalake-create request body."""
    body: JsonObject = {"name": _identifier(name, "name"), "properties": _properties(properties)}
    if comment is not None:
        body["comment"] = _identifier(comment, "comment")
    return body


def catalog_create_body(
    name: str,
    catalog_type: str,
    *,
    provider: str | None = None,
    comment: str | None = None,
    properties: Mapping[str, str] | None = None,
) -> JsonObject:
    """Builds a strict V1 catalog-create request body."""
    catalog_type = _identifier(catalog_type, "catalog_type").upper()
    if catalog_type not in {"RELATIONAL", "FILESET", "MESSAGING", "MODEL"}:
        raise ValueError("catalog_type must be RELATIONAL, FILESET, MESSAGING, or MODEL.")
    if provider is None and catalog_type in {"RELATIONAL", "MESSAGING"}:
        raise ValueError(f"provider is required for {catalog_type} catalogs.")
    body: JsonObject = {
        "name": _identifier(name, "name"),
        "type": catalog_type,
        "properties": _properties(properties),
    }
    if provider is not None:
        body["provider"] = _identifier(provider, "provider")
    if comment is not None:
        body["comment"] = _identifier(comment, "comment")
    return body


def schema_create_body(
    name: str,
    *,
    comment: str | None = None,
    properties: Mapping[str, str] | None = None,
) -> JsonObject:
    """Builds a strict V1 schema-create request body."""
    body: JsonObject = {"name": _identifier(name, "name"), "properties": _properties(properties)}
    if comment is not None:
        body["comment"] = _identifier(comment, "comment")
    return body


def table_create_body(
    name: str,
    columns: Sequence[Mapping[str, Any]],
    *,
    comment: str | None = None,
    properties: Mapping[str, str] | None = None,
    partitioning: Sequence[Mapping[str, Any]] | None = None,
    distribution: Mapping[str, Any] | None = None,
    sort_orders: Sequence[Mapping[str, Any]] | None = None,
    indexes: Sequence[Mapping[str, Any]] | None = None,
) -> JsonObject:
    """Builds the V1 create-table body without response-only resource fields.

    An empty ``columns`` array is valid for a connector-owned schema discovery
    flow such as registration; the array itself is never omitted.
    """
    body: JsonObject = {
        "name": _identifier(name, "name"),
        "columns": _objects(columns, "columns"),
        "properties": _properties(properties),
        "partitioning": _objects(partitioning or [], "partitioning"),
        "sortOrders": _objects(sort_orders or [], "sort_orders"),
        "indexes": _objects(indexes or [], "indexes"),
    }
    if comment is not None:
        body["comment"] = _identifier(comment, "comment")
    if distribution is not None:
        body["distribution"] = _object(distribution, "distribution")
    return body


def parent_update_body(
    *, comment: str | None, properties: Mapping[str, str]
) -> JsonObject:
    """Builds the complete mutable desired state for a V1 parent resource.

    The path, rather than this body, identifies the resource. Catalog type and
    provider are immutable creation-time configuration and are deliberately
    absent from this common parent representation.
    """
    if comment is not None:
        _identifier(comment, "comment")
    return {"comment": comment, "properties": _required_properties(properties)}


def table_update_body(
    *,
    comment: str | None,
    columns: Sequence[Mapping[str, Any]],
    properties: Mapping[str, str],
    partitioning: Sequence[Mapping[str, Any]],
    sort_orders: Sequence[Mapping[str, Any]],
    indexes: Sequence[Mapping[str, Any]],
    distribution: Mapping[str, Any] | None = None,
) -> JsonObject:
    """Builds a complete V1 table mutable desired-state representation.

    The immutable table name belongs in the route. ``comment`` is always
    present, where JSON null explicitly clears it; every mutable collection or
    map is also present, including when empty. ``distribution`` is the one
    optional mutable member and is omitted when no distribution is desired.
    """
    if comment is not None:
        _identifier(comment, "comment")
    body: JsonObject = {
        "comment": comment,
        "columns": _objects(columns, "columns"),
        "properties": _required_properties(properties),
        "partitioning": _objects(partitioning, "partitioning"),
        "sortOrders": _objects(sort_orders, "sort_orders"),
        "indexes": _objects(indexes, "indexes"),
    }
    if distribution is not None:
        body["distribution"] = _object(distribution, "distribution")
    return body


def create_metalake(
    name: str,
    *,
    comment: str | None = None,
    properties: Mapping[str, str] | None = None,
    request_id: str | None = None,
) -> V1Request:
    """Serializes V1 metalake creation."""
    return _request(
        "POST",
        metalakes_path(),
        request_id=request_id,
        json_body=metalake_create_body(name, comment=comment, properties=properties),
    )


def get_metalake(name: str, *, request_id: str | None = None) -> V1Request:
    """Serializes V1 metalake retrieval."""
    return _request("GET", metalake_path(name), request_id=request_id)


def list_metalakes(*, request_id: str | None = None) -> V1Request:
    """Serializes V1 metalake listing."""
    return _request("GET", metalakes_path(), request_id=request_id)


def update_metalake(
    name: str,
    *,
    comment: str | None,
    properties: Mapping[str, str],
    if_match: str,
    request_id: str | None = None,
) -> V1Request:
    """Serializes a complete conditional V1 metalake replacement."""
    return _request(
        "PUT",
        metalake_path(name),
        request_id=request_id,
        if_match=if_match,
        json_body=parent_update_body(comment=comment, properties=properties),
    )


def delete_metalake(
    name: str, *, if_match: str, request_id: str | None = None
) -> V1Request:
    """Serializes a conditional V1 metalake deletion."""
    return _request("DELETE", metalake_path(name), request_id=request_id, if_match=if_match)


def create_catalog(
    metalake: str,
    name: str,
    catalog_type: str,
    *,
    provider: str | None = None,
    comment: str | None = None,
    properties: Mapping[str, str] | None = None,
    request_id: str | None = None,
) -> V1Request:
    """Serializes V1 catalog creation under a metalake."""
    return _request(
        "POST",
        catalogs_path(metalake),
        request_id=request_id,
        json_body=catalog_create_body(
            name,
            catalog_type,
            provider=provider,
            comment=comment,
            properties=properties,
        ),
    )


def get_catalog(metalake: str, catalog: str, *, request_id: str | None = None) -> V1Request:
    """Serializes V1 catalog retrieval."""
    return _request("GET", catalog_path(metalake, catalog), request_id=request_id)


def list_catalogs(metalake: str, *, request_id: str | None = None) -> V1Request:
    """Serializes V1 catalog listing."""
    return _request("GET", catalogs_path(metalake), request_id=request_id)


def update_catalog(
    metalake: str,
    catalog: str,
    *,
    comment: str | None,
    properties: Mapping[str, str],
    if_match: str,
    request_id: str | None = None,
) -> V1Request:
    """Serializes a complete conditional V1 catalog replacement."""
    return _request(
        "PUT",
        catalog_path(metalake, catalog),
        request_id=request_id,
        if_match=if_match,
        json_body=parent_update_body(comment=comment, properties=properties),
    )


def delete_catalog(
    metalake: str, catalog: str, *, if_match: str, request_id: str | None = None
) -> V1Request:
    """Serializes a conditional V1 catalog deletion."""
    return _request(
        "DELETE", catalog_path(metalake, catalog), request_id=request_id, if_match=if_match
    )


def create_schema(
    metalake: str,
    catalog: str,
    name: str,
    *,
    comment: str | None = None,
    properties: Mapping[str, str] | None = None,
    request_id: str | None = None,
) -> V1Request:
    """Serializes V1 schema creation under a catalog."""
    return _request(
        "POST",
        schemas_path(metalake, catalog),
        request_id=request_id,
        json_body=schema_create_body(name, comment=comment, properties=properties),
    )


def get_schema(
    metalake: str, catalog: str, schema: str, *, request_id: str | None = None
) -> V1Request:
    """Serializes V1 schema retrieval."""
    return _request("GET", schema_path(metalake, catalog, schema), request_id=request_id)


def list_schemas(metalake: str, catalog: str, *, request_id: str | None = None) -> V1Request:
    """Serializes V1 schema listing."""
    return _request("GET", schemas_path(metalake, catalog), request_id=request_id)


def update_schema(
    metalake: str,
    catalog: str,
    schema: str,
    *,
    comment: str | None,
    properties: Mapping[str, str],
    if_match: str,
    request_id: str | None = None,
) -> V1Request:
    """Serializes a complete conditional V1 schema replacement."""
    return _request(
        "PUT",
        schema_path(metalake, catalog, schema),
        request_id=request_id,
        if_match=if_match,
        json_body=parent_update_body(comment=comment, properties=properties),
    )


def delete_schema(
    metalake: str,
    catalog: str,
    schema: str,
    *,
    if_match: str,
    request_id: str | None = None,
) -> V1Request:
    """Serializes a conditional V1 schema deletion."""
    return _request(
        "DELETE",
        schema_path(metalake, catalog, schema),
        request_id=request_id,
        if_match=if_match,
    )


def create_table(
    metalake: str,
    catalog: str,
    schema: str,
    name: str,
    columns: Sequence[Mapping[str, Any]],
    *,
    comment: str | None = None,
    properties: Mapping[str, str] | None = None,
    partitioning: Sequence[Mapping[str, Any]] | None = None,
    distribution: Mapping[str, Any] | None = None,
    sort_orders: Sequence[Mapping[str, Any]] | None = None,
    indexes: Sequence[Mapping[str, Any]] | None = None,
    request_id: str | None = None,
) -> V1Request:
    """Serializes V1 relational-table creation under a schema."""
    return _request(
        "POST",
        tables_path(metalake, catalog, schema),
        request_id=request_id,
        json_body=table_create_body(
            name,
            columns,
            comment=comment,
            properties=properties,
            partitioning=partitioning,
            distribution=distribution,
            sort_orders=sort_orders,
            indexes=indexes,
        ),
    )


def get_table(
    metalake: str,
    catalog: str,
    schema: str,
    table: str,
    *,
    request_id: str | None = None,
) -> V1Request:
    """Serializes V1 table retrieval."""
    return _request("GET", table_path(metalake, catalog, schema, table), request_id=request_id)


def list_tables(
    metalake: str, catalog: str, schema: str, *, request_id: str | None = None
) -> V1Request:
    """Serializes V1 table listing."""
    return _request("GET", tables_path(metalake, catalog, schema), request_id=request_id)


def update_table(
    metalake: str,
    catalog: str,
    schema: str,
    table: str,
    *,
    comment: str | None,
    columns: Sequence[Mapping[str, Any]],
    properties: Mapping[str, str],
    partitioning: Sequence[Mapping[str, Any]],
    sort_orders: Sequence[Mapping[str, Any]],
    indexes: Sequence[Mapping[str, Any]],
    distribution: Mapping[str, Any] | None = None,
    if_match: str,
    request_id: str | None = None,
) -> V1Request:
    """Serializes a complete conditional V1 table replacement."""
    return _request(
        "PUT",
        table_path(metalake, catalog, schema, table),
        request_id=request_id,
        if_match=if_match,
        json_body=table_update_body(
            comment=comment,
            columns=columns,
            properties=properties,
            partitioning=partitioning,
            distribution=distribution,
            sort_orders=sort_orders,
            indexes=indexes,
        ),
    )


def delete_table(
    metalake: str,
    catalog: str,
    schema: str,
    table: str,
    *,
    if_match: str,
    request_id: str | None = None,
) -> V1Request:
    """Serializes a conditional V1 table deletion."""
    return _request(
        "DELETE",
        table_path(metalake, catalog, schema, table),
        request_id=request_id,
        if_match=if_match,
    )


def assert_v1_error_payload(
    payload: Mapping[str, Any],
    expectation: V1ErrorExpectation,
    *,
    request_id: str | None = None,
    resource_type: str | None = None,
    resource_name: str | None = None,
) -> Mapping[str, Any]:
    """Asserts a raw V1 public-error envelope without parsing diagnostic text."""
    error = payload.get("error")
    assert isinstance(error, Mapping), "V1 error response must contain an error object."
    assert error.get("code") == expectation.status
    assert error.get("type") == expectation.error_type
    assert isinstance(error.get("message"), str) and error["message"]
    assert error.get("retryable") is expectation.retryable
    if request_id is not None:
        assert error.get("requestId") == request_id
    if expectation.detail_kind is not None:
        details = error.get("details")
        assert isinstance(details, list) and details
        detail = details[0]
        assert isinstance(detail, Mapping)
        assert detail.get("kind") == expectation.detail_kind
        if resource_type is not None:
            assert detail.get("resourceType") == resource_type
        if resource_name is not None:
            assert detail.get("resourceName") == resource_name
    return error


def assert_table_already_exists_error(
    payload: Mapping[str, Any],
    *,
    request_id: str | None,
    metalake: str,
    catalog: str,
    schema: str,
    table: str,
) -> Mapping[str, Any]:
    """Asserts the exact non-retryable V1 duplicate-table conflict contract."""
    return assert_v1_error_payload(
        payload,
        TABLE_ALREADY_EXISTS,
        request_id=request_id,
        resource_type="TABLE",
        resource_name=(
            f"metalakes/{metalake}/catalogs/{catalog}/schemas/{schema}/tables/{table}"
        ),
    )


def assert_precondition_failed_error(
    payload: Mapping[str, Any],
    *,
    request_id: str | None,
    resource_type: str,
    resource_name: str,
) -> Mapping[str, Any]:
    """Asserts the exact non-retryable V1 stale-state error contract."""
    return assert_v1_error_payload(
        payload,
        PRECONDITION_FAILED,
        request_id=request_id,
        resource_type=resource_type,
        resource_name=resource_name,
    )
