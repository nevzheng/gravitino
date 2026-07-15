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

"""No-server checks for the typed V1 connector capability matrix."""

import pytest

from cuj.support.v1_typed_table_profiles import (
    TYPED_TABLE_PROFILES,
    build_typed_table_create_plan,
    typed_table_profile,
    typed_table_provider_names,
)
from gravitino_testkit.v1_typed_tables import typed_option_keys


@pytest.mark.cuj
@pytest.mark.parametrize("provider", typed_table_provider_names())
def test_every_table_capable_connector_has_a_clean_minimal_typed_create_profile(
    provider: str,
) -> None:
    """Profiles use storage/options only and never leak a generic public properties map."""
    plan = build_typed_table_create_plan(
        provider,
        "orders",
        table_location="file:///tmp/typed-cuj/{table}".replace("{table}", "orders"),
        existing_dataset_location="file:///tmp/typed-cuj/existing-delta-orders",
    )
    body = plan.request_body()
    option_blocks = set(body).intersection(typed_option_keys())

    assert plan.is_ready_to_send
    assert body["name"] == "orders"
    assert body["columns"]
    assert "properties" not in body
    assert len(option_blocks) <= 1
    assert option_blocks <= typed_option_keys()
    assert plan.sources


@pytest.mark.cuj
def test_typed_options_profiles_encode_the_agreed_storage_and_option_examples() -> None:
    """The POC has a concrete example for each of the first four typed option schemas."""
    iceberg = build_typed_table_create_plan("iceberg", "orders").request_body()
    hive = build_typed_table_create_plan("hive", "orders").request_body()
    clickhouse = build_typed_table_create_plan("clickhouse", "orders").request_body()
    mysql = build_typed_table_create_plan("mysql", "orders").request_body()

    assert iceberg["storage"] == {
        "ownership": "MANAGED",
        "tableFormat": "ICEBERG",
        "fileFormat": "PARQUET",
    }
    assert iceberg["icebergOptions"] == {"formatVersion": 2}
    assert hive["storage"]["fileFormat"] == "ORC"
    assert hive["hiveOptions"] == {
        "serdeLibrary": "org.apache.hadoop.hive.ql.io.orc.OrcSerde"
    }
    assert clickhouse["clickhouseOptions"] == {"engine": "MergeTree"}
    assert mysql["mysqlOptions"] == {"engine": "InnoDB"}


@pytest.mark.cuj
def test_delta_uses_the_normal_collection_post_as_an_external_metadata_create() -> None:
    """Delta needs no special route or delta options: its shared external storage is sufficient."""
    plan = build_typed_table_create_plan("delta", "orders")

    assert plan.unmet_requirements == ("existing_dataset_location",)
    assert plan.request_body()["storage"] == {
        "ownership": "EXTERNAL",
        "tableFormat": "DELTA",
        "location": "<fixture:existing_dataset_location>",
    }
    assert not set(plan.request_body()).intersection(typed_option_keys())
    assert plan.update_expected_error is not None
    assert (
        plan.update_expected_error.status,
        plan.update_expected_error.error_type,
        plan.update_expected_error.retryable,
    ) == (501, "UNSUPPORTED_OPERATION", False)


@pytest.mark.cuj
def test_hudi_create_is_a_visible_nonblocking_public_error_profile() -> None:
    """Hudi remains in the matrix as a truthful negative create case, not a missing profile."""
    plan = build_typed_table_create_plan("hudi", "orders")

    assert plan.is_ready_to_send
    assert plan.create_expected_error is not None
    assert (
        plan.create_expected_error.status,
        plan.create_expected_error.error_type,
        plan.create_expected_error.retryable,
    ) == (501, "UNSUPPORTED_OPERATION", False)


@pytest.mark.cuj
def test_glue_and_paimon_profiles_do_not_make_false_storage_claims() -> None:
    """Glue is external at creation time; Paimon has no shared storage envelope yet."""
    glue = build_typed_table_create_plan("glue", "orders")
    paimon = build_typed_table_create_plan("paimon", "orders")

    assert glue.unmet_requirements == ("table_location",)
    assert glue.request_body()["storage"] == {
        "ownership": "EXTERNAL",
        "tableFormat": "HIVE",
        "fileFormat": "PARQUET",
        "location": "<fixture:table_location>",
    }
    assert "storage" not in paimon.request_body()
    assert "paimonOptions" not in paimon.request_body()


@pytest.mark.cuj
def test_minimal_profiles_keep_required_connector_layout_in_the_shared_shape() -> None:
    """Doris, StarRocks, and ClickHouse retain required physical declarations without raw maps."""
    doris = build_typed_table_create_plan("doris", "orders").request_body()
    starrocks = build_typed_table_create_plan("starrocks", "orders").request_body()
    clickhouse = build_typed_table_create_plan("clickhouse", "orders").request_body()

    for payload in (doris, starrocks):
        assert payload["distribution"] == {
            "strategy": "HASH",
            "bucketCount": 8,
            "expressions": [{"type": "reference", "name": "id"}],
        }
    assert clickhouse["distribution"] == {"strategy": "NONE", "expressions": []}
    assert clickhouse["sortOrders"] == [
        {
            "expression": {"type": "reference", "name": "id"},
            "direction": "ASC",
            "nullOrdering": "NULLS_LAST",
        }
    ]
    assert "properties" not in clickhouse


@pytest.mark.cuj
def test_profiles_are_closed_immutable_and_findable() -> None:
    """A future connector cannot silently bypass the typed profile/oneOf review boundary."""
    assert set(typed_table_provider_names()) == set(TYPED_TABLE_PROFILES)
    with pytest.raises(TypeError):
        TYPED_TABLE_PROFILES["iceberg"].option_blocks["hiveOptions"] = {}  # type: ignore[index]
    with pytest.raises(KeyError, match="Available providers"):
        typed_table_profile("unreviewed-provider")
