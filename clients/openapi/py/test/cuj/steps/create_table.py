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

"""API-only pytest-bdd steps for the V1 create-table customer journey.

The steps intentionally drive the future V1 collection and item routes directly
through Playwright's :class:`APIRequestContext`.  They never use the legacy
``/api`` table endpoints and never launch a browser.  That makes a missing V1
write lifecycle visible as a normal failed CUJ instead of allowing the legacy
surface to count as V1 coverage.
"""

from __future__ import annotations

from collections.abc import Iterable, Mapping
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, TYPE_CHECKING
from urllib.parse import quote
from uuid import uuid4
import os
import re
import warnings

import pytest
from gherkin.parser import Parser
from pytest_bdd import given, parsers, then, when

from cuj.support.create_table_payloads import (
    CreateTablePayloadPlan,
    build_create_table_payload,
)
from cuj.support.provider_profiles import (
    DeclaredErrorRecord,
    DocumentedUseCase,
    ProviderProfile,
    common_error_for,
    declared_error_for,
    documented_use_case,
    profile_for,
)
from cuj.support.transport import (
    ApiResponse,
    DeclaredApiLimitationWarning,
    ExpectedApiError,
    assert_expected_api_error,
    observe_api_error,
    record_non_blocking_expected_error,
)

if TYPE_CHECKING:
    from playwright.sync_api import APIRequestContext


_CONFIGURED_PROVIDER_STEP = re.compile(
    r'^a configured "(?P<provider>[^"]+)" create-table environment'
)
_DOCUMENTED_USE_CASE_STEP = re.compile(
    r'^(?:I submit the|an existing table created by the) '
    r'"(?P<use_case>[^"]+)" documented create-table use case$'
)
_INVALID_USE_CASE_STEP = re.compile(
    r'^I submit the "(?P<use_case>[^"]+)" invalid create-table request$'
)
_EXPECTED_ERROR_STEP = re.compile(
    r'^the API returns the non-retryable "(?P<error_type>[^"]+)" '
    r'error with status (?P<status>\d+)$'
)


@dataclass(frozen=True)
class V1CreateTableLocation:
    """Names the configured V1 parent resource for one provider profile."""

    metalake: str
    catalog: str
    var_schema: str


