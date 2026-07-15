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

"""No-server serialization checks for target V1 parent/table CRUD requests."""

import pytest

from gravitino_testkit.v1_crud import (
    JSON_MEDIA_TYPE,
    PRECONDITION_FAILED,
    TABLE_ALREADY_EXISTS,
    assert_precondition_failed_error,
    assert_table_already_exists_error,
    catalog_create_body,
    create_catalog,
    create_metalake,
    create_schema,
    create_table,
    delete_catalog,
    delete_metalake,
    delete_schema,
    delete_table,
    get_catalog,
    get_metalake,
    get_schema,
    get_table,
    list_catalogs,
    list_metalakes,
    list_schemas,
    list_tables,
    table_create_body,
    update_catalog,
    update_metalake,
    update_schema,
    update_table,
)


REQUEST_ID = "openapi-v1-crud-unit"
METALAKE = "lake"
CATALOG = "catalog"
SCHEMA = "schema"
TABLE = "orders"
ETAG = '"v1-resource"'


def assert_json_mutation(
    request: object,
    method: str,
    path: str,
    body: dict[str, object],
    *,
    if_match: str | None = None,
) -> None:
    """Asserts common target V1 JSON mutation serialization without a transport."""
    assert request.method == method
    assert request.path == path
    headers = {
        "Accept": JSON_MEDIA_TYPE,
        "Content-Type": JSON_MEDIA_TYPE,
        "X-Request-Id": REQUEST_ID,
    }
    if if_match is not None:
        headers["If-Match"] = if_match
    assert dict(request.headers) == headers
    assert request.body() == body


@pytest.mark.endpoint
def test_parent_create_requests_use_nested_v1_routes_and_strict_json_bodies() -> None:
    """Metalake, catalog, and schema creates retain only public write fields."""
    metalake = create_metalake(
        METALAKE,
        comment="test lake",
        properties={"owner": "api"},
        request_id=REQUEST_ID,
    )
    catalog = create_catalog(
        METALAKE,
        CATALOG,
        "RELATIONAL",
        provider="jdbc-mysql",
        comment="test catalog",
        properties={"jdbc-url": "jdbc:mysql://example.test:3306"},
        request_id=REQUEST_ID,
    )
    schema = create_schema(
        METALAKE,
        CATALOG,
        SCHEMA,
        comment="test schema",
        properties={"location": "file:///tmp/v1"},
        request_id=REQUEST_ID,
    )

    assert_json_mutation(
        metalake,
        "POST",
        "/api/v1/metalakes",
        {"name": METALAKE, "comment": "test lake", "properties": {"owner": "api"}},
    )
    assert_json_mutation(
        catalog,
        "POST",
        "/api/v1/metalakes/lake/catalogs",
        {
            "name": CATALOG,
            "type": "RELATIONAL",
            "provider": "jdbc-mysql",
            "comment": "test catalog",
            "properties": {"jdbc-url": "jdbc:mysql://example.test:3306"},
        },
    )
    assert_json_mutation(
        schema,
        "POST",
        "/api/v1/metalakes/lake/catalogs/catalog/schemas",
        {
            "name": SCHEMA,
            "comment": "test schema",
            "properties": {"location": "file:///tmp/v1"},
        },
    )


@pytest.mark.endpoint
def test_table_create_serializes_only_target_v1_write_fields() -> None:
    """Table creation exposes the complete V1 wire graph but not response metadata."""
    request = create_table(
        METALAKE,
        CATALOG,
        SCHEMA,
        TABLE,
        [
            {
                "name": "id",
                "type": {"kind": "LONG", "signed": True},
                "nullable": False,
                "autoIncrement": False,
            }
        ],
        comment="orders",
        properties={"format": "iceberg"},
        partitioning=[{"kind": "IDENTITY", "fieldName": ["id"]}],
        distribution={
            "strategy": "HASH",
            "bucketCount": 8,
            "expressions": [{"type": "reference", "name": "id"}],
        },
        sort_orders=[
            {
                "expression": {"type": "reference", "name": "id"},
                "direction": "ASC",
                "nullOrdering": "NULLS_LAST",
            }
        ],
        indexes=[
            {
                "type": "PRIMARY_KEY",
                "name": "orders_pk",
                "fieldNames": [["id"]],
                "properties": {},
            }
        ],
        request_id=REQUEST_ID,
    )

    assert_json_mutation(
        request,
        "POST",
        "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables",
        {
            "name": TABLE,
            "comment": "orders",
            "columns": [
                {
                    "name": "id",
                    "type": {"kind": "LONG", "signed": True},
                    "nullable": False,
                    "autoIncrement": False,
                }
            ],
            "properties": {"format": "iceberg"},
            "partitioning": [{"kind": "IDENTITY", "fieldName": ["id"]}],
            "distribution": {
                "strategy": "HASH",
                "bucketCount": 8,
                "expressions": [{"type": "reference", "name": "id"}],
            },
            "sortOrders": [
                {
                    "expression": {"type": "reference", "name": "id"},
                    "direction": "ASC",
                    "nullOrdering": "NULLS_LAST",
                }
            ],
            "indexes": [
                {
                    "type": "PRIMARY_KEY",
                    "name": "orders_pk",
                    "fieldNames": [["id"]],
                    "properties": {},
                }
            ],
        },
    )
    assert "resourceName" not in request.body()
    assert "audit" not in request.body()


