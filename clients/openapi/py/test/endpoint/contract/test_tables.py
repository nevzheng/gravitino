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

"""Real-server V1 contract probes for the generated table client."""

import pytest

from gravitino_client.api.tables_api import TablesApi
from gravitino_client.exceptions import ApiException, NotFoundException
from gravitino_testkit.assertions import assert_public_error
from gravitino_testkit.http import request, request_json


@pytest.mark.endpoint
@pytest.mark.xfail(
    strict=True,
    raises=AssertionError,
    reason=(
        "TODO: The V1 table handler currently translates a missing metalake as "
        "CATALOG_NOT_FOUND; remove this marker when it returns the documented "
        "METALAKE_NOT_FOUND public error."
    ),
)
def test_missing_metalake_returns_typed_not_found_error(tables_api: TablesApi) -> None:
    """A generated client receives the V1 rich public error representation."""
    request_id = "openapi-python-missing-metalake"

    with pytest.raises(NotFoundException) as raised:
        tables_api.get_table_v1(
            metalake="openapi-python-missing-metalake",
            catalog="missing-catalog",
            var_schema="missing-schema",
            table="missing-table",
            x_request_id=request_id,
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
    assert detail.resource_name == "openapi-python-missing-metalake"


@pytest.mark.endpoint
def test_v1_rejects_legacy_vendor_media_type(tables_api: TablesApi) -> None:
    """The route-versioned V1 API does not retain legacy media-type selection."""
    request_id = "openapi-python-legacy-media-type"

    with pytest.raises(ApiException) as raised:
        tables_api.get_table_v1(
            metalake="openapi-python-missing-metalake",
            catalog="missing-catalog",
            var_schema="missing-schema",
            table="missing-table",
            x_request_id=request_id,
            _headers={"Accept": "application/vnd.gravitino.v1+json"},
        )

    assert_public_error(
        raised.value,
        status=406,
        error_type="NOT_ACCEPTABLE",
        retryable=False,
        request_id=request_id,
    )


@pytest.mark.endpoint
def test_v1_legacy_media_type_uses_the_documented_json_error_wire_format(
    gravitino_openapi_base_url: str,
) -> None:
    """Raw HTTP checks protect the public wire contract from SDK behavior changes."""
    request_id = "openapi-python-legacy-media-type-wire"

    response = request_json(
        gravitino_openapi_base_url,
        "GET",
        "/api/v1/metalakes/missing/catalogs/missing/schemas/missing/tables/missing",
        headers={
            "Accept": "application/vnd.gravitino.v1+json",
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
    assert isinstance(error["message"], str)
    assert error["message"]


@pytest.mark.endpoint
@pytest.mark.xfail(
    strict=True,
    raises=AssertionError,
    reason=(
        "TODO: The V1 HEAD error response currently sends "
        "must-revalidate,no-cache,no-store instead of the documented no-store "
        "Cache-Control value; remove this marker when the server matches the contract."
    ),
)
def test_head_missing_table_returns_headers_without_a_message_body(
    gravitino_openapi_base_url: str,
) -> None:
    """A V1 HEAD error preserves observability and cache headers without a body."""
    request_id = "openapi-python-head-missing-table"

    response = request(
        gravitino_openapi_base_url,
        "HEAD",
        "/api/v1/metalakes/missing/catalogs/missing/schemas/missing/tables/missing",
        headers={"X-Request-Id": request_id},
    )

    assert response.status == 404
    assert response.headers["Cache-Control"] == "no-store"
    assert response.headers["X-Request-Id"] == request_id
    assert response.body == b""