@dataclass
class CreateTableJourney:
    """Holds the observable state of one API-only create-table scenario."""

    profile: ProviderProfile
    location: V1CreateTableLocation
    request_context: APIRequestContext
    authorization_mode: str = "configured"
    request_id: str = field(default_factory=lambda: f"cuj-{uuid4().hex}")
    table_name: str | None = None
    selected_use_case: DocumentedUseCase | None = None
    plan: CreateTablePayloadPlan | None = None
    response: ApiResponse | None = None
    expected_error: DeclaredErrorRecord | None = None
    created: bool = False
    existing_table: bool = False
    preexisting_dataset: bool = False
    table_location: str | None = None
    existing_dataset_location: str | None = None
    metadata_location: str | None = None

    @property
    def collection_path(self) -> str:
        """Returns the route-versioned V1 table collection path."""
        location = self.location
        return (
            "/api/v1/metalakes/"
            f"{quote(location.metalake, safe='')}/catalogs/"
            f"{quote(location.catalog, safe='')}/schemas/"
            f"{quote(location.var_schema, safe='')}/tables"
        )

    @property
    def item_path(self) -> str:
        """Returns the route-versioned V1 table item path for the scenario."""
        if self.table_name is None:
            raise AssertionError("A table name must be established before using an item route.")
        return f"{self.collection_path}/{quote(self.table_name, safe='')}"

    def set_unique_table_name(self) -> None:
        """Assigns an isolated bounded table name to the current scenario."""
        if self.table_name is None:
            self.table_name = f"cuj_{self.profile.name}_{uuid4().hex[:20]}"

    def submit_documented_use_case(self, use_case: str) -> ApiResponse:
        """Builds and submits one documented provider use case to the V1 route."""
        self.set_unique_table_name()
        self.selected_use_case = documented_use_case(self.profile.name, use_case)
        declared_error = declared_error_for(self.profile.name, use_case)
        if declared_error is not None:
            self.expected_error = declared_error
        self.plan = _build_payload_plan(self, use_case)
        response = self._post(self.plan)
        self.response = response
        if 200 <= response.status < 300:
            self.created = True
        return response

    def submit_invalid_use_case(self, use_case: str) -> ApiResponse:
        """Builds and submits an intentionally invalid V1 table-create request."""
        self.set_unique_table_name()
        try:
            self.selected_use_case = documented_use_case(self.profile.name, use_case)
            self.expected_error = declared_error_for(self.profile.name, use_case)
        except KeyError:
            self.selected_use_case = None
            self.expected_error = common_error_for(use_case)
        self.plan = _build_payload_plan(self, use_case)
        response = self._post(self.plan)
        self.response = response
        return response

    def resubmit_documented_use_case(self) -> ApiResponse:
        """Submits the selected documented use case again with the same table name."""
        if self.selected_use_case is None:
            raise AssertionError("No documented create-table use case has been submitted.")
        self.plan = _build_payload_plan(self, self.selected_use_case.name)
        response = self._post(self.plan)
        self.response = response
        return response

    def load_table(self) -> ApiResponse:
        """Loads the created table through the V1 item route."""
        response = self.request_context.get(self.item_path, headers=self._headers())
        self.response = response
        return response

    def list_tables(self) -> ApiResponse:
        """Lists tables through the V1 collection route."""
        response = self.request_context.get(self.collection_path, headers=self._headers())
        self.response = response
        return response

    def cleanup(self) -> ApiResponse:
        """Deletes the scenario-owned table through the conditional V1 item route."""
        loaded = self.load_table()
        _assert_success(loaded)
        response = self.request_context.delete(
            self.item_path,
            headers=self._headers(
                if_match=_strong_response_etag(loaded, self.item_path),
                include_content_type=False,
            ),
        )
        self.response = response
        return response

    def _post(self, plan: CreateTablePayloadPlan) -> ApiResponse:
        """Posts a planned V1 wire body without falling back to a legacy route."""
        unmet_requirements = tuple(plan.unmet_requirements)
        if unmet_requirements:
            pytest.skip(
                "The configured external provider lacks this CUJ prerequisite: "
                + "; ".join(unmet_requirements)
            )
        return self.request_context.post(
            self.collection_path,
            data=plan.body(),
            headers=self._headers(),
        )

    def _headers(
        self, *, if_match: str | None = None, include_content_type: bool = True
    ) -> dict[str, str]:
        """Builds V1 headers for the current authentication and conditional-write mode."""
        headers = {
            "Accept": "application/json",
            "X-Request-Id": self.request_id,
        }
        if include_content_type:
            headers["Content-Type"] = "application/json"
        if if_match is not None:
            headers["If-Match"] = if_match
        if self.authorization_mode == "anonymous":
            headers["Authorization"] = ""
        elif self.authorization_mode == "insufficient":
            insufficient_authorization = os.environ.get(
                "GRAVITINO_OPENAPI_CUJ_INSUFFICIENT_AUTHORIZATION", ""
            ).strip()
            if not insufficient_authorization:
                pytest.skip(
                    "Set GRAVITINO_OPENAPI_CUJ_INSUFFICIENT_AUTHORIZATION to run "
                    "the insufficient-privilege CUJ."
                )
            headers["Authorization"] = insufficient_authorization
        return headers


def _configured_location(provider: str) -> V1CreateTableLocation:
    """Resolves safe provider-specific parent names from explicit CUJ configuration."""
    provider_prefix = f"GRAVITINO_OPENAPI_CUJ_{provider.upper()}"
    global_prefix = "GRAVITINO_OPENAPI_CUJ"
    return V1CreateTableLocation(
        metalake=os.environ.get(
            f"{provider_prefix}_METALAKE",
            os.environ.get(f"{global_prefix}_METALAKE", "gravitino_cuj"),
        ),
        catalog=os.environ.get(
            f"{provider_prefix}_CATALOG",
            os.environ.get(f"{global_prefix}_CATALOG", f"cuj_{provider}"),
        ),
        var_schema=os.environ.get(
            f"{provider_prefix}_SCHEMA",
            os.environ.get(f"{global_prefix}_SCHEMA", "cuj"),
        ),
    )


