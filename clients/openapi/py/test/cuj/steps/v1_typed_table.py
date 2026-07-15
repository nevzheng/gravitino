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

"""Gherkin steps for typed V1 table create and metadata round trips.

The feature is intentionally opt-in because each connector needs an externally
provisioned catalog and, for Lance/Delta/Glue, a fixture-owned storage location.
It is still a normal API-only CUJ: the hierarchy and table use only ``/api/v1``
routes, each mutation is conditional where applicable, and no legacy property
map participates in request or round-trip assertions.
"""

from __future__ import annotations

from collections.abc import Mapping
from copy import deepcopy
from dataclasses import dataclass, field
from typing import Any, TYPE_CHECKING
from urllib.parse import quote
from uuid import uuid4
import os

import pytest
from pytest_bdd import given, parsers, then, when

from cuj.steps.v1_hierarchy import V1HierarchyLifecycle, v1_hierarchy_config_from_environment
from cuj.support.transport import (
    ApiResponse,
    ExpectedApiError,
    assert_expected_api_error,
    observe_api_error,
    record_non_blocking_expected_error,
)
from cuj.support.v1_typed_table_profiles import (
    TypedTableCreatePlan,
    TypedTableCreateProfile,
    build_typed_table_create_plan,
    typed_table_profile,
)
from gravitino_testkit.v1_typed_tables import typed_option_keys, typed_table_update_body

if TYPE_CHECKING:
    from playwright.sync_api import APIRequestContext


_TABLE_NOT_FOUND = ExpectedApiError(404, "TABLE_NOT_FOUND", False)


@dataclass
class V1TypedTableScenario:
    """Holds the lifecycle state and most recent response for one BDD scenario."""

    lifecycle: "V1TypedTableLifecycle"
    response: ApiResponse | None = None


