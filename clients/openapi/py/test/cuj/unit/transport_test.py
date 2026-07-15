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

"""No-server checks for the API-only customer-journey transport."""

from typing import Any

import pytest

from cuj.support.transport import (
    ApiContractDivergenceWarning,
    ApiRequestSettings,
    DeclaredApiLimitationWarning,
    ExpectedApiError,
    api_request_context_options,
    assert_expected_api_error,
    managed_api_request_context,
    record_non_blocking_expected_error,
    warn_on_declared_error_divergence,
)


class FakeResponse:
    """A minimal API response fake that does not contact a server."""

    def __init__(self, status: int, payload: object) -> None:
        """Creates a fake response with an HTTP status and JSON payload."""
        self.status = status
        self._payload = payload

    def json(self) -> object:
        """Returns the configured JSON payload."""
        return self._payload


class FakeRequestContext:
    """Records disposal by the API request context lifecycle helper."""

    def __init__(self) -> None:
        """Initializes an undisposed fake request context."""
        self.disposed = False

    def dispose(self) -> None:
        """Records context disposal."""
        self.disposed = True


class FakeRequestFactory:
    """Creates a fake standalone API request context."""

    def __init__(self) -> None:
        """Initializes a factory with no captured options."""
        self.options: dict[str, object] | None = None
        self.context = FakeRequestContext()

    def new_context(self, **options: object) -> FakeRequestContext:
        """Records request-context options without creating a browser."""
        self.options = options
        return self.context


class FakePlaywright:
    """Provides only Playwright's API request factory for unit testing."""

    def __init__(self) -> None:
        """Initializes a fake Playwright API transport."""
        self.request = FakeRequestFactory()


def public_error(status: int, error_type: str, retryable: bool) -> dict[str, Any]:
    """Builds a minimum conforming Gravitino public-error response body."""
    return {
        "error": {
            "code": status,
            "type": error_type,
            "message": "The request could not be completed.",
            "retryable": retryable,
            "requestId": "cuj-transport-unit-test",
        }
    }


@pytest.mark.cuj
@pytest.mark.api
def test_api_request_context_options_use_standard_json_and_preserve_authorization() -> None:
    """The standalone context uses portable JSON and standard HTTP authorization."""
    options = api_request_context_options(
        ApiRequestSettings(
            base_url="https://gravitino.example.test:8090/",
            timeout_ms=12_345,
            authorization="Bearer test-token",
        )
    )

    assert options == {
        "base_url": "https://gravitino.example.test:8090",
        "extra_http_headers": {
            "Accept": "application/json",
            "Authorization": "Bearer test-token",
        },
        "fail_on_status_code": False,
        "timeout": 12_345,
    }


@pytest.mark.cuj
@pytest.mark.api
def test_managed_api_request_context_disposes_the_standalone_context() -> None:
    """The lifecycle helper uses only API request transport and always disposes it."""
    playwright = FakePlaywright()
    settings = ApiRequestSettings(base_url="http://gravitino.example.test:8090")

    with managed_api_request_context(playwright, settings) as request_context:
        assert request_context is playwright.request.context
        assert playwright.request.options is not None
        assert request_context.disposed is False

    assert playwright.request.context.disposed is True


@pytest.mark.cuj
@pytest.mark.expected_error
def test_normal_negative_scenario_asserts_the_documented_public_error() -> None:
    """Expected API errors validate status, stable error type, and retryability."""
    expected = ExpectedApiError(404, "TABLE_NOT_FOUND", retryable=False)
    response = FakeResponse(404, public_error(404, "TABLE_NOT_FOUND", retryable=False))

    observed = assert_expected_api_error(response, expected)

    assert observed.status == 404
    assert observed.error_type == "TABLE_NOT_FOUND"
    assert observed.retryable is False


@pytest.mark.cuj
@pytest.mark.expected_error
def test_declared_error_divergence_warns_without_blocking_the_cuj() -> None:
    """Known implementation gaps remain visible without hiding the expected API contract."""
    expected = ExpectedApiError(404, "METALAKE_NOT_FOUND", retryable=False)
    response = FakeResponse(404, public_error(404, "CATALOG_NOT_FOUND", retryable=False))

    with pytest.warns(ApiContractDivergenceWarning, match="Declared API divergence"):
        observed = warn_on_declared_error_divergence(
            response,
            expected,
            reason="The legacy exception mapper flattens missing-parent errors.",
        )

    assert observed.error_type == "CATALOG_NOT_FOUND"


@pytest.mark.cuj
@pytest.mark.expected_error
def test_declared_expected_error_is_visible_without_blocking_the_cuj() -> None:
    """Known provider limitations remain visible even when their target error occurs."""
    expected = ExpectedApiError(501, "UNSUPPORTED_OPERATION", retryable=False)
    response = FakeResponse(
        501, public_error(501, "UNSUPPORTED_OPERATION", retryable=False)
    )

    with pytest.warns(DeclaredApiLimitationWarning, match="Declared expected API error observed"):
        observed = record_non_blocking_expected_error(
            response,
            expected,
            reason="The connector does not support this documented operation.",
        )

    assert observed.error_type == "UNSUPPORTED_OPERATION"