def _fixture_binding(provider: str, name: str, table_name: str) -> str | None:
    """Returns an optional provider fixture value, expanding its table-name placeholder."""
    provider_prefix = f"GRAVITINO_OPENAPI_CUJ_{provider.upper()}"
    global_prefix = "GRAVITINO_OPENAPI_CUJ"
    raw_value = os.environ.get(f"{provider_prefix}_{name.upper()}")
    if raw_value is None:
        raw_value = os.environ.get(f"{global_prefix}_{name.upper()}")
    if raw_value is None:
        return None
    return raw_value.replace("{table}", table_name)


def _canonical_configured_profiles(profiles: Iterable[str]) -> frozenset[str]:
    """Normalizes configured profile aliases before matching feature examples."""
    try:
        return frozenset(profile_for(profile).name for profile in profiles)
    except KeyError as error:
        raise pytest.UsageError(
            "GRAVITINO_OPENAPI_CUJ_PROFILES contains an unknown provider profile."
        ) from error


def _new_journey(
    provider_name: str,
    configured_profiles: frozenset[str],
    request_context: APIRequestContext,
    *,
    authorization_mode: str = "configured",
) -> CreateTableJourney:
    """Creates a journey or skips a provider that was not explicitly configured."""
    profile = profile_for(provider_name)
    if profile.name not in configured_profiles:
        pytest.skip(
            f"The {profile.display_name} CUJ profile is not configured. "
            "Set GRAVITINO_OPENAPI_CUJ_PROFILES to run it."
        )
    return new_create_table_journey(
        profile.name,
        _configured_location(profile.name),
        request_context,
        authorization_mode=authorization_mode,
    )


def new_create_table_journey(
    provider_name: str,
    location: V1CreateTableLocation,
    request_context: APIRequestContext,
    *,
    authorization_mode: str = "configured",
    table_location: str | None = None,
    existing_dataset_location: str | None = None,
    metadata_location: str | None = None,
) -> CreateTableJourney:
    """Creates a journey from a V1 hierarchy fixture or an explicit configured location.

    Generic V1 metalake/catalog/schema CUJ fixtures can provision an isolated
    hierarchy, construct :class:`V1CreateTableLocation`, and pass it here. The
    table journey deliberately owns only table mutations and cleanup. Storage
    fixture values can be passed directly; environment defaults are used only
    when the caller does not provide an explicit binding.
    """
    journey = CreateTableJourney(
        profile=profile_for(provider_name),
        location=location,
        request_context=request_context,
        authorization_mode=authorization_mode,
    )
    journey.set_unique_table_name()
    assert journey.table_name is not None
    journey.table_location = table_location or _fixture_binding(
        journey.profile.name, "table_location", journey.table_name
    )
    journey.existing_dataset_location = existing_dataset_location or _fixture_binding(
        journey.profile.name, "existing_dataset_location", journey.table_name
    )
    journey.metadata_location = metadata_location or _fixture_binding(
        journey.profile.name, "metadata_location", journey.table_name
    )
    return journey


def _build_payload_plan(journey: CreateTableJourney, use_case: str) -> CreateTablePayloadPlan:
    """Builds a provider-specific V1 request plan from the CUJ payload matrix."""
    if journey.table_name is None:
        raise AssertionError("A table name must be established before building a request.")
    return build_create_table_payload(
        journey.profile.name,
        use_case,
        journey.table_name,
        table_location=journey.table_location,
        existing_dataset_location=journey.existing_dataset_location,
        metadata_location=journey.metadata_location,
        existing_table=journey.existing_table,
    )