@dataclass
class V1TypedTableLifecycle:
    """Drives the typed V1 table post/get/put/delete lifecycle for one provider."""

    hierarchy: V1HierarchyLifecycle
    profile: TypedTableCreateProfile
    request_context: APIRequestContext
    table_location: str | None = None
    existing_dataset_location: str | None = None
    table_name: str = field(default_factory=lambda: f"typed_cuj_{uuid4().hex[:20]}")
    request_id: str = field(default_factory=lambda: f"typed-v1-cuj-{uuid4().hex}")
    plan: TypedTableCreatePlan = field(init=False)
    created: bool = False
    updated_body: dict[str, Any] | None = None

    def __post_init__(self) -> None:
        """Builds the immutable minimal POST plan before any external request is made."""
        self.plan = build_typed_table_create_plan(
            self.profile.provider,
            self.table_name,
            table_location=self.table_location,
            existing_dataset_location=self.existing_dataset_location,
        )

    @property
    def collection_path(self) -> str:
        """Returns the V1 table collection below the fixture-owned parent schema."""
        return f"{self.hierarchy.names.schema_path}/tables"

    @property
    def item_path(self) -> str:
        """Returns the V1 item route for this lifecycle's isolated table."""
        return f"{self.collection_path}/{quote(self.table_name, safe='')}"

    def create(self) -> ApiResponse:
        """Posts the provider's minimal clean V1 create body or records a known limitation."""
        self._require_ready_plan()
        response = self.request_context.post(
            self.collection_path,
            data=self.plan.request_body(),
            headers=self._json_headers(),
        )
        if 200 <= response.status < 300:
            self.created = True
        if self.plan.create_expected_error is not None:
            record_non_blocking_expected_error(
                response,
                self.plan.create_expected_error,
                reason=(
                    f"{self.profile.display_name} documents table creation as unsupported; "
                    "the typed V1 POC records the public outcome without blocking."
                ),
            )
            return response
        _assert_v1_table_success(response, self.collection_path)
        assert response.status == 201, (
            f"Typed V1 table creation must return 201, received HTTP {response.status}."
        )
        return response

    def verify_get(self) -> None:
        """Checks GET returns the storage/options state submitted by the typed V1 POST."""
        _, resource = self._load_resource()
        self._assert_typed_state(resource)

    def update_metadata(self) -> ApiResponse:
        """Uses GET state and its ETag to PUT a comment-only metadata replacement."""
        response, resource = self._load_resource()
        etag = _strong_etag(response, self.item_path)
        body = self._full_desired_state(resource)
        current_comment = body["comment"]
        replacement_comment = "updated by the typed V1 table lifecycle CUJ"
        if current_comment == replacement_comment:
            replacement_comment += " again"
        body["comment"] = replacement_comment
        response = self.request_context.put(
            self.item_path,
            data=body,
            headers=self._json_headers(if_match=etag),
        )
        if self.plan.update_expected_error is not None:
            record_non_blocking_expected_error(
                response,
                self.plan.update_expected_error,
                reason=(
                    f"{self.profile.display_name} documents this metadata update as unavailable; "
                    "the typed V1 POC keeps the public outcome visible."
                ),
            )
            if 200 <= response.status < 300:
                self.updated_body = body
            return response
        _assert_v1_table_success(response, self.item_path)
        self.updated_body = body
        return response

    def verify_updated_metadata(self) -> None:
        """Verifies a successful full PUT retains the prior typed storage/options state."""
        if self.updated_body is None:
            return
        _, resource = self._load_resource()
        assert resource.get("comment") == self.updated_body["comment"], (
            "The typed V1 table PUT did not retain the replacement comment."
        )
        self._assert_typed_state(resource)
        expected_options = _option_blocks_from(self.updated_body)
        assert _option_blocks_from(resource) == expected_options, (
            "The typed V1 table PUT did not preserve the submitted typed options block."
        )

    def delete(self) -> ApiResponse | None:
        """Deletes the scenario-owned table through a conditional V1 DELETE, if it exists."""
        if not self.created:
            return None
        response = self.request_context.get(self.item_path, headers=self._read_headers())
        if _is_table_not_found(response):
            self.created = False
            return None
        _assert_v1_table_success(response, self.item_path)
        response = self.request_context.delete(
            self.item_path,
            headers=self._conditional_headers(_strong_etag(response, self.item_path)),
        )
        _assert_v1_table_success(response, self.item_path)
        assert response.status == 204, (
            f"Typed V1 table deletion must return 204, received HTTP {response.status}."
        )
        self.created = False
        return response

    def verify_deleted(self) -> None:
        """Checks a successful lifecycle ends with the typed public table-not-found error."""
        response = self.request_context.get(self.item_path, headers=self._read_headers())
        assert_expected_api_error(response, _TABLE_NOT_FOUND)

    def _require_ready_plan(self) -> None:
        """Skips before any network work when fixture-owned locations are absent."""
        if self.plan.unmet_requirements:
            pytest.skip(
                f"{self.profile.display_name} needs explicit typed-table CUJ fixture values: "
                + ", ".join(self.plan.unmet_requirements)
            )

    def _load_resource(self) -> tuple[ApiResponse, Mapping[str, Any]]:
        """Loads the table and checks its identity before building a full replacement."""
        response = self.request_context.get(self.item_path, headers=self._read_headers())
        _assert_v1_table_success(response, self.item_path)
        payload = response.json()
        assert isinstance(payload, Mapping), "Typed V1 table GET must return a JSON object."
        assert payload.get("name") == self.table_name, (
            f"{self.item_path} did not return its created table identity."
        )
        _strong_etag(response, self.item_path)
        return response, payload

    def _full_desired_state(self, resource: Mapping[str, Any]) -> dict[str, Any]:
        """Builds a PUT from GET state, omitting identity/audit and all raw properties."""
        columns = _required_list(resource, "columns")
        partitioning = _required_list(resource, "partitioning")
        sort_orders = _required_list(resource, "sortOrders")
        indexes = _required_list(resource, "indexes")
        comment = resource.get("comment")
        assert comment is None or isinstance(comment, str), "Table comment must be a string or null."
        storage = resource.get("storage")
        if storage is not None:
            assert isinstance(storage, Mapping), "Table storage must be a JSON object."
        distribution = resource.get("distribution")
        if distribution is not None:
            assert isinstance(distribution, Mapping), "Table distribution must be a JSON object."
        body = typed_table_update_body(
            comment=comment,
            columns=columns,
            storage=storage,
            option_blocks=_option_blocks_from(resource),
            partitioning=partitioning,
            distribution=distribution,
            sort_orders=sort_orders,
            indexes=indexes,
        )
        assert "properties" not in body
        return body

    def _assert_typed_state(self, resource: Mapping[str, Any]) -> None:
        """Checks GET exposes exactly the shared storage/options selected by the profile."""
        expected_body = self.plan.request_body()
        if "storage" in expected_body:
            assert resource.get("storage") == expected_body["storage"], (
                "Typed V1 table GET did not preserve the requested shared storage state."
            )
        else:
            assert "storage" not in resource, (
                "A JDBC-style typed V1 profile must not gain an invented storage object."
            )
        assert _option_blocks_from(resource) == _option_blocks_from(expected_body), (
            "Typed V1 table GET did not preserve the selected typed provider-options block."
        )
        assert "properties" not in resource, (
            "Typed V1 table GET must not expose the legacy generic properties map."
        )

    def _read_headers(self) -> dict[str, str]:
        """Returns ordinary JSON representation headers for the typed V1 route."""
        return {"Accept": "application/json", "X-Request-Id": self.request_id}

    def _json_headers(self, *, if_match: str | None = None) -> dict[str, str]:
        """Returns JSON mutation headers, optionally carrying a strong current ETag."""
        headers = self._read_headers() | {"Content-Type": "application/json"}
        if if_match is not None:
            headers["If-Match"] = if_match
        return headers

    def _conditional_headers(self, etag: str) -> dict[str, str]:
        """Returns the bodyless conditional header set for typed V1 table deletion."""
        return self._read_headers() | {"If-Match": etag}