@pytest.mark.endpoint
@pytest.mark.parametrize(
    ("serialized_request", "path"),
    (
        (get_metalake(METALAKE, request_id=REQUEST_ID), "/api/v1/metalakes/lake"),
        (list_metalakes(request_id=REQUEST_ID), "/api/v1/metalakes"),
        (
            get_catalog(METALAKE, CATALOG, request_id=REQUEST_ID),
            "/api/v1/metalakes/lake/catalogs/catalog",
        ),
        (
            list_catalogs(METALAKE, request_id=REQUEST_ID),
            "/api/v1/metalakes/lake/catalogs",
        ),
        (
            get_schema(METALAKE, CATALOG, SCHEMA, request_id=REQUEST_ID),
            "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema",
        ),
        (
            list_schemas(METALAKE, CATALOG, request_id=REQUEST_ID),
            "/api/v1/metalakes/lake/catalogs/catalog/schemas",
        ),
        (
            get_table(METALAKE, CATALOG, SCHEMA, TABLE, request_id=REQUEST_ID),
            "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables/orders",
        ),
        (
            list_tables(METALAKE, CATALOG, SCHEMA, request_id=REQUEST_ID),
            "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables",
        ),
    ),
)
def test_get_and_list_requests_are_bodyless_standard_json_requests(
    serialized_request: object, path: str
) -> None:
    """Reads use route versioning and portable JSON negotiation."""
    assert serialized_request.method == "GET"
    assert serialized_request.path == path
    assert dict(serialized_request.headers) == {
        "Accept": JSON_MEDIA_TYPE,
        "X-Request-Id": REQUEST_ID,
    }
    assert serialized_request.body() is None


@pytest.mark.endpoint
@pytest.mark.parametrize(
    ("serialized_request", "path"),
    (
        (
            delete_metalake(METALAKE, if_match=ETAG, request_id=REQUEST_ID),
            "/api/v1/metalakes/lake",
        ),
        (
            delete_catalog(METALAKE, CATALOG, if_match=ETAG, request_id=REQUEST_ID),
            "/api/v1/metalakes/lake/catalogs/catalog",
        ),
        (
            delete_schema(METALAKE, CATALOG, SCHEMA, if_match=ETAG, request_id=REQUEST_ID),
            "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema",
        ),
        (
            delete_table(
                METALAKE, CATALOG, SCHEMA, TABLE, if_match=ETAG, request_id=REQUEST_ID
            ),
            "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables/orders",
        ),
    ),
)
def test_delete_requests_require_a_strong_etag_and_are_bodyless(
    serialized_request: object, path: str
) -> None:
    """Every V1 delete carries the current strong validator, not a body."""
    assert serialized_request.method == "DELETE"
    assert serialized_request.path == path
    assert dict(serialized_request.headers) == {
        "Accept": JSON_MEDIA_TYPE,
        "If-Match": ETAG,
        "X-Request-Id": REQUEST_ID,
    }
    assert serialized_request.body() is None


