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

"""No-server endpoint checks for the typed V1 table contract direction.

The checked-in generated client still follows the current V1 schema.  These
raw request tests intentionally protect the next contract shape until a
regeneration supplies matching Python model classes.
"""

from __future__ import annotations

import pytest

from cuj.support.v1_typed_table_profiles import (
    build_typed_table_create_plan,
    typed_table_provider_names,
)
from gravitino_testkit.v1_typed_tables import (
    create_typed_table,
    typed_table_create_body,
    update_typed_table,
)


_REQUEST_ID = "typed-v1-endpoint-unit"
_ETAG = '"typed-table-v1"'
_COLUMNS = [
    {
        "name": "id",
        "type": {"kind": "LONG", "signed": True},
        "nullable": False,
        "autoIncrement": False,
    }
]


@pytest.mark.endpoint
@pytest.mark.parametrize("provider", typed_table_provider_names())
def test_every_table_connector_has_a_minimal_typed_v1_post_profile(provider: str) -> None:
    """Every table-capable connector has a clean public POST shape before live setup exists."""
    plan = build_typed_table_create_plan(
        provider,
        "orders",
        table_location="file:///tmp/openapi/lance/orders",
        existing_dataset_location="file:///tmp/openapi/delta/orders",
    )

    body = plan.request_body()
    assert body["name"] == "orders"
    assert body["columns"] == _COLUMNS
    assert isinstance(body["partitioning"], list)
    assert isinstance(body["sortOrders"], list)
    assert isinstance(body["indexes"], list)
    assert "properties" not in body
    option_blocks = set(body).intersection(
        {"icebergOptions", "hiveOptions", "clickhouseOptions", "mysqlOptions"}
    )
    assert {key for key in body if key.endswith("Options")} == option_blocks
    assert len(option_blocks) <= 1


@pytest.mark.endpoint
def test_typed_table_post_and_metadata_only_put_keep_storage_and_options_on_the_wire() -> None:
    """A full PUT round-trips immutable typed state while changing only public metadata."""
    profile = build_typed_table_create_plan("iceberg", "orders")
    create_body = profile.request_body()
    request = create_typed_table(
        "lake",
        "catalog",
        "schema",
        "orders",
        _COLUMNS,
        comment="created by the typed V1 table CUJ",
        storage=create_body["storage"],
        option_blocks={"icebergOptions": create_body["icebergOptions"]},
        partitioning=[],
        sort_orders=[],
        indexes=[],
        request_id=_REQUEST_ID,
    )
    update = update_typed_table(
        "lake",
        "catalog",
        "schema",
        "orders",
        comment="updated comment only",
        columns=_COLUMNS,
        storage=create_body["storage"],
        option_blocks={"icebergOptions": create_body["icebergOptions"]},
        partitioning=[],
        sort_orders=[],
        indexes=[],
        if_match=_ETAG,
        request_id=_REQUEST_ID,
    )

    assert request.method == "POST"
    assert request.path == "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables"
    assert dict(request.headers) == {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "X-Request-Id": _REQUEST_ID,
    }
    assert "properties" not in request.body()
    assert update.method == "PUT"
    assert update.path == "/api/v1/metalakes/lake/catalogs/catalog/schemas/schema/tables/orders"
    assert dict(update.headers) == {
        "Accept": "application/json",
        "Content-Type": "application/json",
        "If-Match": _ETAG,
        "X-Request-Id": _REQUEST_ID,
    }
    assert update.body() == {
        "comment": "updated comment only",
        "columns": _COLUMNS,
        "storage": {
            "ownership": "MANAGED",
            "tableFormat": "ICEBERG",
            "fileFormat": "PARQUET",
        },
        "icebergOptions": {"formatVersion": 2},
        "partitioning": [],
        "sortOrders": [],
        "indexes": [],
    }


@pytest.mark.endpoint
def test_typed_option_blocks_are_closed_and_one_of_before_any_http_transport_is_used() -> None:
    """Tests cannot accidentally reintroduce a generic provider-property escape hatch."""
    with pytest.raises(ValueError, match="at most one typed provider-options block"):
        typed_table_create_body(
            "orders",
            _COLUMNS,
            option_blocks={
                "icebergOptions": {"formatVersion": 2},
                "hiveOptions": {"serdeName": "orders"},
            },
        )
    with pytest.raises(ValueError, match="unknown keys"):
        typed_table_create_body(
            "orders",
            _COLUMNS,
            option_blocks={"opaqueProperties": {"anything": "goes"}},
        )