def typed_table_contract_enabled() -> bool:
    """Returns whether a caller explicitly opted into live typed-table contract CUJs."""
    return os.environ.get("GRAVITINO_OPENAPI_CUJ_TYPED_TABLE_CONTRACT", "").strip().lower() in {
        "1",
        "true",
        "yes",
    }


def _fixture_location(provider: str, binding: str, table_name: str) -> str | None:
    """Reads one explicit provider location and expands its isolated-table placeholder."""
    provider_key = provider.upper().replace("-", "_")
    raw = os.environ.get(f"GRAVITINO_OPENAPI_CUJ_{provider_key}_{binding.upper()}")
    if raw is None:
        raw = os.environ.get(f"GRAVITINO_OPENAPI_CUJ_{binding.upper()}")
    if raw is None:
        return None
    return raw.replace("{table}", table_name)


def _configured_typed_lifecycle(
    provider: str,
    request_context: APIRequestContext,
    configured_profiles: frozenset[str],
) -> V1TypedTableLifecycle:
    """Creates an unprovisioned typed lifecycle only for an explicitly selected provider."""
    profile = typed_table_profile(provider)
    if profile.provider not in configured_profiles:
        pytest.skip(
            f"The {profile.display_name} profile is not configured. "
            "Set GRAVITINO_OPENAPI_CUJ_PROFILES to run it."
        )
    if not typed_table_contract_enabled():
        pytest.skip(
            "Set GRAVITINO_OPENAPI_CUJ_TYPED_TABLE_CONTRACT=true after deploying the typed "
            "V1 table contract to run external connector CUJs."
        )
    config = v1_hierarchy_config_from_environment(profile.provider)
    if config is None:
        provider_key = profile.provider.upper().replace("-", "_")
        pytest.skip(
            f"Set GRAVITINO_OPENAPI_CUJ_{provider_key}_CATALOG_CREATE (or the generic "
            "GRAVITINO_OPENAPI_CUJ_CATALOG_CREATE) to provision the typed profile hierarchy."
        )
    hierarchy = V1HierarchyLifecycle(request_context, config)
    lifecycle = V1TypedTableLifecycle(
        hierarchy=hierarchy,
        profile=profile,
        request_context=request_context,
    )
    lifecycle.table_location = _fixture_location(
        profile.provider, "table_location", lifecycle.table_name
    )
    lifecycle.existing_dataset_location = _fixture_location(
        profile.provider, "existing_dataset_location", lifecycle.table_name
    )
    lifecycle.plan = build_typed_table_create_plan(
        profile.provider,
        lifecycle.table_name,
        table_location=lifecycle.table_location,
        existing_dataset_location=lifecycle.existing_dataset_location,
    )
    return lifecycle


def _required_list(resource: Mapping[str, Any], name: str) -> list[Mapping[str, Any]]:
    """Returns one required collection field from a V1 table representation."""
    value = resource.get(name)
    assert isinstance(value, list), f"Typed V1 table GET must include {name}."
    assert all(isinstance(item, Mapping) for item in value), (
        f"Typed V1 table GET field {name} must contain JSON objects."
    )
    return [deepcopy(dict(item)) for item in value]


def _option_blocks_from(resource: Mapping[str, Any]) -> dict[str, Mapping[str, Any]]:
    """Extracts and validates the at-most-one typed option block from a resource or body."""
    blocks = {
        key: value for key, value in resource.items() if key in typed_option_keys() and value is not None
    }
    assert len(blocks) <= 1, "The typed V1 table contract permits at most one options block."
    assert all(isinstance(value, Mapping) for value in blocks.values()), (
        "Typed V1 provider options must be JSON objects."
    )
    return {key: deepcopy(dict(value)) for key, value in blocks.items()}


