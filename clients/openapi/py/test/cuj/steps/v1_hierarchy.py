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

"""Composable API-only V1 metalake, catalog, and schema lifecycle helpers.

The helpers describe the intended route-versioned REST surface only.  They do
not call legacy ``/api`` endpoints, generated clients, browsers, or pages.  A
table CUJ can provision a disposable hierarchy through this module and pass the
resulting names to ``new_create_table_journey``.
"""

from __future__ import annotations

from collections.abc import Mapping
from copy import deepcopy
from dataclasses import dataclass, field
from typing import Any, TYPE_CHECKING
from urllib.parse import quote
from uuid import uuid4
import json
import os

import pytest
from pytest_bdd import given, parsers, then, when

from cuj.support.transport import (
    ApiResponse,
    ExpectedApiError,
    assert_expected_api_error,
    observe_api_error,
    record_non_blocking_expected_error,
)

if TYPE_CHECKING:
    from playwright.sync_api import APIRequestContext


_PRECONDITION_FAILED = ExpectedApiError(
    status=412,
    error_type="PRECONDITION_FAILED",
    retryable=False,
)

_UNSUPPORTED_OPERATION = ExpectedApiError(
    status=501,
    error_type="UNSUPPORTED_OPERATION",
    retryable=False,
)


@dataclass
class V1HierarchyScenario:
    """Holds one pytest-bdd parent-resource scenario and its latest response."""

    lifecycle: "V1HierarchyLifecycle"
    response: ApiResponse | None = None


@dataclass
class V1TableScenario:
    """Holds one pytest-bdd table scenario and its latest response."""

    lifecycle: "V1TableLifecycle"
    response: ApiResponse | None = None


@dataclass(frozen=True)
class V1HierarchyNames:
    """The names of an isolated metalake/catalog/schema V1 hierarchy."""

    metalake: str
    catalog: str
    var_schema: str

    @classmethod
    def allocate(cls) -> "V1HierarchyNames":
        """Allocates collision-resistant resource names valid for V1 path segments."""
        suffix = uuid4().hex[:20]
        return cls(
            metalake=f"cuj_metalake_{suffix}",
            catalog=f"cuj_catalog_{suffix}",
            var_schema=f"cuj_schema_{suffix}",
        )

    @property
    def metalakes_path(self) -> str:
        """Returns the V1 metalake collection route."""
        return "/api/v1/metalakes"

    @property
    def metalake_path(self) -> str:
        """Returns the V1 metalake item route."""
        return f"{self.metalakes_path}/{quote(self.metalake, safe='')}"

    @property
    def catalogs_path(self) -> str:
        """Returns the V1 catalog collection route beneath the metalake."""
        return f"{self.metalake_path}/catalogs"

    @property
    def catalog_path(self) -> str:
        """Returns the V1 catalog item route."""
        return f"{self.catalogs_path}/{quote(self.catalog, safe='')}"

    @property
    def schemas_path(self) -> str:
        """Returns the V1 schema collection route beneath the catalog."""
        return f"{self.catalog_path}/schemas"

    @property
    def schema_path(self) -> str:
        """Returns the V1 schema item route."""
        return f"{self.schemas_path}/{quote(self.var_schema, safe='')}"

    def to_table_location(self) -> Any:
        """Builds the table-CUJ location without imposing a dependency at import time."""
        from cuj.steps.create_table import V1CreateTableLocation

        return V1CreateTableLocation(
            metalake=self.metalake,
            catalog=self.catalog,
            var_schema=self.var_schema,
        )


@dataclass(frozen=True)
class V1HierarchyProvisioningConfig:
    """The provider-specific catalog request needed to provision a V1 hierarchy."""

    catalog_create: Mapping[str, Any]
    metalake_properties: Mapping[str, str] = field(default_factory=dict)
    schema_properties: Mapping[str, str] = field(default_factory=dict)

    def __post_init__(self) -> None:
        """Copies caller input so lifecycle tests cannot mutate shared fixture configuration."""
        if not isinstance(self.catalog_create, Mapping):
            raise ValueError("catalog_create must be a JSON object.")
        if not isinstance(self.catalog_create.get("properties"), Mapping):
            raise ValueError("catalog_create.properties must be a JSON object.")
        object.__setattr__(self, "catalog_create", dict(self.catalog_create))
        object.__setattr__(self, "metalake_properties", dict(self.metalake_properties))
        object.__setattr__(self, "schema_properties", dict(self.schema_properties))