@pytest.mark.endpoint
def test_parent_put_replaces_only_mutable_desired_state_with_a_strong_etag() -> None:
    """Parent PUT bodies keep identity and catalog creation configuration out of the wire state."""
    requests = (
        update_metalake(
            METALAKE,
            comment=None,
            properties={"owner": "platform"},
            if_match=ETAG,
            request_id=REQUEST_ID,
        ),
        update_catalog(
            METALAKE,
            CATALOG,
            comment="managed catalog",
            properties={"owner": "platform"},
            if_match=ETAG,
            request_id=REQUEST_ID,
        ),
        update_schema(
            METALAKE,
            CATALOG,
            SCHEMA,
            comment=None,
            properties={},
            if_match=ETAG,
            request_id=REQUEST_ID,
        ),
    )

    expected = (
        ("/api/v1/metalakes/lake", {"comment": None, "properties": {"owner": "platform"}}),
        (
            "/api/v1/metalakes/lake/catalogs/catalog",
            {"comment": "managed catalog", "properties": {"owner": "platform"}},
        ),
        (
            "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema",
            {"comment": None, "properties": {}},
        ),
    )
    for request, (path, body) in zip(requests, expected, strict=True):
        assert_json_mutation(request, "PUT", path, body, if_match=ETAG)
        assert "name" not in request.body()
        assert "type" not in request.body()
        assert "provider" not in request.body()


@pytest.mark.endpoint
def test_table_put_replaces_the_complete_mutable_graph_without_the_table_name() -> None:
    """A table PUT contains all required mutable members and one optional distribution."""
    request = update_table(
        METALAKE,
        CATALOG,
        SCHEMA,
        TABLE,
        comment=None,
        columns=[
            {
                "name": "id",
                "type": {"kind": "LONG", "signed": True},
                "nullable": False,
                "autoIncrement": False,
            }
        ],
        properties={"format": "iceberg"},
        partitioning=[{"kind": "IDENTITY", "fieldName": ["id"]}],
        distribution={
            "strategy": "HASH",
            "bucketCount": 8,
            "expressions": [{"type": "reference", "name": "id"}],
        },
        sort_orders=[
            {
                "expression": {"type": "reference", "name": "id"},
                "direction": "ASC",
                "nullOrdering": "NULLS_LAST",
            }
        ],
        indexes=[
            {
                "type": "PRIMARY_KEY",
                "name": "orders_pk",
                "fieldNames": [["id"]],
                "properties": {},
            }
        ],
        if_match='"table-v7"',
        request_id=REQUEST_ID,
    )

    assert_json_mutation(
        request,
        "PUT",
        "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables/orders",
        {
            "comment": None,
            "columns": [
                {
                    "name": "id",
                    "type": {"kind": "LONG", "signed": True},
                    "nullable": False,
                    "autoIncrement": False,
                }
            ],
            "properties": {"format": "iceberg"},
            "partitioning": [{"kind": "IDENTITY", "fieldName": ["id"]}],
            "distribution": {
                "strategy": "HASH",
                "bucketCount": 8,
                "expressions": [{"type": "reference", "name": "id"}],
            },
            "sortOrders": [
                {
                    "expression": {"type": "reference", "name": "id"},
                    "direction": "ASC",
                    "nullOrdering": "NULLS_LAST",
                }
            ],
            "indexes": [
                {
                    "type": "PRIMARY_KEY",
                    "name": "orders_pk",
                    "fieldNames": [["id"]],
                    "properties": {},
                }
            ],
        },
        if_match='"table-v7"',
    )
    assert "name" not in request.body()


@pytest.mark.endpoint
def test_table_put_omits_only_the_optional_distribution() -> None:
    """An intentionally absent distribution remains absent while all other mutable fields exist."""
    request = update_table(
        METALAKE,
        CATALOG,
        SCHEMA,
        TABLE,
        comment="replacement",
        columns=[],
        properties={},
        partitioning=[],
        sort_orders=[],
        indexes=[],
        if_match=ETAG,
        request_id=REQUEST_ID,
    )

    assert_json_mutation(
        request,
        "PUT",
        "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables/orders",
        {
            "comment": "replacement",
            "columns": [],
            "properties": {},
            "partitioning": [],
            "sortOrders": [],
            "indexes": [],
        },
        if_match=ETAG,
    )
    assert "distribution" not in request.body()


@pytest.mark.endpoint
def test_table_schema_discovery_still_serializes_an_explicit_empty_columns_array() -> None:
    """Registration-style table creation never conflates absent and empty columns."""
    body = table_create_body(
        "registered",
        [],
        properties={"format": "lance", "lance.register": "true"},
    )

    assert body == {
        "name": "registered",
        "columns": [],
        "properties": {"format": "lance", "lance.register": "true"},
        "partitioning": [],
        "sortOrders": [],
        "indexes": [],
    }