def _assert_v1_table_success(response: ApiResponse, path: str) -> None:
    """Rejects absent V1 routes while requiring a normal successful HTTP response."""
    observed = observe_api_error(response)
    if observed.status in {404, 405} and observed.error_type in {
        None,
        "ROUTE_NOT_FOUND",
        "METHOD_NOT_ALLOWED",
    }:
        raise AssertionError(
            f"The intended typed V1 table route is not implemented: {path}; "
            f"received {(observed.status, observed.error_type)!r}."
        )
    assert 200 <= response.status < 300, f"Expected typed V1 success from {path}, got {response.status}."


def _is_table_not_found(response: ApiResponse) -> bool:
    """Returns whether a table item is absent rather than the typed V1 route missing."""
    return response.status == 404 and observe_api_error(response).error_type == "TABLE_NOT_FOUND"


def _strong_etag(response: ApiResponse, path: str) -> str:
    """Reads the required strong ETag that protects a typed full replacement or delete."""
    headers = getattr(response, "headers", None)
    if callable(headers):
        headers = headers()
    assert isinstance(headers, Mapping), f"{path} must return response headers."
    etag = next((value for name, value in headers.items() if str(name).lower() == "etag"), None)
    assert isinstance(etag, str) and etag.startswith('"') and etag.endswith('"'), (
        f"{path} must return a strong ETag, received {etag!r}."
    )
    assert not etag.startswith('W/"'), f"{path} must not return a weak ETag."
    return etag


@given(
    parsers.parse('a configured "{provider}" typed V1 table contract environment'),
    target_fixture="v1_typed_table_scenario",
)
def configured_typed_v1_table_environment(
    provider: str,
    cuj_api_request_context: APIRequestContext,
    cuj_profiles: frozenset[str],
    request: pytest.FixtureRequest,
) -> V1TypedTableScenario:
    """Allocates a typed V1 table lifecycle and registers conditional resource cleanup."""
    lifecycle = _configured_typed_lifecycle(provider, cuj_api_request_context, cuj_profiles)

    def cleanup() -> None:
        try:
            lifecycle.delete()
        finally:
            lifecycle.hierarchy.delete()

    request.addfinalizer(cleanup)
    return V1TypedTableScenario(lifecycle)


@when("I create the minimal typed V1 table")
def create_minimal_typed_v1_table(scenario: V1TypedTableScenario) -> None:
    """Provisions parents and submits the clean typed V1 POST for the selected provider."""
    scenario.lifecycle.hierarchy.provision()
    scenario.response = scenario.lifecycle.create()


@then("the typed V1 table create outcome is accepted or recorded")
def typed_v1_table_create_outcome_is_recorded(scenario: V1TypedTableScenario) -> None:
    """Checks normal creates are 201 while documented negative creates were visibly recorded."""
    if scenario.response is None:
        pytest.fail("The typed V1 table scenario did not submit a POST.")
    if scenario.lifecycle.plan.create_expected_error is None:
        assert scenario.response.status == 201


@when("I get the typed V1 table")
def get_typed_v1_table(scenario: V1TypedTableScenario) -> None:
    """Reads the created table through its V1 item route."""
    scenario.lifecycle.verify_get()


@then("the typed V1 GET preserves shared storage and provider options")
def typed_v1_get_preserves_state(scenario: V1TypedTableScenario) -> None:
    """Makes the GET shape an explicit compatibility assertion for typed table state."""
    scenario.lifecycle.verify_get()


@when("I fully replace typed V1 table metadata with its current ETag")
def replace_typed_v1_table_metadata(scenario: V1TypedTableScenario) -> None:
    """Sends a full PUT that changes only the comment and round-trips typed state."""
    scenario.response = scenario.lifecycle.update_metadata()


@then("the typed V1 PUT preserves shared storage and provider options")
def typed_v1_put_preserves_state(scenario: V1TypedTableScenario) -> None:
    """Checks successful PUTs retain the typed state, including an option block when selected."""
    scenario.lifecycle.verify_updated_metadata()


@when("I delete the typed V1 table with its current ETag")
def delete_typed_v1_table(scenario: V1TypedTableScenario) -> None:
    """Deletes the typed table using an item GET validator rather than an unconditioned delete."""
    scenario.response = scenario.lifecycle.delete()


@then("the typed V1 table is deleted")
def typed_v1_table_is_deleted(scenario: V1TypedTableScenario) -> None:
    """Requires the public typed V1 not-found error after a successful lifecycle cleanup."""
    scenario.lifecycle.verify_deleted()