@dataclass
class V1HierarchyLifecycle:
    """Drives an isolated V1 hierarchy with standalone Playwright API transport."""

    request_context: APIRequestContext
    config: V1HierarchyProvisioningConfig
    names: V1HierarchyNames = field(default_factory=V1HierarchyNames.allocate)
    request_id: str = field(default_factory=lambda: f"cuj-hierarchy-{uuid4().hex}")
    metalake_created: bool = False
    catalog_created: bool = False
    schema_created: bool = False
    etags: dict[str, str] = field(default_factory=dict)
    updated_bodies: dict[str, dict[str, Any]] = field(default_factory=dict)

    def provision(self) -> V1HierarchyNames:
        """Creates metalake, catalog, and schema in dependency order through V1 routes."""
        _assert_v1_hierarchy_success(
            self.request_context.post(
                self.names.metalakes_path,
                data=self._metalake_create_body(),
                headers=self._json_headers(),
            ),
            self.names.metalakes_path,
        )
        self.metalake_created = True
        _assert_v1_hierarchy_success(
            self.request_context.post(
                self.names.catalogs_path,
                data=self._catalog_create_body(),
                headers=self._json_headers(),
            ),
            self.names.catalogs_path,
        )
        self.catalog_created = True
        _assert_v1_hierarchy_success(
            self.request_context.post(
                self.names.schemas_path,
                data=self._schema_create_body(),
                headers=self._json_headers(),
            ),
            self.names.schemas_path,
        )
        self.schema_created = True
        return self.names

    def verify_get(self) -> None:
        """Verifies each created parent resource is retrievable through its V1 item route."""
        self._assert_item(
            self.request_context.get(self.names.metalake_path, headers=self._read_headers()),
            self.names.metalake_path,
            self.names.metalake,
        )
        self._assert_item(
            self.request_context.get(self.names.catalog_path, headers=self._read_headers()),
            self.names.catalog_path,
            self.names.catalog,
        )
        self._assert_item(
            self.request_context.get(self.names.schema_path, headers=self._read_headers()),
            self.names.schema_path,
            self.names.var_schema,
        )

    def verify_list(self) -> None:
        """Verifies each created parent resource is visible from its V1 collection route."""
        self._assert_list(
            self.request_context.get(self.names.metalakes_path, headers=self._read_headers()),
            self.names.metalakes_path,
            self.names.metalake,
        )
        self._assert_list(
            self.request_context.get(self.names.catalogs_path, headers=self._read_headers()),
            self.names.catalogs_path,
            self.names.catalog,
        )
        self._assert_list(
            self.request_context.get(self.names.schemas_path, headers=self._read_headers()),
            self.names.schemas_path,
            self.names.var_schema,
        )

    def verify_get_and_list(self) -> None:
        """Verifies each created resource is retrievable and visible from its V1 collection."""
        self.verify_get()
        self.verify_list()

    def update_metadata(self) -> None:
        """Replaces all mutable V1 metadata using strong ETags read from GET."""
        schema_current = self._current_parent_update_body(self.names.schema_path)
        for path, body in (
            (
                self.names.metalake_path,
                self._replacement_body(
                    self._metalake_properties(), "updated by the V1 hierarchy CUJ"
                ),
            ),
            (
                self.names.catalog_path,
                self._replacement_body(
                    self._catalog_properties(), "updated by the V1 hierarchy CUJ"
                ),
            ),
            (
                self.names.schema_path,
                self._replacement_body(
                    schema_current["properties"], schema_current["comment"]
                ),
            ),
        ):
            etag = self._etag_for(path)
            assert etag is not None
            # A successful replacement changes the resource version. If the PUT
            # itself fails, cleanup must re-read rather than reuse a stale ETag.
            self.etags.pop(path, None)
            _assert_v1_hierarchy_success(
                self.request_context.put(
                    path,
                    data=body,
                    headers=self._json_headers(if_match=etag),
                ),
                path,
            )
            self.updated_bodies[path] = body

    def attempt_parent_precondition_failure(
        self, resource: str, precondition: str
    ) -> ApiResponse:
        """Submits a valid parent replacement with a deliberately invalid precondition.

        Stale and missing preconditions are normal, strict public API error scenarios. They are
        not migration limitations and callers must assert their public 412 envelope.
        """
        path = self._parent_path(resource)
        body = self._current_parent_update_body(path)
        if precondition == "stale":
            headers = self._json_headers(if_match='"cuj-stale-validator"')
        elif precondition == "missing":
            headers = self._json_headers()
        else:
            raise ValueError(f"Unknown parent precondition mode: {precondition!r}.")
        return self.request_context.put(path, data=body, headers=headers)

    def attempt_schema_comment_update(self) -> ApiResponse:
        """Attempts the documented-but-unimplemented V1 schema-comment replacement.

        The request is a complete desired state with a current strong ETag. It intentionally
        changes only the comment so a 501 can be attributed to the known core capability gap,
        not to an invalid or partial request.
        """
        response = self.request_context.get(self.names.schema_path, headers=self._read_headers())
        _assert_v1_hierarchy_success(response, self.names.schema_path)
        self._remember_etag(response, self.names.schema_path)
        payload = response.json()
        assert isinstance(payload, Mapping), "The V1 schema GET response must be a JSON object."
        properties = payload.get("properties")
        assert isinstance(properties, Mapping), "The V1 schema response must include properties."
        current_comment = payload.get("comment")
        attempted_comment = "V1 schema comment replacement requested by the CUJ"
        if current_comment == attempted_comment:
            attempted_comment += " again"
        return self.request_context.put(
            self.names.schema_path,
            data={"comment": attempted_comment, "properties": dict(properties)},
            headers=self._json_headers(if_match=self.etags[self.names.schema_path]),
        )

    def verify_updated_metadata(self) -> None:
        """Reads each V1 item after PUT and verifies its complete mutable state."""
        for path, expected in self.updated_bodies.items():
            response = self.request_context.get(path, headers=self._read_headers())
            _assert_v1_hierarchy_success(response, path)
            self._remember_etag(response, path)
            payload = response.json()
            assert isinstance(payload, Mapping), f"{path} must return a JSON object."
            assert payload.get("comment") == expected["comment"], (
                f"{path} did not preserve its V1 PUT comment replacement."
            )
            assert payload.get("properties") == expected["properties"], (
                f"{path} did not preserve its V1 PUT property replacement."
            )

    def delete(self) -> None:
        """Deletes schema, catalog, then metalake through V1 item routes."""
        failures: list[str] = []
        for attribute, path, error_type in (
            ("schema_created", self.names.schema_path, "SCHEMA_NOT_FOUND"),
            ("catalog_created", self.names.catalog_path, "CATALOG_NOT_FOUND"),
            ("metalake_created", self.names.metalake_path, "METALAKE_NOT_FOUND"),
        ):
            if not getattr(self, attribute):
                continue
            etag = self._etag_for(path, allow_not_found=True)
            if etag is None:
                setattr(self, attribute, False)
                continue
            response = self.request_context.delete(
                path,
                headers=self._conditional_headers(etag),
            )
            if not _is_success_or_not_found(response):
                failures.append(f"{path}: HTTP {response.status}")
                continue
            setattr(self, attribute, False)
            self._assert_parent_not_found(path, error_type)
        if failures:
            raise AssertionError("V1 hierarchy cleanup failed: " + "; ".join(failures))

    def verify_deleted(self) -> None:
        """Verifies the three hierarchy resources are absent after conditional deletion."""
        assert not self.schema_created, "The V1 schema cleanup did not complete."
        assert not self.catalog_created, "The V1 catalog cleanup did not complete."
        assert not self.metalake_created, "The V1 metalake cleanup did not complete."

    def _assert_parent_not_found(self, path: str, error_type: str) -> None:
        """Confirms one deleted resource is absent before deleting an ancestor resource."""
        response = self.request_context.get(path, headers=self._read_headers())
        assert_expected_api_error(
            response,
            ExpectedApiError(status=404, error_type=error_type, retryable=False),
        )

    def _metalake_create_body(self) -> dict[str, Any]:
        """Builds a minimal closed V1 metalake-create request body."""
        return {
            "name": self.names.metalake,
            "comment": "owned by the V1 hierarchy CUJ",
            "properties": self._metalake_properties(),
        }

    def _catalog_create_body(self) -> dict[str, Any]:
        """Binds the allocated name into the provider-specific catalog request."""
        body = deepcopy(dict(self.config.catalog_create))
        body["name"] = self.names.catalog
        return body

    def _schema_create_body(self) -> dict[str, Any]:
        """Builds a minimal closed V1 schema-create request body."""
        return {
            "name": self.names.var_schema,
            "comment": "owned by the V1 hierarchy CUJ",
            "properties": self._schema_properties(),
        }

    def _metalake_properties(self) -> dict[str, str]:
        """Returns all metalake properties that a replacement must preserve."""
        return {"cuj.owner": "openapi"} | dict(self.config.metalake_properties)

    def _catalog_properties(self) -> dict[str, Any]:
        """Returns all catalog properties that a replacement must preserve."""
        catalog_properties = self.config.catalog_create["properties"]
        assert isinstance(catalog_properties, Mapping)
        return dict(catalog_properties)

    def _schema_properties(self) -> dict[str, str]:
        """Returns all schema properties that a replacement must preserve."""
        return {"cuj.owner": "openapi"} | dict(self.config.schema_properties)

    def _replacement_body(
        self, current_properties: Mapping[str, Any], comment: str | None
    ) -> dict[str, Any]:
        """Builds a full desired-state body for one immutable-path V1 resource."""
        return {
            "comment": comment,
            "properties": dict(current_properties) | {"cuj.lifecycle": "updated"},
        }

    def _parent_path(self, resource: str) -> str:
        """Maps one public parent-resource name to its V1 item path."""
        paths = {
            "metalake": self.names.metalake_path,
            "catalog": self.names.catalog_path,
            "schema": self.names.schema_path,
        }
        try:
            return paths[resource]
        except KeyError as error:
            raise ValueError(f"Unknown V1 parent resource: {resource!r}.") from error

    def _current_parent_update_body(self, path: str) -> dict[str, Any]:
        """Loads one parent and returns its full current desired-state PUT body."""
        response = self.request_context.get(path, headers=self._read_headers())
        _assert_v1_hierarchy_success(response, path)
        payload = response.json()
        assert isinstance(payload, Mapping), f"{path} must return a JSON object."
        properties = payload.get("properties")
        assert isinstance(properties, Mapping), f"{path} must include properties."
        comment = payload.get("comment")
        assert comment is None or isinstance(comment, str), f"{path} returned an invalid comment."
        return {"comment": comment, "properties": dict(properties)}

    def _read_headers(self) -> dict[str, str]:
        """Returns standard headers for a V1 representation read."""
        return {
            "Accept": "application/json",
            "X-Request-Id": self.request_id,
        }

    def _json_headers(self, *, if_match: str | None = None) -> dict[str, str]:
        """Returns JSON mutation headers, optionally conditioned on a strong ETag."""
        headers = self._read_headers() | {"Content-Type": "application/json"}
        if if_match is not None:
            headers["If-Match"] = if_match
        return headers

    def _conditional_headers(self, etag: str) -> dict[str, str]:
        """Returns a conditional mutation header set without an irrelevant body type."""
        return self._read_headers() | {"If-Match": etag}

    def _etag_for(self, path: str, *, allow_not_found: bool = False) -> str | None:
        """Returns a fresh strong ETag from a prior V1 GET for a mutation request."""
        etag = self.etags.get(path)
        if etag is not None:
            return etag
        response = self.request_context.get(path, headers=self._read_headers())
        if allow_not_found and _is_hierarchy_resource_not_found(response):
            return None
        _assert_v1_hierarchy_success(response, path)
        self._remember_etag(response, path)
        return self.etags[path]

    def _remember_etag(self, response: ApiResponse, path: str) -> None:
        """Stores the strong entity tag that conditions the next V1 mutation."""
        self.etags[path] = _strong_etag(response, path)

    def _assert_item(self, response: ApiResponse, path: str, name: str) -> None:
        """Asserts one V1 item route returned the named resource directly."""
        _assert_v1_hierarchy_success(response, path)
        payload = response.json()
        assert isinstance(payload, Mapping), f"{path} must return a JSON object."
        assert payload.get("name") == name, f"{path} did not return resource {name!r}."
        self._remember_etag(response, path)

    def _assert_list(self, response: ApiResponse, path: str, name: str) -> None:
        """Asserts one V1 collection route contains the created resource name."""
        _assert_v1_hierarchy_success(response, path)
        assert _payload_contains_name(response.json(), name), (
            f"{path} did not list resource {name!r}."
        )


