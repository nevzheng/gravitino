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

"""No-server coverage for the complete V1 table lifecycle CUJ helper."""

from copy import deepcopy
from types import SimpleNamespace
from typing import Any

import pytest

from cuj.steps.v1_hierarchy import (
    V1TableLifecycle,
    assert_v1_precondition_failed,
    record_v1_unsupported_operation,
)


class FakeResponse:
    """A minimal Playwright-response-shaped value for table lifecycle assertions."""

    def __init__(
        self,
        status: int,
        payload: object,
        headers: dict[str, str] | None = None,
    ) -> None:
        """Creates one response with a status, JSON body, and optional headers."""
        self.status = status
        self._payload = payload
        self.headers = {} if headers is None else dict(headers)

    def json(self) -> object:
        """Returns the configured decoded JSON body."""
        return deepcopy(self._payload)


class FakeTableJourney:
    """An in-memory V1-only table route used to validate raw CUJ transport behavior."""

    collection_path = "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables"
    table_name = "table"
    request_id = "table-lifecycle-unit-request"

    def __init__(self) -> None:
        """Initializes the disposable table and its strong V1 validator."""
        self.request_context = self
        self.created = False
        self.etag = '"table-etag-1"'
        self.calls: list[tuple[str, str, object | None, dict[str, str]]] = []
        self.resource: dict[str, Any] = {
            "name": self.table_name,
            "comment": "created by the table lifecycle CUJ",
            "columns": [
                {
                    "name": "id",
                    "type": {"kind": "LONG", "signed": True},
                    "nullable": False,
                }
            ],
            "properties": {"cuj.owner": "openapi"},
            "partitioning": [],
            "sortOrders": [],
            "indexes": [],
        }

    @property
    def item_path(self) -> str:
        """Returns the route-versioned item path for the fake table."""
        return f"{self.collection_path}/{self.table_name}"

    def submit_documented_use_case(self, use_case: str) -> FakeResponse:
        """Creates the documented minimal table through the intended V1 collection route."""
        assert use_case == "minimal"
        self.calls.append(("POST", self.collection_path, None, {}))
        self.created = True
        return FakeResponse(201, self.resource)

    def load_table(self) -> FakeResponse:
        """Loads the table or returns its strict public V1 not-found response."""
        self.calls.append(("GET", self.item_path, None, self._read_headers()))
        if not self.created:
            return FakeResponse(
                404,
                {"error": {"type": "TABLE_NOT_FOUND", "retryable": False}},
            )
        return FakeResponse(200, self.resource, {"ETag": self.etag})

    def list_tables(self) -> FakeResponse:
        """Lists the table through its V1 collection route."""
        self.calls.append(("GET", self.collection_path, None, self._read_headers()))
        tables = [{"name": self.table_name}] if self.created else []
        return FakeResponse(200, {"tables": tables})

    def put(self, path: str, *, data: object, headers: dict[str, str]) -> FakeResponse:
        """Applies supported metadata or reports the contract's 412/501 outcomes."""
        self.calls.append(("PUT", path, data, headers))
        assert path == self.item_path
        if not self.created:
            return FakeResponse(
                404,
                {"error": {"type": "TABLE_NOT_FOUND", "retryable": False}},
            )
        if headers.get("If-Match") != self.etag:
            return FakeResponse(
                412,
                {"error": {"type": "PRECONDITION_FAILED", "retryable": False}},
            )
        assert isinstance(data, dict)
        assert set(data).issuperset(
            {"comment", "columns", "properties", "partitioning", "sortOrders", "indexes"}
        )
        for field in ("columns", "partitioning", "sortOrders", "indexes"):
            if data[field] != self.resource[field]:
                return FakeResponse(
                    501,
                    {"error": {"type": "UNSUPPORTED_OPERATION", "retryable": False}},
                )
        if data.get("distribution") != self.resource.get("distribution"):
            return FakeResponse(
                501,
                {"error": {"type": "UNSUPPORTED_OPERATION", "retryable": False}},
            )
        self.resource["comment"] = data["comment"]
        self.resource["properties"] = dict(data["properties"])
        self.etag = '"table-etag-2"'
        return FakeResponse(200, self.resource, {"ETag": self.etag})

    def delete(self, path: str, *, headers: dict[str, str]) -> FakeResponse:
        """Conditionally deletes the fake table through the intended V1 item route."""
        self.calls.append(("DELETE", path, None, headers))
        assert path == self.item_path
        if headers.get("If-Match") != self.etag:
            return FakeResponse(
                412,
                {"error": {"type": "PRECONDITION_FAILED", "retryable": False}},
            )
        self.created = False
        return FakeResponse(204, None)

    def _read_headers(self) -> dict[str, str]:
        """Returns the ordinary V1 read headers emitted by the fake journey."""
        return {"Accept": "application/json", "X-Request-Id": self.request_id}


@pytest.mark.cuj
@pytest.mark.api
@pytest.mark.lifecycle
@pytest.mark.expected_error
def test_v1_table_lifecycle_uses_full_conditional_put_and_visible_capability_outcomes() -> None:
    """The table CUJ proves supported and explicitly unsupported V1 lifecycle behavior."""
    journey = FakeTableJourney()
    lifecycle = V1TableLifecycle(SimpleNamespace(schema_created=True), journey)

    lifecycle.create()
    lifecycle.verify_get()
    lifecycle.verify_list()
    lifecycle.update_metadata()
    lifecycle.verify_updated_metadata()

    stale = lifecycle.attempt_precondition_failure("stale")
    missing = lifecycle.attempt_precondition_failure("missing")
    assert_v1_precondition_failed(stale, journey.item_path)
    assert_v1_precondition_failed(missing, journey.item_path)

    structural = lifecycle.attempt_structural_update()
    with pytest.warns(UserWarning, match="Declared expected API error observed"):
        record_v1_unsupported_operation(
            structural,
            reason="Table structural replacement remains unavailable.",
        )

    lifecycle.delete()
    lifecycle.verify_deleted()

    assert all(path.startswith("/api/v1/") for _, path, _, _ in journey.calls)
    assert all("/api/metalakes" not in path for _, path, _, _ in journey.calls)
    put_headers = [headers for method, _, _, headers in journey.calls if method == "PUT"]
    assert put_headers == [
        {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-Request-Id": "table-lifecycle-unit-request",
            "If-Match": '"table-etag-1"',
        },
        {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-Request-Id": "table-lifecycle-unit-request",
            "If-Match": '"cuj-stale-validator"',
        },
        {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-Request-Id": "table-lifecycle-unit-request",
        },
        {
            "Accept": "application/json",
            "Content-Type": "application/json",
            "X-Request-Id": "table-lifecycle-unit-request",
            "If-Match": '"table-etag-2"',
        },
    ]
    delete_headers = [headers for method, _, _, headers in journey.calls if method == "DELETE"]
    assert delete_headers == [
        {
            "Accept": "application/json",
            "X-Request-Id": "table-lifecycle-unit-request",
            "If-Match": '"table-etag-2"',
        }
    ]
