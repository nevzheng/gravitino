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

"""Unit coverage for target V1 documented create-table payloads."""

import pytest

from cuj.support.create_table_payloads import (
    CASES,
    FEATURE_CASE_KEYS,
    CaseOutcome,
    PayloadInputs,
    build_create_table_payload,
    build_create_table_plan,
)


@pytest.mark.cuj
def test_every_documented_feature_case_has_a_concrete_target_v1_payload() -> None:
    """Feature additions cannot silently become an unmodeled or skipped payload."""
    assert FEATURE_CASE_KEYS == frozenset(CASES)

    for provider, use_case in FEATURE_CASE_KEYS:
        plan = build_create_table_payload(provider, use_case, "cuj_table")

        assert plan.payload["name"] is not None
        assert {
            "name",
            "columns",
            "properties",
            "partitioning",
            "sortOrders",
            "indexes",
        } <= set(plan.payload)
        assert plan.sources


@pytest.mark.cuj
@pytest.mark.parametrize("provider", ("mysql", "oceanbase"))
def test_auto_increment_payloads_include_a_same_request_unique_key(
    provider: str,
) -> None:
    """MySQL and OceanBase meet their documented auto-increment key constraint."""
    plan = build_create_table_payload(provider, "defaults_and_auto_increment", "orders")
    payload = plan.body()

    assert payload["columns"][0]["autoIncrement"] is True
    assert payload["indexes"] == [
        {
            "type": "PRIMARY_KEY",
            "name": "PRIMARY",
            "fieldNames": [["id"]],
            "properties": {},
        }
    ]

    invalid = build_create_table_payload(provider, "auto_increment_without_key", "orders")
    assert invalid.outcome is CaseOutcome.ASSERT_ERROR
    assert invalid.expected_error is not None
    assert invalid.expected_error.error is not None
    assert invalid.expected_error.error.error_type == "INVALID_ARGUMENT"
    assert invalid.body()["indexes"] == []


@pytest.mark.cuj
def test_paimon_primary_key_and_partition_fields_are_deliberately_distinct() -> None:
    """The Paimon payload avoids the one-record-per-partition anti-pattern."""
    payload = build_create_table_payload(
        "paimon", "identity_partition_and_primary_key", "orders"
    ).body()

    assert payload["partitioning"] == [{"kind": "IDENTITY", "fieldName": ["ds"]}]
    assert payload["indexes"][0]["fieldNames"] == [["order_id"]]


@pytest.mark.cuj
def test_clickhouse_payloads_always_state_engine_order_and_none_distribution() -> None:
    """ClickHouse cases do not rely on a generic bare-table request."""
    primary_key = build_create_table_payload(
        "clickhouse", "mergetree_order_and_primary_key", "orders"
    ).body()
    data_skipping = build_create_table_payload(
        "clickhouse", "data_skipping_index", "orders"
    ).body()

    for payload in (primary_key, data_skipping):
        assert payload["properties"]["engine"] == "MergeTree"
        assert payload["distribution"] == {"strategy": "NONE", "expressions": []}
        assert payload["sortOrders"][0]["expression"] == {
            "type": "reference",
            "name": "id",
        }

    assert data_skipping["indexes"][-1]["type"] == "DATA_SKIPPING_MINMAX"


@pytest.mark.cuj
def test_glue_complex_schema_does_not_request_an_unsupported_not_null_constraint() -> None:
    """The Glue complex path stays within the connector's documented type limits."""
    payload = build_create_table_payload("glue", "complex_schema", "events").body()

    assert all(column["nullable"] is True for column in payload["columns"])
    assert "comment" not in payload["columns"][1]["type"]["fields"][0]


@pytest.mark.cuj
def test_lance_lifecycle_payloads_require_fixture_owned_locations_and_preconditions() -> None:
    """Lifecycle requests cannot accidentally run against an unowned data path."""
    managed = build_create_table_payload("lance", "managed_table", "orders")
    assert managed.unmet_requirements == ("table_location",)
    assert managed.body()["properties"]["location"] == "<fixture:table_location>"

    registered = build_create_table_payload("lance", "registered_table", "orders")
    assert registered.unmet_requirements == ("existing_dataset_location",)
    assert registered.body()["columns"] == []
    assert registered.body()["properties"]["lance.register"] == "true"

    overwrite = build_create_table_payload(
        "lance",
        "creation_mode_overwrite",
        "orders",
        table_location="file:///tmp/cuj/orders",
    )
    assert overwrite.unmet_requirements == ("existing_table",)
    assert any("OVERWRITE deletes it" in note for note in overwrite.preconditions)

    ready = build_create_table_payload(
        "lance",
        "creation_mode_overwrite",
        "orders",
        table_location="file:///tmp/cuj/orders",
        existing_table=True,
    )
    assert ready.is_ready_to_send


@pytest.mark.cuj
def test_delta_and_glue_registration_bind_only_fixture_provisioned_external_resources() -> None:
    """Registration payloads describe their external-resource preconditions explicitly."""
    delta = build_create_table_payload("delta", "register_existing_delta", "orders")
    glue = build_create_table_payload("glue", "register_existing_iceberg", "orders")

    assert delta.unmet_requirements == ("existing_dataset_location",)
    assert delta.body()["properties"]["location"] == "<fixture:existing_dataset_location>"
    assert any("_delta_log" in note for note in delta.preconditions)
    assert glue.unmet_requirements == ("metadata_location",)
    assert glue.body()["properties"]["metadata_location"] == "<fixture:metadata_location>"


@pytest.mark.cuj
def test_non_blocking_provider_limitations_keep_their_documented_public_error_tuple() -> None:
    """Known connector limitations warn later but still declare the target API error."""
    hive = build_create_table_payload("hive", "default_values", "orders")
    hologres = build_create_table_payload("hologres", "auto_increment", "orders")
    duplicate = build_create_table_payload(
        "lance", "duplicate_table", "orders", table_location="file:///tmp/cuj/orders"
    )

    assert hive.outcome is CaseOutcome.NON_BLOCKING_ERROR
    assert hive.expected_error is not None
    assert hive.expected_error.error is not None
    assert (hive.expected_error.error.status, hive.expected_error.error.error_type) == (
        501,
        "UNSUPPORTED_OPERATION",
    )
    assert hologres.expected_error is not None
    assert hologres.expected_error.error is not None
    assert (hologres.expected_error.error.status, hologres.expected_error.error.error_type) == (
        400,
        "INVALID_ARGUMENT",
    )
    assert duplicate.outcome is CaseOutcome.ASSERT_ERROR
    assert duplicate.expected_error is not None
    assert duplicate.expected_error.error is not None
    assert (duplicate.expected_error.error.status, duplicate.expected_error.error.error_type) == (
        409,
        "TABLE_ALREADY_EXISTS",
    )
    assert duplicate.unmet_requirements == ("existing_table",)


@pytest.mark.cuj
def test_returned_payload_bodies_are_independent_deep_copies() -> None:
    """One scenario cannot mutate a subsequent scenario's request shape."""
    plan = build_create_table_plan("mysql", "default_values", PayloadInputs("orders"))
    first = plan.body()
    second = plan.body()

    first["columns"][0]["name"] = "mutated"
    assert second["columns"][0]["name"] == "id"


@pytest.mark.cuj
def test_unknown_payload_case_lists_available_keys() -> None:
    """A BDD spelling mistake produces an actionable authoring failure."""
    with pytest.raises(KeyError, match="Available cases"):
        build_create_table_payload("lance", "not_a_real_case", "orders")