def v1_hierarchy_config_from_environment(
    provider: str | None = None,
) -> V1HierarchyProvisioningConfig | None:
    """Reads the explicit provider catalog request used by a live hierarchy CUJ.

    The value is intentionally JSON rather than a legacy route shape.  Each
    connector profile supplies the V1 ``CatalogCreateRequest`` body it needs;
    the helper only binds an isolated catalog name. A provider-specific
    ``GRAVITINO_OPENAPI_CUJ_<PROVIDER>_CATALOG_CREATE`` value takes precedence
    when a table feature needs separate hierarchy setup per connector. The
    generic ``GRAVITINO_OPENAPI_CUJ_CATALOG_CREATE`` remains the fallback for
    a single configured provider.
    """
    variable = "GRAVITINO_OPENAPI_CUJ_CATALOG_CREATE"
    if provider:
        provider_key = provider.upper().replace("-", "_")
        provider_variable = f"GRAVITINO_OPENAPI_CUJ_{provider_key}_CATALOG_CREATE"
        raw_catalog_create = os.environ.get(provider_variable, "").strip()
        if raw_catalog_create:
            variable = provider_variable
        else:
            raw_catalog_create = os.environ.get(variable, "").strip()
    else:
        raw_catalog_create = os.environ.get(variable, "").strip()
    if not raw_catalog_create:
        return None
    try:
        catalog_create = json.loads(raw_catalog_create)
    except json.JSONDecodeError as error:
        raise pytest.UsageError(
            f"{variable} must be a JSON object."
        ) from error
    if not isinstance(catalog_create, Mapping):
        raise pytest.UsageError(
            f"{variable} must decode to a JSON object."
        )
    return V1HierarchyProvisioningConfig(catalog_create=catalog_create)


