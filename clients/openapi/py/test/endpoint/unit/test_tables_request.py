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

"""No-server checks for generated table endpoint requests and error models."""

import pytest

from gravitino_client.api.tables_api import TablesApi
from gravitino_client.api_client import ApiClient
from gravitino_client.configuration import Configuration
from gravitino_client.models.not_found_error_response import NotFoundErrorResponse


@pytest.mark.endpoint
def test_get_table_serializes_route_and_standard_json_accept_header() -> None:
    """The generated client targets the route-versioned V1 JSON API."""
    api_client = ApiClient(Configuration(host="http://gravitino.example.test:8090"))
    tables_api = TablesApi(api_client)

    method, url, headers, body, post_params = tables_api._get_table_v1_serialize(
        metalake="lake",
        catalog="catalog",
        var_schema="schema",
        table="table",
        if_none_match='W/"v1"',
        x_request_id="openapi-python-unit-request",
        _request_auth=None,
        _content_type=None,
        _headers=None,
        _host_index=0,
    )

    assert method == "GET"
    assert (
        url
        == "http://gravitino.example.test:8090/api/v1/metalakes/lake/"
        "catalogs/catalog/schemas/schema/tables/table"
    )
    assert headers["Accept"] == "application/json"
    assert headers["If-None-Match"] == 'W/"v1"'
    assert headers["X-Request-Id"] == "openapi-python-unit-request"
    assert body is None
    assert post_params == []


@pytest.mark.endpoint
def test_head_table_serializes_route_and_conditional_headers_without_a_body() -> None:
    """The generated client represents HEAD as a bodyless table route request."""
    api_client = ApiClient(Configuration(host="http://gravitino.example.test:8090"))
    tables_api = TablesApi(api_client)

    method, url, headers, body, post_params = tables_api._head_table_v1_serialize(
        metalake="lake",
        catalog="catalog",
        var_schema="schema",
        table="table",
        if_none_match='W/"v1"',
        x_request_id="openapi-python-unit-head-request",
        _request_auth=None,
        _content_type=None,
        _headers=None,
        _host_index=0,
    )

    assert method == "HEAD"
    assert (
        url
        == "http://gravitino.example.test:8090/api/v1/metalakes/lake/"
        "catalogs/catalog/schemas/schema/tables/table"
    )
    assert headers["If-None-Match"] == 'W/"v1"'
    assert headers["X-Request-Id"] == "openapi-python-unit-head-request"
    assert body is None
    assert post_params == []


@pytest.mark.endpoint
def test_not_found_error_deserializes_non_retryable_boolean() -> None:
    """A conforming public error remains usable as a typed generated model."""
    response = NotFoundErrorResponse.from_dict(
        {
            "error": {
                "code": 404,
                "type": "METALAKE_NOT_FOUND",
                "message": "The requested metalake was not found.",
                "retryable": False,
                "requestId": "openapi-python-unit-error",
                "details": [
                    {
                        "kind": "RESOURCE_INFO",
                        "resourceType": "METALAKE",
                        "resourceName": "metalakes/missing",
                    }
                ],
            }
        }
    )

    assert response is not None
    assert response.error.retryable is False
    detail = response.error.details[0].actual_instance
    assert detail.resource_type == "METALAKE"
    assert detail.resource_name == "metalakes/missing"