def _response_or_fail(journey: CreateTableJourney) -> ApiResponse:
    """Returns the preceding raw API response or reports an invalid BDD step sequence."""
    if journey.response is None:
        pytest.fail("The scenario did not submit a V1 request before asserting its result.")
    return journey.response


def _assert_v1_collection_route_handled(response: ApiResponse) -> None:
    """Fails a CUJ when the V1 collection route itself is absent or rejects POST."""
    observed = observe_api_error(response)
    if observed.status in {404, 405} and observed.error_type in {
        None,
        "ROUTE_NOT_FOUND",
        "METHOD_NOT_ALLOWED",
    }:
        pytest.fail(
            "The V1 create-table collection lifecycle is not implemented at "
            "/api/v1/.../tables. CUJs never fall back to the legacy /api route; "
            f"received status/type {(observed.status, observed.error_type)!r}."
        )


def _assert_success(response: ApiResponse) -> None:
    """Asserts a request reached a successful V1 HTTP outcome."""
    _assert_v1_collection_route_handled(response)
    assert 200 <= response.status < 300, (
        f"Expected a successful V1 response, got HTTP {response.status}."
    )


def _strong_response_etag(response: ApiResponse, path: str) -> str:
    """Extracts the strong V1 validator needed for a conditional table cleanup DELETE."""
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


def _public_error(response: ApiResponse) -> Mapping[str, Any]:
    """Returns a conforming public error object for detail-specific assertions."""
    payload = response.json()
    assert isinstance(payload, Mapping), "A public API error must have a JSON object envelope."
    error = payload.get("error")
    assert isinstance(error, Mapping), "A public API error must contain an error object."
    assert isinstance(error.get("requestId"), str) and error["requestId"], (
        "A public API error must include a non-empty requestId."
    )
    details = error.get("details")
    assert isinstance(details, list), "A public API error must include its details array."
    return error


def _assert_normal_error(
    journey: CreateTableJourney, expected: ExpectedApiError
) -> Mapping[str, Any]:
    """Asserts one contractual negative response exactly, including its public envelope."""
    response = _response_or_fail(journey)
    _assert_v1_collection_route_handled(response)
    assert_expected_api_error(response, expected)
    return _public_error(response)


def _expected_error_for(journey: CreateTableJourney) -> ExpectedApiError:
    """Returns the exact expected error selected by the preceding scenario action."""
    record = journey.expected_error
    if record is None or record.expected is None:
        pytest.fail(
            "The feature has no exact public V1 error declaration for this normal negative "
            "scenario. Add one to provider_profiles before making it contractual."
        )
    return record.expected


def _assert_error_detail_kind(journey: CreateTableJourney, kind: str) -> None:
    """Asserts that a public error contains the expected typed detail discriminator."""
    error = _public_error(_response_or_fail(journey))
    assert any(
        isinstance(detail, Mapping) and detail.get("kind") == kind
        for detail in error["details"]
    ), f"Expected a {kind} detail in the public error: {error!r}"


def _loaded_table(journey: CreateTableJourney) -> Mapping[str, Any]:
    """Loads the table through V1 and returns its JSON resource representation."""
    response = journey.load_table()
    _assert_success(response)
    payload = response.json()
    assert isinstance(payload, Mapping), "The V1 table GET response must be a JSON object."
    assert payload.get("name") == journey.table_name, (
        "The V1 table GET response must identify the table created by this scenario."
    )
    return payload


def _payload_contains_table_name(payload: Any, table_name: str) -> bool:
    """Returns whether a V1 list representation includes the expected table name."""
    if isinstance(payload, Mapping):
        if payload.get("name") == table_name:
            return True
        return any(_payload_contains_table_name(value, table_name) for value in payload.values())
    if isinstance(payload, list):
        return any(_payload_contains_table_name(value, table_name) for value in payload)
    return False