def provision_v1_hierarchy(
    request_context: APIRequestContext,
    config: V1HierarchyProvisioningConfig,
    *,
    names: V1HierarchyNames | None = None,
) -> V1HierarchyLifecycle:
    """Provisions a disposable V1 hierarchy for another API-only customer journey.

    Callers own the returned lifecycle's ``delete`` call, typically in a pytest
    fixture finalizer.  ``names.to_table_location()`` composes directly with
    the V1 table CUJ's public journey constructor.
    """
    lifecycle = V1HierarchyLifecycle(
        request_context=request_context,
        config=config,
        names=V1HierarchyNames.allocate() if names is None else names,
    )
    lifecycle.provision()
    return lifecycle


@dataclass
class V1TableLifecycle:
    """Drives a table lifecycle below an isolated V1 parent hierarchy.

    ``CreateTableJourney`` remains the source of documented provider payloads. This wrapper adds
    the V1 item lifecycle contract that table creation alone cannot prove: strong-ETag full PUT,
    strict precondition failures, and conditional deletion.
    """

    hierarchy: V1HierarchyLifecycle
    journey: Any
    created: bool = False
    updated_body: dict[str, Any] | None = None

    @property
    def collection_path(self) -> str:
        """Returns the V1 table collection path owned by the configured table journey."""
        return self.journey.collection_path

    @property
    def item_path(self) -> str:
        """Returns the V1 item path for the isolated table."""
        return self.journey.item_path

    def create(self) -> ApiResponse:
        """Creates the provider's documented minimal table through the V1 collection route."""
        if not self.hierarchy.schema_created:
            raise AssertionError("The V1 parent hierarchy must exist before creating a table.")
        response = self.journey.submit_documented_use_case("minimal")
        _assert_v1_table_success(response, self.collection_path)
        assert response.status == 201, (
            f"V1 table creation must return 201, received HTTP {response.status}."
        )
        self.created = True
        return response

    def verify_get(self) -> None:
        """Verifies the created table is returned by its V1 item route with a strong ETag."""
        self._load_resource()

    def verify_list(self) -> None:
        """Verifies the created table appears in its V1 schema collection response."""
        response = self.journey.list_tables()
        _assert_v1_table_success(response, self.collection_path)
        assert self.journey.table_name is not None
        assert _payload_contains_name(response.json(), self.journey.table_name), (
            f"{self.collection_path} did not list table {self.journey.table_name!r}."
        )

    def update_metadata(self) -> ApiResponse:
        """Replaces the full supported table state with a current strong ETag.

        The complete request preserves the current columns and physical layout while changing the
        currently supported comment and properties. That keeps the happy path distinct from the
        documented structural-replacement 501 scenario.
        """
        response, resource = self._load_resource()
        etag = _strong_etag(response, self.item_path)
        body = self._full_desired_state(resource)
        current_comment = body["comment"]
        updated_comment = "updated by the V1 table lifecycle CUJ"
        if current_comment == updated_comment:
            updated_comment += " again"
        body["comment"] = updated_comment
        properties = body["properties"]
        assert isinstance(properties, dict)
        properties["cuj.lifecycle"] = "updated"
        response = self.journey.request_context.put(
            self.item_path,
            data=body,
            headers=self._json_headers(if_match=etag),
        )
        _assert_v1_table_success(response, self.item_path)
        self.updated_body = body
        return response

    def verify_updated_metadata(self) -> None:
        """Verifies that the complete V1 PUT preserved the requested mutable table state."""
        if self.updated_body is None:
            raise AssertionError("A V1 table update must run before checking its result.")
        _, resource = self._load_resource()
        assert resource.get("comment") == self.updated_body["comment"], (
            "The V1 table PUT did not preserve the requested comment."
        )
        assert resource.get("properties") == self.updated_body["properties"], (
            "The V1 table PUT did not preserve the requested full properties state."
        )

    def attempt_precondition_failure(self, precondition: str) -> ApiResponse:
        """Submits a valid full table PUT with a stale or missing precondition."""
        response, resource = self._load_resource()
        _strong_etag(response, self.item_path)
        body = self._full_desired_state(resource)
        if precondition == "stale":
            headers = self._json_headers(if_match='"cuj-stale-validator"')
        elif precondition == "missing":
            headers = self._json_headers()
        else:
            raise ValueError(f"Unknown table precondition mode: {precondition!r}.")
        return self.journey.request_context.put(self.item_path, data=body, headers=headers)

    def attempt_structural_update(self) -> ApiResponse:
        """Attempts a valid full structural replacement that current V1 intentionally rejects."""
        response, resource = self._load_resource()
        etag = _strong_etag(response, self.item_path)
        body = self._full_desired_state(resource)
        columns = body["columns"]
        assert isinstance(columns, list)
        if columns:
            first = columns[0]
            assert isinstance(first, Mapping), "A V1 table column must be a JSON object."
            replacement = dict(first)
            old_comment = replacement.get("comment")
            new_comment = "V1 structural-replacement capability probe"
            if old_comment == new_comment:
                new_comment += " again"
            replacement["comment"] = new_comment
            columns[0] = replacement
        else:
            columns.append(
                {
                    "name": "cuj_structural_replacement_probe",
                    "type": {"kind": "STRING"},
                    "nullable": True,
                }
            )
        return self.journey.request_context.put(
            self.item_path,
            data=body,
            headers=self._json_headers(if_match=etag),
        )

    def delete(self) -> ApiResponse | None:
        """Conditionally deletes the table using a fresh strong ETag, if it still exists."""
        if not self.created:
            return None
        response = self.journey.load_table()
        if _is_table_not_found(response):
            self.created = False
            return None
        _assert_v1_table_success(response, self.item_path)
        etag = _strong_etag(response, self.item_path)
        response = self.journey.request_context.delete(
            self.item_path,
            headers=self._conditional_headers(etag),
        )
        _assert_v1_table_success(response, self.item_path)
        assert response.status == 204, (
            f"V1 table deletion must return 204, received HTTP {response.status}."
        )
        self.created = False
        return response

    def verify_deleted(self) -> None:
        """Verifies the table returns the documented public not-found error after deletion."""
        response = self.journey.load_table()
        assert_expected_api_error(
            response,
            ExpectedApiError(status=404, error_type="TABLE_NOT_FOUND", retryable=False),
        )

    def _load_resource(self) -> tuple[ApiResponse, Mapping[str, Any]]:
        """Loads the V1 table resource and checks its identity and strong ETag contract."""
        response = self.journey.load_table()
        _assert_v1_table_success(response, self.item_path)
        payload = response.json()
        assert isinstance(payload, Mapping), "The V1 table GET response must be a JSON object."
        assert payload.get("name") == self.journey.table_name, (
            f"{self.item_path} did not return its named table resource."
        )
        _strong_etag(response, self.item_path)
        return response, payload

    def _full_desired_state(self, resource: Mapping[str, Any]) -> dict[str, Any]:
        """Builds a full V1 table PUT body from a current public resource representation."""
        columns = resource.get("columns")
        properties = resource.get("properties")
        partitioning = resource.get("partitioning")
        sort_orders = resource.get("sortOrders")
        indexes = resource.get("indexes")
        assert isinstance(columns, list), "A V1 table resource must include columns."
        assert isinstance(properties, Mapping), "A V1 table resource must include properties."
        assert isinstance(partitioning, list), "A V1 table resource must include partitioning."
        assert isinstance(sort_orders, list), "A V1 table resource must include sortOrders."
        assert isinstance(indexes, list), "A V1 table resource must include indexes."
        comment = resource.get("comment")
        assert comment is None or isinstance(comment, str), "A V1 table comment must be a string."
        body: dict[str, Any] = {
            "comment": comment,
            "columns": deepcopy(columns),
            "properties": dict(properties),
            "partitioning": deepcopy(partitioning),
            "sortOrders": deepcopy(sort_orders),
            "indexes": deepcopy(indexes),
        }
        if resource.get("distribution") is not None:
            body["distribution"] = deepcopy(resource["distribution"])
        return body

    def _read_headers(self) -> dict[str, str]:
        """Returns the standard V1 read headers for this table journey."""
        return {
            "Accept": "application/json",
            "X-Request-Id": self.journey.request_id,
        }

    def _json_headers(self, *, if_match: str | None = None) -> dict[str, str]:
        """Returns V1 JSON mutation headers, optionally carrying a strong entity tag."""
        headers = self._read_headers() | {"Content-Type": "application/json"}
        if if_match is not None:
            headers["If-Match"] = if_match
        return headers

    def _conditional_headers(self, etag: str) -> dict[str, str]:
        """Returns V1 conditional DELETE headers without an irrelevant content type."""
        return self._read_headers() | {"If-Match": etag}


