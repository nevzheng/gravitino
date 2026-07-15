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

"""No-server behavior tests for typed V1 table lifecycle CUJ steps."""

from __future__ import annotations

from copy import deepcopy
from types import SimpleNamespace
from typing import Any

import pytest

from cuj.steps.v1_typed_table import V1TypedTableLifecycle, typed_table_contract_enabled
from cuj.support.v1_typed_table_profiles import typed_table_profile


class FakeResponse:
    """Small Playwright-response-shaped value used to test typed lifecycle semantics."""

    def __init__(
        self, status: int, payload: object, headers: dict[str, str] | None = None
    ) -> None:
        """Creates one response with decoded JSON and optional HTTP headers."""
        self.status = status
        self._payload = deepcopy(payload)
        self.headers = {} if headers is None else dict(headers)

    def json(self) -> object:
        """Returns a safe decoded response body for every assertion path."""
        return deepcopy(self._payload)


class FakeTypedTableContext:
    """In-memory typed V1 route implementation with optional documented limitations."""

    def __init__(self, *, post_error: bool = False, update_error: bool = False) -> None:
        """Initializes a disposable table response model and request recording list."""
        self.post_error = post_error
        self.update_error = update_error
        self.resource: dict[str, Any] | None = None
        self.deleted = False
        self.etag = '"typed-v1-etag-1"'
        self.calls: list[tuple[str, str, object | None, dict[str, str]]] = []

    def post(self, path: str, *, data: object, headers: dict[str, str]) -> FakeResponse:
        """Creates a typed resource or returns the configured public provider error."""
        self.calls.append(("POST", path, deepcopy(data), dict(headers)))
        if self.post_error:
            return FakeResponse(
                501,
                {"error": {"type": "UNSUPPORTED_OPERATION", "retryable": False}},
            )
        assert isinstance(data, dict)
        assert "properties" not in data
        self.resource = deepcopy(data)
        return FakeResponse(201, self.resource, {"ETag": self.etag})

    def get(self, path: str, *, headers: dict[str, str]) -> FakeResponse:
        """Loads the current typed table or its strict public not-found response."""
        self.calls.append(("GET", path, None, dict(headers)))
        if self.resource is None or self.deleted:
            return FakeResponse(
                404,
                {"error": {"type": "TABLE_NOT_FOUND", "retryable": False}},
            )
        return FakeResponse(200, self.resource, {"ETag": self.etag})

    def put(self, path: str, *, data: object, headers: dict[str, str]) -> FakeResponse:
        """Applies a typed full replacement or records a configured connector limitation."""
        self.calls.append(("PUT", path, deepcopy(data), dict(headers)))
        if headers.get("If-Match") != self.etag:
            return FakeResponse(
                412,
                {"error": {"type": "PRECONDITION_FAILED", "retryable": False}},
            )
        if self.update_error:
            return FakeResponse(
                501,
                {"error": {"type": "UNSUPPORTED_OPERATION", "retryable": False}},
            )
        assert isinstance(data, dict)
        assert "properties" not in data
        assert self.resource is not None
        self.resource = {"name": self.resource["name"], **deepcopy(data)}
        self.etag = '"typed-v1-etag-2"'
        return FakeResponse(200, self.resource, {"ETag": self.etag})

    def delete(self, path: str, *, headers: dict[str, str]) -> FakeResponse:
        """Conditionally deletes the disposable table from the fake V1 route."""
        self.calls.append(("DELETE", path, None, dict(headers)))
        if headers.get("If-Match") != self.etag:
            return FakeResponse(
                412,
                {"error": {"type": "PRECONDITION_FAILED", "retryable": False}},
            )
        self.deleted = True
        return FakeResponse(204, None)


def _lifecycle(
    provider: str,
    context: FakeTypedTableContext,
    *,
    table_location: str | None = None,
    existing_dataset_location: str | None = None,
) -> V1TypedTableLifecycle:
    """Builds a fake hierarchy and typed lifecycle without provisioning any real connector."""
    hierarchy = SimpleNamespace(
        names=SimpleNamespace(
            schema_path="/api/v1/metalakes/lake/catalogs/catalog/schemas/schema"
        )
    )
    return V1TypedTableLifecycle(
        hierarchy=hierarchy,
        profile=typed_table_profile(provider),
        request_context=context,  # type: ignore[arg-type]
        table_name=f"typed_{provider}",
        request_id="typed-v1-unit-request",
        table_location=table_location,
        existing_dataset_location=existing_dataset_location,
    )