def _record_non_blocking_observation(
    journey: CreateTableJourney, record: DeclaredErrorRecord
) -> None:
    """Records a known provider limitation without converting it into an xfail."""
    response = _response_or_fail(journey)
    _assert_v1_collection_route_handled(response)
    if record.expected is not None:
        record_non_blocking_expected_error(
            response,
            record.expected,
            reason=record.warning_reason(journey.profile.name),
        )
        return

    observed = observe_api_error(response)
    warnings.warn(
        "Declared API observation has no fixed public-error taxonomy yet: "
        f"{record.warning_reason(journey.profile.name)}. Observed "
        f"status/type/retryable {(observed.status, observed.error_type, observed.retryable)!r}.",
        DeclaredApiLimitationWarning,
        stacklevel=2,
    )


@given(
    parsers.parse('a configured "{provider}" create-table environment'),
    target_fixture="cuj_journey",
)
def configured_create_table_environment(
    provider: str,
    cuj_api_request_context: APIRequestContext,
    cuj_profiles: frozenset[str],
) -> CreateTableJourney:
    """Selects a configured provider environment for a normal authenticated scenario."""
    return _new_journey(
        provider,
        _canonical_configured_profiles(cuj_profiles),
        cuj_api_request_context,
    )


@given(
    parsers.parse('a configured "{provider}" create-table environment with a missing "{parent}"'),
    target_fixture="cuj_journey",
)
def configured_environment_with_missing_parent(
    provider: str,
    parent: str,
    cuj_api_request_context: APIRequestContext,
    cuj_profiles: frozenset[str],
) -> CreateTableJourney:
    """Selects a profile and replaces exactly one parent resource with a missing name."""
    journey = _new_journey(
        provider,
        _canonical_configured_profiles(cuj_profiles),
        cuj_api_request_context,
    )
    missing_name = f"missing_cuj_{uuid4().hex[:20]}"
    location = journey.location
    if parent == "metalake":
        journey.location = V1CreateTableLocation(
            missing_name,
            location.catalog,
            location.var_schema,
        )
        journey.expected_error = common_error_for("missing_metalake")
    elif parent == "catalog":
        journey.location = V1CreateTableLocation(
            location.metalake,
            missing_name,
            location.var_schema,
        )
        journey.expected_error = common_error_for("missing_catalog")
    elif parent == "schema":
        journey.location = V1CreateTableLocation(location.metalake, location.catalog, missing_name)
        journey.expected_error = common_error_for("missing_schema")
    else:
        raise pytest.UsageError(f"Unsupported missing create-table parent {parent!r}.")
    return journey


@given(
    parsers.parse('a configured "{provider}" create-table environment without credentials'),
    target_fixture="cuj_journey",
)
def configured_environment_without_credentials(
    provider: str,
    cuj_api_request_context: APIRequestContext,
    cuj_profiles: frozenset[str],
) -> CreateTableJourney:
    """Selects a configured profile and overrides its request authorization as empty."""
    journey = _new_journey(
        provider,
        _canonical_configured_profiles(cuj_profiles),
        cuj_api_request_context,
        authorization_mode="anonymous",
    )
    journey.expected_error = common_error_for("unauthenticated")
    return journey


@given(
    parsers.parse(
        'a configured "{provider}" create-table environment with insufficient '
        "create-table privileges"
    ),
    target_fixture="cuj_journey",
)
def configured_environment_with_insufficient_privileges(
    provider: str,
    cuj_api_request_context: APIRequestContext,
    cuj_profiles: frozenset[str],
) -> CreateTableJourney:
    """Selects a configured profile and uses the explicitly configured low-privilege credential."""
    journey = _new_journey(
        provider,
        _canonical_configured_profiles(cuj_profiles),
        cuj_api_request_context,
        authorization_mode="insufficient",
    )
    journey.expected_error = common_error_for("permission_denied")
    return journey


@given("a unique table name")
def unique_table_name(cuj_journey: CreateTableJourney) -> None:
    """Allocates an isolated table name for the scenario."""
    cuj_journey.set_unique_table_name()