def new_v1_table_lifecycle(
    provider: str,
    hierarchy: V1HierarchyLifecycle,
    request_context: APIRequestContext,
) -> V1TableLifecycle:
    """Creates a table-lifecycle wrapper using the documented payload profile for one provider."""
    from cuj.steps.create_table import new_create_table_journey

    return V1TableLifecycle(
        hierarchy=hierarchy,
        journey=new_create_table_journey(
            provider,
            hierarchy.names.to_table_location(),
            request_context,
        ),
    )


def _assert_v1_hierarchy_success(response: ApiResponse, path: str) -> None:
    """Requires a successful V1 hierarchy response and exposes missing routes clearly."""
    _assert_v1_hierarchy_route_handled(response, path)
    assert 200 <= response.status < 300, (
        f"Expected V1 success from {path}, got HTTP {response.status}."
    )


def _assert_v1_hierarchy_route_handled(response: ApiResponse, path: str) -> None:
    """Raises when a target V1 route is absent or selects the wrong HTTP method."""
    observed = observe_api_error(response)
    if observed.status in {404, 405} and observed.error_type in {
        None,
        "ROUTE_NOT_FOUND",
        "METHOD_NOT_ALLOWED",
    }:
        raise AssertionError(
            "The intended V1 hierarchy route is not implemented: "
            f"{path}. Received status/type {(observed.status, observed.error_type)!r}; "
            "the CUJ will not fall back to legacy /api routes."
        )


