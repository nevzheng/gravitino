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

"""No-server coverage for target V1 hierarchy path and lifecycle helpers."""

from typing import Any

import pytest

from cuj.steps.v1_hierarchy import (
    V1HierarchyLifecycle,
    V1HierarchyNames,
    V1HierarchyProvisioningConfig,
    _assert_v1_hierarchy_route_handled,
    _is_success_or_not_found,
    _strong_etag,
    assert_v1_precondition_failed,
    assert_v1_hierarchy_stale_precondition,
    record_v1_unsupported_operation,
    v1_hierarchy_config_from_environment,
)


class FakeResponse:
    """A minimal response fake for API-only hierarchy tests."""

    def __init__(
        self,
        status: int,
        payload: object,
        headers: dict[str, str] | None = None,
    ) -> None:
        """Creates a response with a status code and decoded JSON payload."""
        self.status = status
        self._payload = payload
        self.headers = {} if headers is None else dict(headers)

    def json(self) -> object:
        """Returns the configured decoded JSON payload."""
        return self._payload


class FakeRequestContext:
    """Records V1-only lifecycle requests and returns resource-shaped responses."""

    def __init__(self, names: V1HierarchyNames) -> None:
        """Initializes an empty call log for the supplied hierarchy names."""
        self.names = names
        self.calls: list[tuple[str, str, object | None, dict[str, str]]] = []
        self.comments: dict[str, str | None] = {}
        self.properties: dict[str, dict[str, str]] = {}
        self.deleted: set[str] = set()
        self.etags = {
            names.metalake_path: '"metalake-etag"',
            names.catalog_path: '"catalog-etag"',
            names.schema_path: '"schema-etag"',
        }

    def post(self, path: str, *, data: object, headers: dict[str, str]) -> FakeResponse:
        """Records a create request and returns a created response."""
        self.calls.append(("POST", path, data, headers))
        assert isinstance(data, dict)
        item_paths = {
            self.names.metalakes_path: self.names.metalake_path,
            self.names.catalogs_path: self.names.catalog_path,
            self.names.schemas_path: self.names.schema_path,
        }
        item_path = item_paths[path]
        self.comments[item_path] = data.get("comment")  # type: ignore[assignment]
        properties = data.get("properties")
        assert isinstance(properties, dict)
        self.properties[item_path] = properties
        return FakeResponse(201, data)

    def get(self, path: str, *, headers: dict[str, str]) -> FakeResponse:
        """Records a get/list request and returns the appropriate named representation."""
        self.calls.append(("GET", path, None, headers))
        item_names = {
            self.names.metalake_path: self.names.metalake,
            self.names.catalog_path: self.names.catalog,
            self.names.schema_path: self.names.var_schema,
        }
        if path in item_names:
            if path in self.deleted:
                error_types = {
                    self.names.metalake_path: "METALAKE_NOT_FOUND",
                    self.names.catalog_path: "CATALOG_NOT_FOUND",
                    self.names.schema_path: "SCHEMA_NOT_FOUND",
                }
                return FakeResponse(
                    404, {"error": {"type": error_types[path], "retryable": False}}
                )
            payload: dict[str, object] = {"name": item_names[path]}
            payload["comment"] = self.comments[path]
            payload["properties"] = self.properties[path]
            return FakeResponse(200, payload, {"ETag": self.etags[path]})
        collection_names = {
            self.names.metalakes_path: self.names.metalake,
            self.names.catalogs_path: self.names.catalog,
            self.names.schemas_path: self.names.var_schema,
        }
        return FakeResponse(200, {"items": [{"name": collection_names[path]}]})

    def put(self, path: str, *, data: object, headers: dict[str, str]) -> FakeResponse:
        """Records a full replacement request and returns a successful response."""
        self.calls.append(("PUT", path, data, headers))
        assert isinstance(data, dict)
        if headers.get("If-Match") != self.etags[path]:
            return FakeResponse(
                412,
                {"error": {"type": "PRECONDITION_FAILED", "retryable": False}},
            )
        assert set(data) == {"comment", "properties"}
        assert data["comment"] is None or isinstance(data["comment"], str)
        assert isinstance(data["properties"], dict)
        if path == self.names.schema_path and data["comment"] != self.comments[path]:
            return FakeResponse(
                501,
                {"error": {"type": "UNSUPPORTED_OPERATION", "retryable": False}},
            )
        self.comments[path] = data["comment"]
        self.properties[path] = data["properties"]
        return FakeResponse(200, data, {"ETag": self.etags[path]})

    def delete(self, path: str, *, headers: dict[str, str]) -> FakeResponse:
        """Records a delete request and returns a no-content response."""
        self.calls.append(("DELETE", path, None, headers))
        if headers.get("If-Match") != self.etags[path]:
            return FakeResponse(
                412,
                {"error": {"type": "PRECONDITION_FAILED", "retryable": False}},
            )
        self.deleted.add(path)
        return FakeResponse(204, None)