@given(parsers.parse('an existing table created by the "{use_case}" documented use case'))
def existing_documented_table(cuj_journey: CreateTableJourney, use_case: str) -> None:
    """Creates the prerequisite table through the same V1 collection route."""
    _assert_success(cuj_journey.submit_documented_use_case(use_case))
    cuj_journey.existing_table = True


@given("a pre-existing provider dataset for that table")
def preexisting_provider_dataset(cuj_journey: CreateTableJourney) -> None:
    """Marks the scenario for the provider-specific pre-existing dataset fixture input."""
    cuj_journey.preexisting_dataset = True


@when(parsers.parse('I submit the "{use_case}" documented create-table use case'))
def submit_documented_create_table_use_case(
    cuj_journey: CreateTableJourney, use_case: str
) -> None:
    """Posts a documented provider request to the V1 table collection route."""
    cuj_journey.submit_documented_use_case(use_case)


@when(parsers.parse('I submit the "{use_case}" invalid create-table request'))
def submit_invalid_create_table_request(
    cuj_journey: CreateTableJourney, use_case: str
) -> None:
    """Posts an intentionally invalid request to the V1 table collection route."""
    cuj_journey.submit_invalid_use_case(use_case)


@when("I submit the same documented create-table use case again")
def resubmit_documented_create_table_use_case(cuj_journey: CreateTableJourney) -> None:
    """Posts the prior documented request again to exercise create conflict semantics."""
    cuj_journey.resubmit_documented_use_case()


@then("the create operation succeeds")
def create_operation_succeeds(cuj_journey: CreateTableJourney) -> None:
    """Requires a successful V1 create response."""
    _assert_success(_response_or_fail(cuj_journey))


@then("the created table can be loaded through the public API")
def created_table_can_be_loaded(cuj_journey: CreateTableJourney) -> None:
    """Requires the V1 item route to return the table just created."""
    _loaded_table(cuj_journey)


@then("the created table is listed in its schema")
def created_table_is_listed(cuj_journey: CreateTableJourney) -> None:
    """Requires the V1 collection route to include the table just created."""
    response = cuj_journey.list_tables()
    _assert_success(response)
    assert cuj_journey.table_name is not None
    assert _payload_contains_table_name(response.json(), cuj_journey.table_name), (
        "The V1 table collection response did not contain the table created by this scenario."
    )


@then("the loaded table preserves the documented metadata")
def loaded_table_preserves_metadata(cuj_journey: CreateTableJourney) -> None:
    """Checks the documented metadata fields selected by the provider payload plan."""
    table = _loaded_table(cuj_journey)
    plan = cuj_journey.plan
    assert plan is not None
    expected = plan.body()
    for field_name in ("comment", "properties"):
        if field_name in expected:
            assert table.get(field_name) == expected[field_name], (
                f"The V1 table resource did not preserve {field_name!r}."
            )


@then("the loaded table preserves the documented schema")
def loaded_table_preserves_schema(cuj_journey: CreateTableJourney) -> None:
    """Checks the documented column order and names selected by the payload plan."""
    table = _loaded_table(cuj_journey)
    plan = cuj_journey.plan
    assert plan is not None
    expected_columns = plan.body().get("columns")
    assert isinstance(expected_columns, list)
    actual_columns = table.get("columns")
    assert isinstance(actual_columns, list), "A V1 table resource must expose its columns."
    assert [column.get("name") for column in actual_columns] == [
        column.get("name") for column in expected_columns if isinstance(column, Mapping)
    ], "The V1 table resource did not preserve the documented column names and order."


@then("the loaded table preserves the documented layout metadata")
def loaded_table_preserves_layout(cuj_journey: CreateTableJourney) -> None:
    """Checks layout fields that were supplied by the provider payload plan."""
    table = _loaded_table(cuj_journey)
    plan = cuj_journey.plan
    assert plan is not None
    expected = plan.body()
    for field_name in ("partitioning", "distribution", "sortOrders", "indexes"):
        if field_name in expected:
            assert table.get(field_name) == expected[field_name], (
                f"The V1 table resource did not preserve {field_name!r}."
            )