def _assert_v1_table_success(response: ApiResponse, path: str) -> None:
    """Requires a successful V1 table response and exposes missing routes clearly."""
    _assert_v1_table_route_handled(response, path)
    assert 200 <= response.status < 300, (
        f"Expected V1 table success from {path}, got HTTP {response.status}."
    )


def _assert_v1_table_route_handled(response: ApiResponse, path: str) -> None:
    """Raises when a target V1 table route is absent or rejects its HTTP method."""
    observed = observe_api_error(response)
    if observed.status in {404, 405} and observed.error_type in {
        None,
        "ROUTE_NOT_FOUND",
        "METHOD_NOT_ALLOWED",
    }:
        raise AssertionError(
            "The intended V1 table route is not implemented: "
            f"{path}. Received status/type {(observed.status, observed.error_type)!r}; "
            "the CUJ will not fall back to legacy /api routes."
        )


def assert_v1_hierarchy_stale_precondition(response: ApiResponse, path: str) -> None:
    """Asserts the public error returned when a hierarchy ETag is stale."""
    _assert_v1_hierarchy_route_handled(response, path)
    assert_expected_api_error(response, _PRECONDITION_FAILED)


def assert_v1_precondition_failed(response: ApiResponse, path: str) -> None:
    """Asserts the strict public precondition error for any V1 conditional mutation."""
    _assert_v1_hierarchy_route_handled(response, path)
    assert_expected_api_error(response, _PRECONDITION_FAILED)


def record_v1_unsupported_operation(response: ApiResponse, *, reason: str) -> None:
    """Records a known temporary V1 mutation limitation without hiding the observed outcome."""
    record_non_blocking_expected_error(response, _UNSUPPORTED_OPERATION, reason=reason)


def _is_success_or_not_found(response: ApiResponse) -> bool:
    """Returns whether cleanup completed or its resource was already absent."""
    if 200 <= response.status < 300:
        return True
    return _is_hierarchy_resource_not_found(response)


def _is_hierarchy_resource_not_found(response: ApiResponse) -> bool:
    """Returns whether a 404 names a hierarchy resource rather than a missing route."""
    return response.status == 404 and observe_api_error(response).error_type in {
        "METALAKE_NOT_FOUND",
        "CATALOG_NOT_FOUND",
        "SCHEMA_NOT_FOUND",
    }


def _is_table_not_found(response: ApiResponse) -> bool:
    """Returns whether a V1 table item is absent rather than its route being absent."""
    return response.status == 404 and observe_api_error(response).error_type == "TABLE_NOT_FOUND"