@pytest.mark.cuj
@pytest.mark.api
def test_hierarchy_lifecycle_uses_only_route_versioned_rest_paths() -> None:
    """Create, read, replace, and delete stay on the intended V1 hierarchy paths."""
    names = V1HierarchyNames("lake", "catalog", "schema")
    context = FakeRequestContext(names)
    lifecycle = V1HierarchyLifecycle(
        context,  # type: ignore[arg-type]
        V1HierarchyProvisioningConfig(
            catalog_create={"type": "RELATIONAL", "provider": "test", "properties": {}}
        ),
        names=names,
        request_id="hierarchy-unit-request",
    )

    assert lifecycle.provision() == names
    lifecycle.verify_get_and_list()
    lifecycle.update_metadata()
    lifecycle.verify_updated_metadata()
    lifecycle.delete()

    assert context.calls[0] == (
        "POST",
        "/api/v1/metalakes",
        {
            "name": "lake",
            "comment": "owned by the V1 hierarchy CUJ",
            "properties": {"cuj.owner": "openapi"},
        },
        {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-Request-Id": "hierarchy-unit-request",
        },
    )
    assert context.calls[1][0:3] == (
        "POST",
        "/api/v1/metalakes/lake/catalogs",
        {"name": "catalog", "type": "RELATIONAL", "provider": "test", "properties": {}},
    )
    assert context.calls[2][0:2] == (
        "POST",
        "/api/v1/metalakes/lake/catalogs/catalog/schemas",
    )
    assert all(path.startswith("/api/v1/") for _, path, _, _ in context.calls)
    assert all("/api/metalakes" not in path for _, path, _, _ in context.calls)
    assert [method for method, _, _, _ in context.calls if method == "PUT"] == [
        "PUT",
        "PUT",
        "PUT",
    ]
    update_bodies = [data for method, _, data, _ in context.calls if method == "PUT"]
    assert update_bodies == [
        {
            "comment": "updated by the V1 hierarchy CUJ",
            "properties": {"cuj.owner": "openapi", "cuj.lifecycle": "updated"},
        },
        {
            "comment": "updated by the V1 hierarchy CUJ",
            "properties": {"cuj.lifecycle": "updated"},
        },
        {
            "comment": "owned by the V1 hierarchy CUJ",
            "properties": {"cuj.owner": "openapi", "cuj.lifecycle": "updated"},
        },
    ]
    put_headers = [headers for method, _, _, headers in context.calls if method == "PUT"]
    assert put_headers == [
        {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-Request-Id": "hierarchy-unit-request",
            "If-Match": '"metalake-etag"',
        },
        {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-Request-Id": "hierarchy-unit-request",
            "If-Match": '"catalog-etag"',
        },
        {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-Request-Id": "hierarchy-unit-request",
            "If-Match": '"schema-etag"',
        },
    ]
    delete_calls = [call for call in context.calls if call[0] == "DELETE"]
    assert [method for method, _, _, _ in delete_calls] == [
        "DELETE",
        "DELETE",
        "DELETE",
    ]
    delete_headers = [headers for _, _, _, headers in delete_calls]
    assert delete_headers == [
        {
            "Accept": "application/json",
            "X-Request-Id": "hierarchy-unit-request",
            "If-Match": '"schema-etag"',
        },
        {
            "Accept": "application/json",
            "X-Request-Id": "hierarchy-unit-request",
            "If-Match": '"catalog-etag"',
        },
        {
            "Accept": "application/json",
            "X-Request-Id": "hierarchy-unit-request",
            "If-Match": '"metalake-etag"',
        },
    ]
    lifecycle.verify_deleted()


@pytest.mark.cuj
def test_hierarchy_paths_quote_each_route_segment_and_adapt_to_table_cuj_location() -> None:
    """Hierarchy names are safe route segments and compose with table journey locations."""
    names = V1HierarchyNames("lake space", "catalog/slash", "schema space")

    assert names.metalake_path == "/api/v1/metalakes/lake%20space"
    assert names.catalog_path == "/api/v1/metalakes/lake%20space/catalogs/catalog%2Fslash"
    assert (
        names.schema_path
        == "/api/v1/metalakes/lake%20space/catalogs/catalog%2Fslash/schemas/schema%20space"
    )
    table_location = names.to_table_location()
    assert (table_location.metalake, table_location.catalog, table_location.var_schema) == (
        "lake space",
        "catalog/slash",
        "schema space",
    )


@pytest.mark.cuj
def test_missing_v1_hierarchy_route_is_a_failure_not_a_legacy_fallback() -> None:
    """A target V1 404 route response reports the migration gap immediately."""
    response = FakeResponse(
        404,
        {
            "error": {
                "type": "ROUTE_NOT_FOUND",
                "retryable": False,
            }
        },
    )

    with pytest.raises(AssertionError, match="will not fall back to legacy /api routes"):
        _assert_v1_hierarchy_route_handled(response, "/api/v1/metalakes")