@then("the documented provider side effect exists")
def documented_provider_side_effect_exists(cuj_journey: CreateTableJourney) -> None:
    """Uses V1 read-back to verify that the documented lifecycle operation persisted metadata."""
    _loaded_table(cuj_journey)


@then("the fixture cleans up the created table")
def fixture_cleans_up_created_table(cuj_journey: CreateTableJourney) -> None:
    """Requires the V1 item DELETE route to remove a scenario-owned table."""
    if not cuj_journey.created:
        pytest.fail("The scenario cannot clean up because V1 table creation did not succeed.")
    _assert_success(cuj_journey.cleanup())


@then(
    parsers.parse(
        'the API returns the non-retryable "{error_type}" error with status {status:d}'
    )
)
def api_returns_exact_non_retryable_error(
    cuj_journey: CreateTableJourney, error_type: str, status: int
) -> None:
    """Asserts the exact V1 public error selected by a normal negative scenario."""
    expected = _expected_error_for(cuj_journey)
    assert expected == ExpectedApiError(status=status, error_type=error_type, retryable=False), (
        "The feature's asserted public error differs from the provider profile declaration."
    )
    _assert_normal_error(cuj_journey, expected)


@then("the error identifies the invalid request field")
def error_identifies_invalid_request_field(cuj_journey: CreateTableJourney) -> None:
    """Requires a typed field-violation detail in a normal validation error."""
    _assert_error_detail_kind(cuj_journey, "FIELD_VIOLATION")


@then("the error identifies the missing resource")
def error_identifies_missing_resource(cuj_journey: CreateTableJourney) -> None:
    """Requires a typed resource detail in a normal missing-parent error."""
    _assert_error_detail_kind(cuj_journey, "RESOURCE_INFO")


@then("the API returns the documented duplicate-table conflict error")
def documented_duplicate_table_conflict_error(cuj_journey: CreateTableJourney) -> None:
    """Asserts the strict public duplicate-table conflict contract."""
    expected_record = common_error_for("duplicate_table")
    assert expected_record.expected is not None
    _assert_normal_error(cuj_journey, expected_record.expected)
    _assert_error_detail_kind(cuj_journey, "RESOURCE_INFO")


@then("the error is non-retryable")
def error_is_non_retryable(cuj_journey: CreateTableJourney) -> None:
    """Requires a normal error response to carry the non-retryable classification."""
    observed = observe_api_error(_response_or_fail(cuj_journey))
    assert observed.retryable is False, "The public error must be classified as non-retryable."


@then("the documented expected API error is recorded without blocking the suite")
@then("the documented unsupported-operation error is recorded without blocking the suite")
def documented_expected_error_is_recorded(cuj_journey: CreateTableJourney) -> None:
    """Warns for a declared provider limitation without hiding the observed response."""
    record = cuj_journey.expected_error
    if record is None:
        pytest.fail("The expected-error feature selected no provider error declaration.")
    _record_non_blocking_observation(cuj_journey, record)


@then("the documented registration-schema behavior is recorded without blocking the suite")
def documented_registration_schema_behavior_is_recorded(cuj_journey: CreateTableJourney) -> None:
    """Records the intentionally conditional Lance schema-discovery observation."""
    record = DeclaredErrorRecord(
        scenario="lance/registered_table_schema_discovery",
        reason=(
            "Lance registration schema discovery remains a documentation/source observation "
            "until the V1 request contract chooses one behavior."
        ),
        expected=None,
    )
    _record_non_blocking_observation(cuj_journey, record)


def feature_inventory_errors(feature_directory: Path) -> tuple[str, ...]:
    """Returns feature/profile drift errors without executing a CUJ or contacting a server."""
    parser = Parser()
    errors: list[str] = []
    for feature_path in sorted(feature_directory.glob("*.feature")):
        feature = parser.parse(feature_path.read_text(encoding="utf-8"))["feature"]
        for child in feature["children"]:
            scenario = child.get("scenario")
            if scenario is None:
                continue
            for values in _scenario_example_values(scenario):
                _validate_scenario_inventory(feature_path, scenario, values, errors)
    return tuple(errors)