def _strong_etag(response: ApiResponse, path: str) -> str:
    """Extracts the required strong ETag from one V1 item response."""
    headers = getattr(response, "headers", None)
    if callable(headers):
        headers = headers()
    assert isinstance(headers, Mapping), f"{path} must return response headers."
    etag = next(
        (value for name, value in headers.items() if str(name).lower() == "etag"),
        None,
    )
    assert isinstance(etag, str) and etag, f"{path} must return an ETag header."
    assert etag.startswith('"') and etag.endswith('"') and not etag.startswith('W/"'), (
        f"{path} must return a strong ETag, received {etag!r}."
    )
    return etag


def _payload_contains_name(payload: Any, name: str) -> bool:
    """Finds a resource name in direct resource or collection response JSON."""
    if isinstance(payload, Mapping):
        if payload.get("name") == name:
            return True
        return any(_payload_contains_name(value, name) for value in payload.values())
    if isinstance(payload, list):
        return any(_payload_contains_name(value, name) for value in payload)
    return False


@given("a configured V1 parent hierarchy", target_fixture="v1_hierarchy_scenario")
def configured_v1_parent_hierarchy(
    cuj_api_request_context: APIRequestContext,
    request: pytest.FixtureRequest,
) -> V1HierarchyScenario:
    """Allocates an unprovisioned V1 parent hierarchy and registers conditional cleanup."""
    config = v1_hierarchy_config_from_environment()
    if config is None:
        pytest.skip(
            "Set GRAVITINO_OPENAPI_CUJ_CATALOG_CREATE to a V1 catalog-create JSON object "
            "before running parent hierarchy CUJs."
        )
    lifecycle = V1HierarchyLifecycle(cuj_api_request_context, config)
    request.addfinalizer(lifecycle.delete)
    return V1HierarchyScenario(lifecycle)


@when("I create the metalake, catalog, and schema through V1")
def create_v1_parent_hierarchy(scenario: V1HierarchyScenario) -> None:
    """Creates the parent hierarchy in dependency order through V1 collection routes."""
    scenario.lifecycle.provision()


@when("I get each V1 parent resource")
def get_each_v1_parent_resource(scenario: V1HierarchyScenario) -> None:
    """Reads the metalake, catalog, and schema through their individual V1 routes."""
    scenario.lifecycle.verify_get()


@when("I list each V1 parent collection")
def list_each_v1_parent_collection(scenario: V1HierarchyScenario) -> None:
    """Lists the metalake, catalog, and schema collections through V1 routes."""
    scenario.lifecycle.verify_list()


@when("I fully replace each supported V1 parent state with its current ETag")
def replace_each_supported_v1_parent_state(scenario: V1HierarchyScenario) -> None:
    """Sends full desired-state parent PUTs with a current strong If-Match value."""
    scenario.lifecycle.update_metadata()


@then("each parent V1 PUT preserves its full supported desired state")
def parent_put_preserves_full_desired_state(scenario: V1HierarchyScenario) -> None:
    """Checks the full supported postcondition for all parent PUT requests."""
    scenario.lifecycle.verify_updated_metadata()


@when("I delete the schema, catalog, and metalake through V1 with their current ETags")
def delete_v1_parent_hierarchy(scenario: V1HierarchyScenario) -> None:
    """Deletes parent resources bottom-up through conditional V1 DELETE routes."""
    scenario.lifecycle.delete()


@then("the V1 parent hierarchy is deleted")
def v1_parent_hierarchy_is_deleted(scenario: V1HierarchyScenario) -> None:
    """Checks each deleted parent resource returns its strict public not-found error."""
    scenario.lifecycle.verify_deleted()


@when(
    parsers.parse(
        'I attempt a "{resource}" V1 full replacement with a "{precondition}" precondition'
    )
)
def attempt_parent_precondition_failure(
    scenario: V1HierarchyScenario, resource: str, precondition: str
) -> None:
    """Submits a valid parent PUT that deliberately omits or stales its validator."""
    scenario.response = scenario.lifecycle.attempt_parent_precondition_failure(
        resource, precondition
    )


@then("the parent API returns the strict non-retryable PRECONDITION_FAILED error")
def parent_api_returns_precondition_failed(scenario: V1HierarchyScenario) -> None:
    """Requires normal parent precondition failures to use the public V1 error contract."""
    if scenario.response is None:
        pytest.fail("The scenario did not submit a parent conditional mutation.")
    assert_v1_precondition_failed(scenario.response, "V1 parent conditional mutation")


@when("I attempt a V1 schema comment replacement")
def attempt_v1_schema_comment_replacement(scenario: V1HierarchyScenario) -> None:
    """Attempts the known core-limited schema comment mutation through a complete V1 PUT."""
    scenario.response = scenario.lifecycle.attempt_schema_comment_update()


@then("the documented schema-comment capability error is recorded without blocking the suite")
def schema_comment_capability_error_is_recorded(scenario: V1HierarchyScenario) -> None:
    """Records the documented schema comment 501 as a visible migration limitation."""
    if scenario.response is None:
        pytest.fail("The scenario did not submit a V1 schema comment replacement.")
    record_v1_unsupported_operation(
        scenario.response,
        reason=(
            "Schema comment replacement has no correct internal mutation primitive yet; "
            "the V1 API documents UNSUPPORTED_OPERATION until it does."
        ),
    )