@pytest.mark.endpoint
def test_duplicate_table_uses_the_exact_non_retryable_v1_conflict_contract() -> None:
    """A duplicate create never degrades into a generic or retryable error."""
    payload = {
        "error": {
            "code": 409,
            "type": "TABLE_ALREADY_EXISTS",
            "message": "A table with this name already exists.",
            "retryable": False,
            "requestId": REQUEST_ID,
            "details": [
                {
                    "kind": "RESOURCE_INFO",
                    "resourceType": "TABLE",
                    "resourceName": "metalakes/lake/catalogs/catalog/schemas/schema/tables/orders",
                }
            ],
        }
    }

    assert (TABLE_ALREADY_EXISTS.status, TABLE_ALREADY_EXISTS.error_type) == (
        409,
        "TABLE_ALREADY_EXISTS",
    )
    error = assert_table_already_exists_error(
        payload,
        request_id=REQUEST_ID,
        metalake=METALAKE,
        catalog=CATALOG,
        schema=SCHEMA,
        table=TABLE,
    )
    assert error["retryable"] is False


@pytest.mark.endpoint
def test_stale_etag_uses_the_exact_non_retryable_v1_precondition_contract() -> None:
    """A stale representation is an explicit 412 instead of a generic conflict."""
    payload = {
        "error": {
            "code": 412,
            "type": "PRECONDITION_FAILED",
            "message": "The resource has changed since it was read.",
            "retryable": False,
            "requestId": REQUEST_ID,
            "details": [
                {
                    "kind": "RESOURCE_INFO",
                    "resourceType": "TABLE",
                    "resourceName": "metalakes/lake/catalogs/catalog/schemas/schema/tables/orders",
                }
            ],
        }
    }

    assert (PRECONDITION_FAILED.status, PRECONDITION_FAILED.error_type) == (
        412,
        "PRECONDITION_FAILED",
    )
    error = assert_precondition_failed_error(
        payload,
        request_id=REQUEST_ID,
        resource_type="TABLE",
        resource_name="metalakes/lake/catalogs/catalog/schemas/schema/tables/orders",
    )
    assert error["retryable"] is False


@pytest.mark.endpoint
def test_paths_escape_each_identifier_as_one_segment_and_bodies_are_copied() -> None:
    """Special characters cannot change resource hierarchy or mutate future requests."""
    request = create_table(
        "lake one",
        "catalog/two",
        "schema three",
        "orders/four",
        [
            {
                "name": "id",
                "type": {"kind": "LONG", "signed": True},
                "nullable": False,
                "autoIncrement": False,
            }
        ],
    )
    first = request.body()
    second = request.body()

    assert request.path == (
        "/api/v1/metalakes/lake%20one/catalogs/catalog%2Ftwo/"
        "schemas/schema%20three/tables"
    )
    assert get_table("lake one", "catalog/two", "schema three", "orders/four").path == (
        "/api/v1/metalakes/lake%20one/catalogs/catalog%2Ftwo/"
        "schemas/schema%20three/tables/orders%2Ffour"
    )
    assert first is not None
    assert second is not None
    first["columns"][0]["name"] = "changed"
    assert second["columns"][0]["name"] == "id"


@pytest.mark.endpoint
@pytest.mark.parametrize("invalid_etag", ("*", 'W/"table-v1"', '"one", "two"', '""'))
def test_invalid_target_request_input_is_rejected_before_any_transport_is_used(
    invalid_etag: str,
) -> None:
    """Test authors get useful failures for ambiguous or malformed target requests."""
    with pytest.raises(ValueError, match="provider is required"):
        catalog_create_body("catalog", "RELATIONAL")
    with pytest.raises(ValueError, match="strong quoted ETag"):
        delete_table(METALAKE, CATALOG, SCHEMA, TABLE, if_match=invalid_etag)
    with pytest.raises(TypeError, match="if_match"):
        update_table(
            METALAKE,
            CATALOG,
            SCHEMA,
            TABLE,
            comment=None,
            columns=[],
            properties={},
            partitioning=[],
            sort_orders=[],
            indexes=[],
        )
    with pytest.raises(TypeError, match="properties must be a JSON object"):
        update_metalake(METALAKE, comment=None, properties=None, if_match=ETAG)
