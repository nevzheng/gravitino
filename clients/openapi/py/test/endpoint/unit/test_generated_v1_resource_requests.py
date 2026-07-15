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

"""No-server request serialization coverage for generated V1 resource APIs."""

import pytest

from gravitino_client.api.catalogs_api import CatalogsApi
from gravitino_client.api.metalakes_api import MetalakesApi
from gravitino_client.api.schemas_api import SchemasApi
from gravitino_client.api.tables_api import TablesApi
from gravitino_client.api_client import ApiClient
from gravitino_client.configuration import Configuration
from gravitino_client.models.catalog_create_request import CatalogCreateRequest
from gravitino_client.models.catalog_type import CatalogType
from gravitino_client.models.catalog_update_request import CatalogUpdateRequest
from gravitino_client.models.data_type import DataType
from gravitino_client.models.integral_data_type import IntegralDataType
from gravitino_client.models.metalake_create_request import MetalakeCreateRequest
from gravitino_client.models.metalake_update_request import MetalakeUpdateRequest
from gravitino_client.models.schema_create_request import SchemaCreateRequest
from gravitino_client.models.schema_update_request import SchemaUpdateRequest
from gravitino_client.models.table_column import TableColumn
from gravitino_client.models.table_create_request import TableCreateRequest
from gravitino_client.models.table_update_request import TableUpdateRequest


HOST = "http://gravitino.example.test:8090"
REQUEST_ID = "generated-v1-unit"
ETAG = '"v1-resource"'
METALAKE = "lake"
CATALOG = "catalog"
SCHEMA = "schema"
TABLE = "orders"
JSON_MEDIA_TYPE = "application/json"


def serializer_options() -> dict[str, object]:
    """Returns generator-only parameters required by private serializer methods."""
    return {
        "_request_auth": None,
        "_content_type": None,
        "_headers": None,
        "_host_index": 0,
    }


def assert_json_mutation(
    serialized: tuple[object, object, object, object, object],
    *,
    method: str,
    path: str,
    body: dict[str, object],
    if_match: str | None = None,
) -> None:
    """Asserts a generated JSON mutation request without opening a connection."""
    actual_method, url, headers, actual_body, post_params = serialized
    expected_headers = {
        "Accept": JSON_MEDIA_TYPE,
        "Content-Type": JSON_MEDIA_TYPE,
        "X-Request-Id": REQUEST_ID,
    }
    if if_match is not None:
        expected_headers["If-Match"] = if_match

    assert actual_method == method
    assert url == f"{HOST}{path}"
    assert_generated_headers(headers, expected_headers)
    assert actual_body == body
    assert post_params == []


def assert_read_request(
    serialized: tuple[object, object, object, object, object],
    *,
    method: str,
    path: str,
    if_none_match: str | None = None,
) -> None:
    """Asserts a generated bodyless read request using portable JSON negotiation."""
    actual_method, url, headers, body, post_params = serialized
    expected_headers = {"Accept": JSON_MEDIA_TYPE, "X-Request-Id": REQUEST_ID}
    if if_none_match is not None:
        expected_headers["If-None-Match"] = if_none_match

    assert actual_method == method
    assert url == f"{HOST}{path}"
    assert_generated_headers(headers, expected_headers)
    assert body is None
    assert post_params == []


def assert_conditional_delete(
    serialized: tuple[object, object, object, object, object],
    *,
    path: str,
) -> None:
    """Asserts a generated bodyless delete that carries exactly one strong ETag."""
    actual_method, url, headers, body, post_params = serialized
    assert actual_method == "DELETE"
    assert url == f"{HOST}{path}"
    assert_generated_headers(
        headers,
        {
            "Accept": JSON_MEDIA_TYPE,
            "If-Match": ETAG,
            "X-Request-Id": REQUEST_ID,
        },
    )
    assert body is None
    assert post_params == []


def api_client() -> ApiClient:
    """Creates an unconnected generated client with a deterministic base URL."""
    return ApiClient(Configuration(host=HOST))


def assert_generated_headers(headers: dict[str, str], expected: dict[str, str]) -> None:
    """Checks V1 contract headers while allowing the generated client user agent."""
    assert headers["User-Agent"].startswith("OpenAPI-Generator/")
    assert {key: value for key, value in headers.items() if key != "User-Agent"} == expected