@given(
    parsers.parse('a configured "{provider}" V1 table lifecycle environment'),
    target_fixture="v1_table_scenario",
)
def configured_v1_table_lifecycle_environment(
    provider: str,
    cuj_api_request_context: APIRequestContext,
    cuj_profiles: frozenset[str],
    request: pytest.FixtureRequest,
) -> V1TableScenario:
    """Creates an isolated provider-configured hierarchy for a table lifecycle scenario."""
    from cuj.support.provider_profiles import profile_for

    try:
        profile = profile_for(provider)
    except KeyError as error:
        raise pytest.UsageError(
            f"The V1 table lifecycle feature names unknown provider {provider!r}."
        ) from error
    if profile.name not in cuj_profiles:
        pytest.skip(
            f"The {profile.display_name} CUJ profile is not configured. "
            "Set GRAVITINO_OPENAPI_CUJ_PROFILES to run it."
        )
    config = v1_hierarchy_config_from_environment(profile.name)
    if config is None:
        provider_key = profile.name.upper().replace("-", "_")
        pytest.skip(
            f"Set GRAVITINO_OPENAPI_CUJ_{provider_key}_CATALOG_CREATE (or the generic "
            "GRAVITINO_OPENAPI_CUJ_CATALOG_CREATE) to provision this provider hierarchy."
        )
    hierarchy = V1HierarchyLifecycle(cuj_api_request_context, config)
    table_lifecycle = new_v1_table_lifecycle(profile.name, hierarchy, cuj_api_request_context)

    def cleanup() -> None:
        try:
            table_lifecycle.delete()
        finally:
            hierarchy.delete()

    request.addfinalizer(cleanup)
    return V1TableScenario(table_lifecycle)


@when("I create the V1 parent hierarchy for the table")
def create_v1_table_parent_hierarchy(scenario: V1TableScenario) -> None:
    """Creates the isolated metalake/catalog/schema prerequisite through V1 routes."""
    scenario.lifecycle.hierarchy.provision()


@when("I create the documented minimal V1 table")
def create_documented_minimal_v1_table(scenario: V1TableScenario) -> None:
    """Creates one provider-documented minimal table through the V1 collection route."""
    scenario.response = scenario.lifecycle.create()


@when("I get the V1 table")
def get_v1_table(scenario: V1TableScenario) -> None:
    """Reads the created table through the V1 item route."""
    scenario.lifecycle.verify_get()


@when("I list the V1 table collection")
def list_v1_table_collection(scenario: V1TableScenario) -> None:
    """Lists the V1 table collection beneath the disposable schema."""
    scenario.lifecycle.verify_list()


@when("I fully replace supported V1 table metadata with its current ETag")
def fully_replace_supported_v1_table_metadata(scenario: V1TableScenario) -> None:
    """Sends a complete table PUT that changes only supported comment/properties fields."""
    scenario.response = scenario.lifecycle.update_metadata()


@then("the table V1 PUT preserves comment and full properties desired state")
def table_put_preserves_supported_desired_state(scenario: V1TableScenario) -> None:
    """Checks the table full-PUT happy path postcondition."""
    scenario.lifecycle.verify_updated_metadata()


@when(
    parsers.parse(
        'I attempt a V1 table full replacement with a "{precondition}" precondition'
    )
)
def attempt_table_precondition_failure(
    scenario: V1TableScenario, precondition: str
) -> None:
    """Submits a valid table PUT with a stale or missing validator."""
    scenario.response = scenario.lifecycle.attempt_precondition_failure(precondition)


@then("the table API returns the strict non-retryable PRECONDITION_FAILED error")
def table_api_returns_precondition_failed(scenario: V1TableScenario) -> None:
    """Requires table conditional writes to use the strict public 412 contract."""
    if scenario.response is None:
        pytest.fail("The scenario did not submit a table conditional mutation.")
    assert_v1_precondition_failed(scenario.response, "V1 table conditional mutation")


@when("I attempt a structural V1 table replacement with its current ETag")
def attempt_structural_v1_table_replacement(scenario: V1TableScenario) -> None:
    """Attempts the documented unimplemented structural part of a complete V1 table PUT."""
    scenario.response = scenario.lifecycle.attempt_structural_update()


@then("the documented table-structural capability error is recorded without blocking the suite")
def table_structural_capability_error_is_recorded(scenario: V1TableScenario) -> None:
    """Records the documented structural-replacement 501 as a visible migration limitation."""
    if scenario.response is None:
        pytest.fail("The scenario did not submit a V1 table structural replacement.")
    record_v1_unsupported_operation(
        scenario.response,
        reason=(
            "Full table structure replacement has no correct internal mutation primitive yet; "
            "the V1 API documents UNSUPPORTED_OPERATION until it does."
        ),
    )


@when("I delete the V1 table with its current ETag")
def delete_v1_table(scenario: V1TableScenario) -> None:
    """Deletes the table through the conditional V1 item route."""
    scenario.response = scenario.lifecycle.delete()


@then("the V1 table is deleted")
def v1_table_is_deleted(scenario: V1TableScenario) -> None:
    """Checks the deleted table returns the strict public table-not-found error."""
    scenario.lifecycle.verify_deleted()
