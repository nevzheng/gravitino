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

"""Configuration-independent V1 contract probes for generated parent clients."""

from collections.abc import Callable
from typing import Any

import pytest

from gravitino_client.api.catalogs_api import CatalogsApi
from gravitino_client.api.metalakes_api import MetalakesApi
from gravitino_client.api.schemas_api import SchemasApi
from gravitino_client.exceptions import ApiException, NotFoundException
from gravitino_testkit.assertions import assert_public_error
from gravitino_testkit.http import request_json


LEGACY_VENDOR_MEDIA_TYPE = "application/vnd.gravitino.v1+json"


def get_metalake_with_legacy_accept(api: MetalakesApi, request_id: str) -> None:
    """Sends one media-negotiation probe through the generated metalakes client."""
    api.get_metalake_v1(
        metalake="openapi-python-missing-metalake",
        x_request_id=request_id,
        _headers={"Accept": LEGACY_VENDOR_MEDIA_TYPE},
    )


def get_catalog_with_legacy_accept(api: CatalogsApi, request_id: str) -> None:
    """Sends one media-negotiation probe through the generated catalogs client."""
    api.get_catalog_v1(
        metalake="openapi-python-missing-metalake",
        catalog="missing-catalog",
        x_request_id=request_id,
        _headers={"Accept": LEGACY_VENDOR_MEDIA_TYPE},
    )


def get_schema_with_legacy_accept(api: SchemasApi, request_id: str) -> None:
    """Sends one media-negotiation probe through the generated schemas client."""
    api.get_schema_v1(
        metalake="openapi-python-missing-metalake",
        catalog="missing-catalog",
        var_schema="missing-schema",
        x_request_id=request_id,
        _headers={"Accept": LEGACY_VENDOR_MEDIA_TYPE},
    )


@pytest.mark.endpoint
def test_missing_metalake_uses_the_generated_public_not_found_model(
    metalakes_api: MetalakesApi,
) -> None:
    """A no-configuration GET receives the documented V1 metalake not-found error."""
    request_id = "openapi-python-parent-missing-metalake"

    with pytest.raises(NotFoundException) as raised:
        metalakes_api.get_metalake_v1(
            metalake="openapi-python-missing-metalake", x_request_id=request_id
        )

    error = assert_public_error(
        raised.value,
        status=404,
        error_type="METALAKE_NOT_FOUND",
        retryable=False,
        request_id=request_id,
    )
    detail = error.details[0].actual_instance
    assert detail.resource_type == "METALAKE"
    assert detail.resource_name == "metalakes/openapi-python-missing-metalake"


@pytest.mark.endpoint
@pytest.mark.xfail(
    strict=True,
    raises=AssertionError,
    reason=(
        "TODO: parent V1 operations omit documented 406 NotAcceptable response refs; "
        "remove this marker when their OpenAPI responses list NotAcceptable."
    ),
)
@pytest.mark.parametrize(
    ("api_fixture", "generated_request"),
    (
        ("metalakes_api", get_metalake_with_legacy_accept),
        ("catalogs_api", get_catalog_with_legacy_accept),
        ("schemas_api", get_schema_with_legacy_accept),
    ),
    ids=("metalakes", "catalogs", "schemas"),
)
def test_parent_v1_apis_reject_legacy_vendor_media_types_before_resource_lookup(
    request: pytest.FixtureRequest,
    api_fixture: str,
    generated_request: Callable[[Any, str], None],
) -> None:
    """Every generated parent API gets the same public V1 406 without provider setup."""
    request_id = f"openapi-python-{api_fixture}-legacy-media"
    api = request.getfixturevalue(api_fixture)

    with pytest.raises(ApiException) as raised:
        generated_request(api, request_id)

    error = assert_public_error(
        raised.value,
        status=406,
        error_type="NOT_ACCEPTABLE",
        retryable=False,
        request_id=request_id,
    )
    assert error.details == []


@pytest.mark.endpoint
@pytest.mark.parametrize(
    ("path", "request_id"),
    (
        (
            "/api/v1/metalakes/openapi-python-missing-metalake",
            "openapi-python-metalakes-legacy-media-wire",
        ),
        (
            "/api/v1/metalakes/openapi-python-missing-metalake/catalogs/missing-catalog",
            "openapi-python-catalogs-legacy-media-wire",
        ),
        (
            "/api/v1/metalakes/openapi-python-missing-metalake/catalogs/missing-catalog/"
            "schemas/missing-schema",
            "openapi-python-schemas-legacy-media-wire",
        ),
    ),
    ids=("metalakes", "catalogs", "schemas"),
)
def test_parent_v1_apis_reject_legacy_vendor_media_types_on_the_wire(
    gravitino_openapi_base_url: str,
    path: str,
    request_id: str,
) -> None:
    """Parent routes return the public V1 406 envelope before resource lookup."""
    response = request_json(
        gravitino_openapi_base_url,
        "GET",
        path,
        headers={
            "Accept": LEGACY_VENDOR_MEDIA_TYPE,
            "X-Request-Id": request_id,
        },
    )

    assert response.status == 406
    assert response.headers["Content-Type"].split(";", maxsplit=1)[0] == "application/json"
    assert response.headers["Cache-Control"] == "no-store"
    assert response.headers["X-Request-Id"] == request_id
    error = response.body["error"]
    assert error["code"] == 406
    assert error["type"] == "NOT_ACCEPTABLE"
    assert error["retryable"] is False
    assert error["requestId"] == request_id
    assert error["details"] == []