@pytest.mark.endpoint
def test_generated_metalakes_api_serializes_full_crud_contract() -> None:
    """The generated top-level API uses V1 paths, JSON, and conditional mutations."""
    api = MetalakesApi(api_client())
    create = MetalakeCreateRequest(
        name=METALAKE, comment="test metalake", properties={"owner": "platform"}
    )
    update = MetalakeUpdateRequest(comment=None, properties={"owner": "api"})

    assert_json_mutation(
        api._create_metalake_v1_serialize(
            metalake_create_request=create,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        method="POST",
        path="/api/v1/metalakes",
        body={"name": METALAKE, "comment": "test metalake", "properties": {"owner": "platform"}},
    )
    assert_read_request(
        api._list_metalakes_v1_serialize(x_request_id=REQUEST_ID, **serializer_options()),
        method="GET",
        path="/api/v1/metalakes",
    )
    assert_read_request(
        api._get_metalake_v1_serialize(
            metalake=METALAKE, x_request_id=REQUEST_ID, **serializer_options()
        ),
        method="GET",
        path="/api/v1/metalakes/lake",
    )
    assert_json_mutation(
        api._update_metalake_v1_serialize(
            if_match=ETAG,
            metalake=METALAKE,
            metalake_update_request=update,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        method="PUT",
        path="/api/v1/metalakes/lake",
        body={"comment": None, "properties": {"owner": "api"}},
        if_match=ETAG,
    )
    assert_conditional_delete(
        api._delete_metalake_v1_serialize(
            if_match=ETAG,
            metalake=METALAKE,
            force=None,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        path="/api/v1/metalakes/lake",
    )


@pytest.mark.endpoint
def test_generated_catalogs_api_serializes_full_crud_contract() -> None:
    """The generated nested catalog API preserves immutable creation-only fields."""
    api = CatalogsApi(api_client())
    create = CatalogCreateRequest(
        name=CATALOG,
        type=CatalogType.RELATIONAL,
        provider="jdbc-mysql",
        comment="test catalog",
        properties={"uri": "jdbc:mysql://example.test:3306"},
    )
    update = CatalogUpdateRequest(comment="updated catalog", properties={"owner": "api"})

    assert_json_mutation(
        api._create_catalog_v1_serialize(
            metalake=METALAKE,
            catalog_create_request=create,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        method="POST",
        path="/api/v1/metalakes/lake/catalogs",
        body={
            "name": CATALOG,
            "type": "RELATIONAL",
            "provider": "jdbc-mysql",
            "comment": "test catalog",
            "properties": {"uri": "jdbc:mysql://example.test:3306"},
        },
    )
    assert_read_request(
        api._list_catalogs_v1_serialize(
            metalake=METALAKE, x_request_id=REQUEST_ID, **serializer_options()
        ),
        method="GET",
        path="/api/v1/metalakes/lake/catalogs",
    )
    assert_read_request(
        api._get_catalog_v1_serialize(
            metalake=METALAKE,
            catalog=CATALOG,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        method="GET",
        path="/api/v1/metalakes/lake/catalogs/catalog",
    )
    assert_json_mutation(
        api._update_catalog_v1_serialize(
            if_match=ETAG,
            metalake=METALAKE,
            catalog=CATALOG,
            catalog_update_request=update,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        method="PUT",
        path="/api/v1/metalakes/lake/catalogs/catalog",
        body={"comment": "updated catalog", "properties": {"owner": "api"}},
        if_match=ETAG,
    )
    assert_conditional_delete(
        api._delete_catalog_v1_serialize(
            if_match=ETAG,
            metalake=METALAKE,
            catalog=CATALOG,
            force=None,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        path="/api/v1/metalakes/lake/catalogs/catalog",
    )


@pytest.mark.endpoint
def test_generated_schemas_api_serializes_full_crud_contract() -> None:
    """The generated schema API keeps each parent identifier in the route hierarchy."""
    api = SchemasApi(api_client())
    create = SchemaCreateRequest(
        name=SCHEMA, comment="test schema", properties={"location": "file:///tmp/v1"}
    )
    update = SchemaUpdateRequest(comment=None, properties={"owner": "api"})

    assert_json_mutation(
        api._create_schema_v1_serialize(
            metalake=METALAKE,
            catalog=CATALOG,
            schema_create_request=create,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        method="POST",
        path="/api/v1/metalakes/lake/catalogs/catalog/schemas",
        body={
            "name": SCHEMA,
            "comment": "test schema",
            "properties": {"location": "file:///tmp/v1"},
        },
    )
    assert_read_request(
        api._list_schemas_v1_serialize(
            metalake=METALAKE,
            catalog=CATALOG,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        method="GET",
        path="/api/v1/metalakes/lake/catalogs/catalog/schemas",
    )
    assert_read_request(
        api._get_schema_v1_serialize(
            metalake=METALAKE,
            catalog=CATALOG,
            var_schema=SCHEMA,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        method="GET",
        path="/api/v1/metalakes/lake/catalogs/catalog/schemas/schema",
    )
    assert_json_mutation(
        api._update_schema_v1_serialize(
            if_match=ETAG,
            metalake=METALAKE,
            catalog=CATALOG,
            var_schema=SCHEMA,
            schema_update_request=update,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        method="PUT",
        path="/api/v1/metalakes/lake/catalogs/catalog/schemas/schema",
        body={"comment": None, "properties": {"owner": "api"}},
        if_match=ETAG,
    )
    assert_conditional_delete(
        api._delete_schema_v1_serialize(
            if_match=ETAG,
            metalake=METALAKE,
            catalog=CATALOG,
            var_schema=SCHEMA,
            cascade=None,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        path="/api/v1/metalakes/lake/catalogs/catalog/schemas/schema",
    )


@pytest.mark.endpoint
def test_generated_tables_api_serializes_full_crud_contract() -> None:
    """The generated table API preserves typed columns and complete PUT state."""
    api = TablesApi(api_client())
    column = TableColumn(
        name="id",
        type=DataType(IntegralDataType(kind="LONG", signed=True)),
        nullable=False,
        autoIncrement=False,
    )
    create = TableCreateRequest(
        name=TABLE,
        comment="test table",
        columns=[column],
        properties={"format": "iceberg"},
        partitioning=[],
        sortOrders=[],
        indexes=[],
    )
    update = TableUpdateRequest(
        comment=None,
        columns=[column],
        properties={"format": "iceberg"},
        partitioning=[],
        sortOrders=[],
        indexes=[],
    )
    table_body = {
        "columns": [
            {
                "name": "id",
                "type": {"kind": "LONG", "signed": True},
                "nullable": False,
                "autoIncrement": False,
            }
        ],
        "properties": {"format": "iceberg"},
        "partitioning": [],
        "sortOrders": [],
        "indexes": [],
    }

    assert_json_mutation(
        api._create_table_v1_serialize(
            metalake=METALAKE,
            catalog=CATALOG,
            var_schema=SCHEMA,
            table_create_request=create,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        method="POST",
        path="/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables",
        body={"name": TABLE, "comment": "test table", **table_body},
    )
    assert_read_request(
        api._list_tables_v1_serialize(
            metalake=METALAKE,
            catalog=CATALOG,
            var_schema=SCHEMA,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        method="GET",
        path="/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables",
    )
    item_path = "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables/orders"
    assert_read_request(
        api._get_table_v1_serialize(
            metalake=METALAKE,
            catalog=CATALOG,
            var_schema=SCHEMA,
            table=TABLE,
            if_none_match='"table-v1"',
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        method="GET",
        path=item_path,
        if_none_match='"table-v1"',
    )
    assert_json_mutation(
        api._update_table_v1_serialize(
            if_match=ETAG,
            metalake=METALAKE,
            catalog=CATALOG,
            var_schema=SCHEMA,
            table=TABLE,
            table_update_request=update,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        method="PUT",
        path=item_path,
        body={"comment": None, **table_body},
        if_match=ETAG,
    )
    assert_conditional_delete(
        api._delete_table_v1_serialize(
            if_match=ETAG,
            metalake=METALAKE,
            catalog=CATALOG,
            var_schema=SCHEMA,
            table=TABLE,
            purge=None,
            x_request_id=REQUEST_ID,
            **serializer_options(),
        ),
        path=item_path,
    )


@pytest.mark.endpoint
def test_generated_v1_apis_do_not_expose_patch_operations() -> None:
    """V1 full-state replacement is PUT-only; no generated API surface may add PATCH."""
    for api in (MetalakesApi, CatalogsApi, SchemasApi, TablesApi):
        patch_methods = [name for name in dir(api) if name.startswith(("patch_", "_patch_"))]
        assert patch_methods == []