@pytest.mark.cuj
@pytest.mark.api
@pytest.mark.lifecycle
def test_iceberg_round_trip_uses_only_typed_storage_and_options() -> None:
    """GET-to-PUT preserves the table's typed Iceberg state while replacing only comment metadata."""
    context = FakeTypedTableContext()
    lifecycle = _lifecycle("iceberg", context)

    assert lifecycle.create().status == 201
    lifecycle.verify_get()
    assert lifecycle.update_metadata().status == 200
    lifecycle.verify_updated_metadata()
    assert lifecycle.delete() is not None
    lifecycle.verify_deleted()

    post = context.calls[0]
    put = next(call for call in context.calls if call[0] == "PUT")
    assert post[1] == "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables"
    assert "properties" not in post[2]
    assert post[2]["storage"] == {
        "ownership": "MANAGED",
        "tableFormat": "ICEBERG",
        "fileFormat": "PARQUET",
    }
    assert post[2]["icebergOptions"] == {"formatVersion": 2}
    assert put[2]["storage"] == post[2]["storage"]
    assert put[2]["icebergOptions"] == post[2]["icebergOptions"]
    assert "properties" not in put[2]
    assert put[3]["If-Match"] == '"typed-v1-etag-1"'
    delete = next(call for call in context.calls if call[0] == "DELETE")
    assert delete[3]["If-Match"] == '"typed-v1-etag-2"'


@pytest.mark.cuj
@pytest.mark.expected_error
def test_delta_external_post_and_known_metadata_update_limitation_are_visible_nonblocking() -> None:
    """Delta stays on POST /tables and records its documented unavailable alter operation."""
    context = FakeTypedTableContext(update_error=True)
    lifecycle = _lifecycle(
        "delta",
        context,
        existing_dataset_location="file:///tmp/delta-existing-orders",
    )

    assert lifecycle.create().status == 201
    lifecycle.verify_get()
    with pytest.warns(UserWarning, match="Declared expected API error observed"):
        assert lifecycle.update_metadata().status == 501
    assert lifecycle.updated_body is None
    assert lifecycle.delete() is not None
    lifecycle.verify_deleted()

    post = context.calls[0]
    assert post[2]["storage"] == {
        "ownership": "EXTERNAL",
        "tableFormat": "DELTA",
        "location": "file:///tmp/delta-existing-orders",
    }
    assert "properties" not in post[2]
    assert all(":register" not in path for _, path, _, _ in context.calls)


@pytest.mark.cuj
@pytest.mark.expected_error
@pytest.mark.parametrize("provider", ("paimon", "hudi"))
def test_declared_unavailable_typed_posts_are_nonblocking_provider_errors(
    provider: str,
) -> None:
    """Paimon and Hudi remain declared public-error paths rather than omitted connectors."""
    context = FakeTypedTableContext(post_error=True)
    lifecycle = _lifecycle(provider, context)

    with pytest.warns(UserWarning, match="Declared expected API error observed"):
        response = lifecycle.create()

    assert response.status == 501
    assert not lifecycle.created
    assert context.calls[0][0:2] == (
        "POST",
        "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables",
    )
    assert "properties" not in context.calls[0][2]


@pytest.mark.cuj
def test_missing_external_location_skips_before_any_typed_post() -> None:
    """No live connector run can accidentally create data using an invented storage location."""
    context = FakeTypedTableContext()
    lifecycle = _lifecycle("lance", context)

    with pytest.raises(pytest.skip.Exception, match="table_location"):
        lifecycle.create()
    assert context.calls == []


@pytest.mark.cuj
def test_typed_table_live_cuj_requires_an_explicit_rollout_flag(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Provider profiles remain data-only until a caller opts into deployed typed V1 behavior."""
    monkeypatch.delenv("GRAVITINO_OPENAPI_CUJ_TYPED_TABLE_CONTRACT", raising=False)
    assert not typed_table_contract_enabled()
    monkeypatch.setenv("GRAVITINO_OPENAPI_CUJ_TYPED_TABLE_CONTRACT", "true")
    assert typed_table_contract_enabled()
