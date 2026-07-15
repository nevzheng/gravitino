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

"""API-only fixtures for OpenAPI customer-journey tests.

These fixtures deliberately use Playwright's APIRequestContext. They never create a browser,
page, or Chromium process.
"""

from collections.abc import Iterator
import os

import pytest
from playwright.sync_api import APIRequestContext, sync_playwright

from cuj.support.transport import ApiRequestSettings, managed_api_request_context


@pytest.fixture(scope="session")
def cuj_api_base_url() -> str:
    """Returns the explicitly configured or local Gravitino API base URL."""
    return os.environ.get("GRAVITINO_OPENAPI_BASE_URL", "http://localhost:8090")


@pytest.fixture(scope="session")
def cuj_api_timeout_ms() -> int:
    """Returns the per-request timeout for customer-journey API calls."""
    raw_timeout = os.environ.get("GRAVITINO_OPENAPI_CUJ_TIMEOUT_MS", "30000")
    try:
        timeout_ms = int(raw_timeout)
    except ValueError as error:
        raise pytest.UsageError(
            "GRAVITINO_OPENAPI_CUJ_TIMEOUT_MS must be a positive integer."
        ) from error
    if timeout_ms <= 0:
        raise pytest.UsageError("GRAVITINO_OPENAPI_CUJ_TIMEOUT_MS must be positive.")
    return timeout_ms


@pytest.fixture(scope="session")
def cuj_api_authorization() -> str | None:
    """Returns an optional standard HTTP Authorization header value for CUJs."""
    authorization = os.environ.get("GRAVITINO_OPENAPI_AUTHORIZATION", "").strip()
    return authorization or None


@pytest.fixture(scope="session")
def cuj_profiles() -> frozenset[str]:
    """Returns the configured comma-separated catalog-provider profiles."""
    configured_profiles = os.environ.get("GRAVITINO_OPENAPI_CUJ_PROFILES", "")
    return frozenset(
        profile.strip().lower()
        for profile in configured_profiles.split(",")
        if profile.strip()
    )


@pytest.fixture
def cuj_api_request_context(
    cuj_api_authorization: str | None,
    cuj_api_base_url: str,
    cuj_api_timeout_ms: int,
) -> Iterator[APIRequestContext]:
    """Creates and disposes an API-only Playwright request context for one scenario."""
    settings = ApiRequestSettings(
        base_url=cuj_api_base_url,
        timeout_ms=cuj_api_timeout_ms,
        authorization=cuj_api_authorization,
    )
    with sync_playwright() as playwright:
        with managed_api_request_context(playwright, settings) as request_context:
            yield request_context