@pytest.mark.cuj
def test_hierarchy_cleanup_tolerates_only_a_real_resource_not_found_error() -> None:
    """Cleanup cannot accidentally mask a missing V1 route as a deleted resource."""
    missing_resource = FakeResponse(
        404,
        {"error": {"type": "SCHEMA_NOT_FOUND", "retryable": False}},
    )
    missing_route = FakeResponse(
        404,
        {"error": {"type": "ROUTE_NOT_FOUND", "retryable": False}},
    )

    assert _is_success_or_not_found(missing_resource)
    assert not _is_success_or_not_found(missing_route)


@pytest.mark.cuj
def test_hierarchy_stale_etag_uses_the_strict_public_precondition_error() -> None:
    """A lost update is a normal V1 412, not an implementation-specific conflict."""
    stale_response = FakeResponse(
        412,
        {"error": {"type": "PRECONDITION_FAILED", "retryable": False}},
    )

    assert_v1_hierarchy_stale_precondition(stale_response, "/api/v1/metalakes/lake")


@pytest.mark.cuj
def test_parent_mutations_assert_missing_and_stale_preconditions() -> None:
    """A full parent PUT always makes missing and stale validators visible as strict 412s."""
    names = V1HierarchyNames("lake", "catalog", "schema")
    lifecycle = V1HierarchyLifecycle(
        FakeRequestContext(names),  # type: ignore[arg-type]
        V1HierarchyProvisioningConfig(
            catalog_create={"type": "RELATIONAL", "provider": "test", "properties": {}}
        ),
        names=names,
    )
    lifecycle.provision()

    stale = lifecycle.attempt_parent_precondition_failure("metalake", "stale")
    missing = lifecycle.attempt_parent_precondition_failure("catalog", "missing")

    assert_v1_precondition_failed(stale, names.metalake_path)
    assert_v1_precondition_failed(missing, names.catalog_path)


@pytest.mark.cuj
@pytest.mark.expected_error
def test_schema_comment_capability_gap_is_a_visible_nonblocking_observation() -> None:
    """Schema comment replacement is not falsely counted as a supported parent PUT."""
    names = V1HierarchyNames("lake", "catalog", "schema")
    lifecycle = V1HierarchyLifecycle(
        FakeRequestContext(names),  # type: ignore[arg-type]
        V1HierarchyProvisioningConfig(
            catalog_create={"type": "RELATIONAL", "provider": "test", "properties": {}}
        ),
        names=names,
    )
    lifecycle.provision()

    response = lifecycle.attempt_schema_comment_update()

    with pytest.warns(UserWarning, match="Declared expected API error observed"):
        record_v1_unsupported_operation(response, reason="Schema comment mutation is unavailable.")


@pytest.mark.cuj
def test_hierarchy_mutations_reject_a_weak_or_missing_etag() -> None:
    """PUT and DELETE must be guarded by a strong validator from an item GET."""
    weak_etag_response = FakeResponse(200, {"name": "lake"}, {"ETag": 'W/"weak"'})
    missing_etag_response = FakeResponse(200, {"name": "lake"})

    with pytest.raises(AssertionError, match="strong ETag"):
        _strong_etag(weak_etag_response, "/api/v1/metalakes/lake")
    with pytest.raises(AssertionError, match="ETag header"):
        _strong_etag(missing_etag_response, "/api/v1/metalakes/lake")


@pytest.mark.cuj
def test_hierarchy_config_requires_an_explicit_json_catalog_request(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Live provisioning does not guess connector configuration or use a legacy shape."""
    monkeypatch.delenv("GRAVITINO_OPENAPI_CUJ_CATALOG_CREATE", raising=False)
    assert v1_hierarchy_config_from_environment() is None

    monkeypatch.setenv(
        "GRAVITINO_OPENAPI_CUJ_CATALOG_CREATE",
        '{"type":"RELATIONAL","provider":"test","properties":{}}',
    )
    config = v1_hierarchy_config_from_environment()

    assert config is not None
    assert config.catalog_create["provider"] == "test"

    monkeypatch.setenv("GRAVITINO_OPENAPI_CUJ_CATALOG_CREATE", "not-json")
    with pytest.raises(pytest.UsageError, match="must be a JSON object"):
        v1_hierarchy_config_from_environment()


@pytest.mark.cuj
def test_provider_catalog_configuration_overrides_the_generic_hierarchy_configuration(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A table outline can provision separate V1 parent hierarchies per connector profile."""
    monkeypatch.setenv(
        "GRAVITINO_OPENAPI_CUJ_CATALOG_CREATE",
        '{"type":"RELATIONAL","provider":"generic","properties":{}}',
    )
    monkeypatch.setenv(
        "GRAVITINO_OPENAPI_CUJ_ICEBERG_CATALOG_CREATE",
        '{"type":"RELATIONAL","provider":"iceberg","properties":{}}',
    )

    iceberg = v1_hierarchy_config_from_environment("iceberg")
    hive = v1_hierarchy_config_from_environment("hive")

    assert iceberg is not None
    assert iceberg.catalog_create["provider"] == "iceberg"
    assert hive is not None
    assert hive.catalog_create["provider"] == "generic"
