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

"""API-only Playwright transport and public-error expectation helpers."""

from collections.abc import Iterator, Mapping
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Any, Protocol
from urllib.parse import urlparse
import warnings

from playwright.sync_api import APIRequestContext, Playwright


@dataclass(frozen=True)
class ApiRequestSettings:
    """Configures one standalone Playwright API request context."""

    base_url: str
    timeout_ms: int = 30_000
    authorization: str | None = None

    def __post_init__(self) -> None:
        """Validates settings before any transport process is started."""
        parsed_base_url = urlparse(self.base_url.strip())
        if parsed_base_url.scheme not in {"http", "https"} or not parsed_base_url.netloc:
            raise ValueError("base_url must be an absolute HTTP(S) URL.")
        if self.timeout_ms <= 0:
            raise ValueError("timeout_ms must be positive.")


@dataclass(frozen=True)
class ExpectedApiError:
    """The public API error a negative scenario is expected to receive."""

    status: int
    error_type: str
    retryable: bool

    def __post_init__(self) -> None:
        """Validates that the expectation describes an HTTP error response."""
        if not 400 <= self.status <= 599:
            raise ValueError("status must be an HTTP error status between 400 and 599.")
        if not self.error_type:
            raise ValueError("error_type must not be empty.")


@dataclass(frozen=True)
class ObservedApiError:
    """The error fields observed in an API response."""

    status: int
    error_type: str | None
    retryable: bool | None


class ApiResponse(Protocol):
    """The subset of a Playwright API response needed by error assertions."""

    @property
    def status(self) -> int:
        """Returns the HTTP status code."""

    def json(self) -> Any:
        """Decodes the response body as JSON."""


class DeclaredApiLimitationWarning(UserWarning):
    """Signals a known, non-blocking API limitation observed by a CUJ."""


class ApiContractDivergenceWarning(DeclaredApiLimitationWarning):
    """Signals a declared limitation whose observed API outcome has diverged."""


def api_request_context_options(settings: ApiRequestSettings) -> dict[str, object]:
    """Builds Playwright options for a standard JSON, non-browser API context."""
    headers = {"Accept": "application/json"}
    if settings.authorization is not None:
        headers["Authorization"] = settings.authorization

    return {
        "base_url": settings.base_url.strip().rstrip("/"),
        "extra_http_headers": headers,
        "fail_on_status_code": False,
        "timeout": settings.timeout_ms,
    }


@contextmanager
def managed_api_request_context(
    playwright: Playwright, settings: ApiRequestSettings
) -> Iterator[APIRequestContext]:
    """Creates and disposes a standalone Playwright API request context."""
    request_context = playwright.request.new_context(**api_request_context_options(settings))
    try:
        yield request_context
    finally:
        request_context.dispose()


def observe_api_error(response: ApiResponse) -> ObservedApiError:
    """Extracts the public error fields without asserting conformance."""
    try:
        payload = response.json()
    except Exception:
        payload = None

    error: Mapping[str, Any] | None = None
    if isinstance(payload, Mapping):
        candidate = payload.get("error")
        if isinstance(candidate, Mapping):
            error = candidate

    return ObservedApiError(
        status=response.status,
        error_type=error.get("type") if error is not None else None,
        retryable=error.get("retryable") if error is not None else None,
    )


def assert_expected_api_error(
    response: ApiResponse, expected: ExpectedApiError
) -> ObservedApiError:
    """Asserts a normal negative scenario receives its documented public API error."""
    observed = observe_api_error(response)
    assert observed.status == expected.status, (
        f"Expected HTTP {expected.status}, received HTTP {observed.status}."
    )
    assert observed.error_type == expected.error_type, (
        f"Expected API error type {expected.error_type!r}, "
        f"received {observed.error_type!r}."
    )
    assert observed.retryable is expected.retryable, (
        f"Expected API error retryable={expected.retryable!r}, "
        f"received {observed.retryable!r}."
    )
    return observed


def record_non_blocking_expected_error(
    response: ApiResponse, expected: ExpectedApiError, *, reason: str
) -> ObservedApiError:
    """Records a known error outcome as a visible, non-blocking CUJ warning.

    Expected provider limitations are intentionally neither ``xfail`` nor
    passing silently. The expected public error, a different error, and a
    newly successful request are all useful observations while the V1 surface
    is being implemented, so each emits a warning and lets the suite continue.
    Normal negative contract scenarios instead use :func:`assert_expected_api_error`.
    """
    observed = observe_api_error(response)
    expected_values = (expected.status, expected.error_type, expected.retryable)
    observed_values = (observed.status, observed.error_type, observed.retryable)
    if observed_values == expected_values:
        warnings.warn(
            "Declared expected API error observed: "
            f"{reason}. Received status/type/retryable {observed_values!r}. "
            "This known limitation is non-blocking.",
            DeclaredApiLimitationWarning,
            stacklevel=2,
        )
    elif 200 <= observed.status <= 299:
        warnings.warn(
            "Declared expected API error was not observed because the request succeeded: "
            f"{reason}. Expected status/type/retryable {expected_values!r}; "
            f"received {observed_values!r}. Update the provider profile if this is now supported.",
            DeclaredApiLimitationWarning,
            stacklevel=2,
        )
    else:
        warnings.warn(
            "Declared API divergence: "
            f"{reason}. Expected status/type/retryable {expected_values!r}; "
            f"received {observed_values!r}.",
            ApiContractDivergenceWarning,
            stacklevel=2,
        )
    return observed


def warn_on_declared_error_divergence(
    response: ApiResponse, expected: ExpectedApiError, *, reason: str
) -> ObservedApiError:
    """Backward-compatible name for recording a non-blocking expected error."""
    return record_non_blocking_expected_error(response, expected, reason=reason)