def _scenario_example_values(scenario: Mapping[str, Any]) -> tuple[Mapping[str, str], ...]:
    """Expands Scenario Outline example rows into simple placeholder values."""
    examples = scenario.get("examples", [])
    if not examples:
        return ({},)
    values: list[Mapping[str, str]] = []
    for example in examples:
        header = [cell["value"] for cell in example["tableHeader"]["cells"]]
        for row in example["tableBody"]:
            values.append(
                dict(zip(header, [cell["value"] for cell in row["cells"]], strict=True))
            )
    return tuple(values)


def _validate_scenario_inventory(
    feature_path: Path,
    scenario: Mapping[str, Any],
    values: Mapping[str, str],
    errors: list[str],
) -> None:
    """Validates one expanded Gherkin scenario against provider/error profiles."""
    provider: str | None = None
    for step in scenario["steps"]:
        text = _render_step(step["text"], values)
        location = f"{feature_path.name}: {scenario['name']}"
        configured = _CONFIGURED_PROVIDER_STEP.match(text)
        if configured:
            provider = configured["provider"]
            try:
                profile_for(provider)
            except KeyError as error:
                errors.append(f"{location}: {error}")
            continue
        invalid = _INVALID_USE_CASE_STEP.match(text)
        if invalid:
            _validate_invalid_use_case(location, provider, invalid["use_case"], errors)
            continue
        documented = _DOCUMENTED_USE_CASE_STEP.match(text)
        if documented:
            if provider is None:
                errors.append(f"{location}: documented use case has no configured provider.")
            else:
                try:
                    documented_use_case(provider, documented["use_case"])
                except KeyError as error:
                    errors.append(f"{location}: {error}")
            continue
        expected_error = _EXPECTED_ERROR_STEP.match(text)
        if expected_error:
            _validate_expected_error_step(
                location,
                provider,
                expected_error,
                errors,
            )


def _validate_invalid_use_case(
    location: str, provider: str | None, use_case: str, errors: list[str]
) -> None:
    """Validates a provider-specific invalid input or a cross-provider request error."""
    if provider is not None:
        try:
            documented_use_case(provider, use_case)
            return
        except KeyError:
            pass
    try:
        common_error_for(use_case)
    except KeyError as error:
        errors.append(f"{location}: {error}")


def _validate_expected_error_step(
    location: str,
    provider: str | None,
    expected_error_step: re.Match[str],
    errors: list[str],
) -> None:
    """Checks an exact feature error against its selected provider/common declaration."""
    if provider is None:
        errors.append(f"{location}: public error assertion has no configured provider.")
        return
    expected_type = expected_error_step["error_type"]
    expected_status = int(expected_error_step["status"])
    candidate_names = (
        "missing_metalake",
        "missing_catalog",
        "missing_schema",
        "unauthenticated",
        "permission_denied",
    )
    matching = [
        record.expected
        for name in candidate_names
        if (record := common_error_for(name)).expected is not None
        and record.expected.error_type == expected_type
        and record.expected.status == expected_status
    ]
    if not matching and expected_type == "INVALID_ARGUMENT" and expected_status == 400:
        matching.append(ExpectedApiError(400, "INVALID_ARGUMENT", False))
    if not matching:
        errors.append(
            f"{location}: no public error declaration matches "
            f"HTTP {expected_status} {expected_type}."
        )


def _render_step(text: str, values: Mapping[str, str]) -> str:
    """Renders one Scenario Outline step and reports unresolved placeholders."""
    rendered = text
    for key, value in values.items():
        rendered = rendered.replace(f"<{key}>", value)
    if "<" in rendered or ">" in rendered:
        raise ValueError(f"Unresolved Scenario Outline placeholder in step {text!r}.")
    return rendered
