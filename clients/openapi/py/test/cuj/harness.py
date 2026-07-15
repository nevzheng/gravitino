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

"""Execution harness for the V1 create-table customer journey.

The harness owns the raw V1 transport (a Playwright ``APIRequestContext``) used
to drive a create journey and inspect the exact status, headers, and public
error envelope. It uses the generated ``gravitino_client`` for typed V1
read-back operations that are already present in the contract.

The collection ``POST``/``GET`` and item ``DELETE`` calls intentionally target
their V1 routes. A configured CUJ must fail if that target API has not been
implemented yet; it must never silently exercise the legacy API as a proxy.
"""

from dataclasses import dataclass, field
from typing import TYPE_CHECKING, Any

from gravitino_client.api.tables_api import TablesApi
from gravitino_client.api_client import ApiClient
from gravitino_client.configuration import Configuration

from .payloads import build_create_payload, build_invalid_payload
from .profiles import ProviderEnvironment

if TYPE_CHECKING:  # pragma: no cover - typing only, avoids a hard import
    from playwright.sync_api import APIRequestContext


@dataclass(frozen=True)
class ApiOutcome:
    """A raw HTTP outcome captured without generated-client deserialization."""

    status: int
    headers: dict[str, str]
    json: Any
    text: str

    def request_id(self) -> str | None:
        """Returns the response's request id, if the server echoed one."""
        return self.headers.get("x-request-id")


@dataclass
class CujHarness:
    """Drives one create-table scenario against a configured environment."""

    environment: ProviderEnvironment
    request_context: "APIRequestContext"
    request_id: str
    table_name: str | None = None
    _last_payload: dict[str, object] | None = field(default=None, init=False)
    _created_tables: list[str] = field(default_factory=list, init=False)

    # --- transport helpers ------------------------------------------------

    def _tables_collection_path(self) -> str:
        env = self.environment
        return (
            f"/api/v1/metalakes/{env.metalake}"
            f"/catalogs/{env.catalog}"
            f"/schemas/{env.var_schema}/tables"
        )

    def _headers(self) -> dict[str, str]:
        headers = {
            "Content-Type": "application/json",
            "Accept": "application/json",
            "X-Request-Id": self.request_id,
        }
        if self.environment.token:
            headers["Authorization"] = f"Bearer {self.environment.token}"
        return headers

    def _capture(self, response: Any) -> ApiOutcome:
        headers = {k.lower(): v for k, v in response.headers.items()}
        text = response.text()
        try:
            body = response.json()
        except Exception:  # noqa: BLE001 - non-JSON bodies are still observable
            body = None
        return ApiOutcome(
            status=response.status, headers=headers, json=body, text=text
        )

    # --- journey actions --------------------------------------------------

    def submit_create(self, use_case: str) -> ApiOutcome:
        """Submits a documented positive create-table request."""
        assert self.table_name is not None, "a unique table name must be set first"
        payload = build_create_payload(use_case, self.table_name)
        self._last_payload = payload
        outcome = self._post(payload)
        if 200 <= outcome.status < 300:
            self._created_tables.append(self.table_name)
        return outcome

    def submit_invalid(self, use_case: str) -> ApiOutcome:
        """Submits a documented invalid create-table request."""
        name = self.table_name or "invalid_request_table"
        payload = build_invalid_payload(use_case, name)
        self._last_payload = payload
        return self._post(payload)

    def resubmit_last(self) -> ApiOutcome:
        """Re-submits the most recently submitted create-table request."""
        assert self._last_payload is not None, "no prior create request to resubmit"
        return self._post(self._last_payload)

    def _post(self, payload: dict[str, object]) -> ApiOutcome:
        response = self.request_context.post(
            self._tables_collection_path(),
            data=payload,
            headers=self._headers(),
        )
        return self._capture(response)

    def load_table_via_client(self) -> Any:
        """Reads the created table back through the generated V1 client."""
        assert self.table_name is not None, "a unique table name must be set first"
        configuration = Configuration(host=self.environment.base_url)
        if self.environment.token:
            configuration.access_token = self.environment.token
        with ApiClient(configuration) as api_client:
            tables_api = TablesApi(api_client)
            return tables_api.get_table_v1(
                metalake=self.environment.metalake,
                catalog=self.environment.catalog,
                var_schema=self.environment.var_schema,
                table=self.table_name,
                x_request_id=self.request_id,
            )

    def list_tables(self) -> ApiOutcome:
        """Lists tables in the configured schema through the intended V1 route."""
        response = self.request_context.get(
            self._tables_collection_path(), headers=self._headers()
        )
        return self._capture(response)

    def cleanup(self) -> None:
        """Deletes every table this scenario created, best effort."""
        while self._created_tables:
            name = self._created_tables.pop()
            try:
                self.request_context.delete(
                    f"{self._tables_collection_path()}/{name}",
                    headers=self._headers(),
                )
            except Exception:  # noqa: BLE001 - cleanup must not mask a result
                pass
