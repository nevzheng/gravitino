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

"""Unit coverage for documented create-table provider profiles."""

import pytest

from cuj.support.provider_profiles import (
    COMMON_CREATE_TABLE_ERRORS,
    PROFILES,
    CleanupMode,
    PayloadTrait,
    UseCaseSupport,
    common_error_for,
    declared_error_for,
    documented_use_case,
    profile_for,
)
from cuj.support.transport import ExpectedApiError


@pytest.mark.cuj
def test_profiles_cover_every_documented_relational_create_table_provider() -> None:
    """The CUJ matrix includes every connector named in the design scope."""
    assert set(PROFILES) == {
        "hive",
        "iceberg",
        "paimon",
        "hudi",
        "lance",
        "delta",
        "glue",
        "mysql",
        "postgresql",
        "doris",
        "starrocks",
        "oceanbase",
        "clickhouse",
        "hologres",
    }
    assert all(profile.sources for profile in PROFILES.values())


@pytest.mark.cuj
@pytest.mark.parametrize(
    ("alias", "canonical"),
    (
        ("apache-hive", "hive"),
        ("aws-glue", "glue"),
        ("generic-delta", "delta"),
        ("generic-lance", "lance"),
        ("postgres", "postgresql"),
    ),
)
def test_profile_lookup_accepts_useful_connector_aliases(
    alias: str, canonical: str
) -> None:
    """Generic feature steps can use clear connector names without duplicated mappings."""
    assert profile_for(alias) is profile_for(canonical)


@pytest.mark.cuj
def test_documented_use_case_exposes_provider_neutral_payload_hints() -> None:
    """The profile identifies the input shape without committing to a V1 wire model."""
    registered_delta = documented_use_case("delta", "register_existing_delta")

    assert registered_delta.should_succeed
    assert registered_delta.support is UseCaseSupport.SUPPORTED
    assert registered_delta.property_hints == {
        "format": "delta",
        "external": "true",
        "location": "<existing-delta-location>",
    }
    assert PayloadTrait.REGISTER_EXISTING in registered_delta.payload_traits
    assert PayloadTrait.EXTERNAL_TABLE in registered_delta.payload_traits
    assert PayloadTrait.STORAGE_LOCATION in registered_delta.payload_traits


@pytest.mark.cuj
def test_unsupported_connector_use_case_is_a_non_blocking_public_error_record() -> None:
    """A known backend limitation feeds warning handling rather than xfail or failure state."""
    record = declared_error_for("hive", "default_values")

    assert record is not None
    assert record.non_blocking
    assert record.expected == ExpectedApiError(
        status=501, error_type="UNSUPPORTED_OPERATION", retryable=False
    )
    assert not documented_use_case("hive", "default_values").should_succeed


@pytest.mark.cuj
def test_paimon_distinguishes_property_defaults_from_generic_column_defaults() -> None:
    """Paimon's documented default syntax is not conflated with wire column expressions."""
    property_default = documented_use_case("paimon", "default_values_via_properties")
    generic_default = documented_use_case("paimon", "default_values")

    assert property_default.should_succeed
    assert property_default.property_hints == {
        "fields.<column>.default-value": "<literal-default>"
    }
    assert PayloadTrait.PROPERTY_DEFAULT in property_default.payload_traits
    assert generic_default.support is UseCaseSupport.UNSUPPORTED
    assert generic_default.expected_error == ExpectedApiError(
        status=501, error_type="UNSUPPORTED_OPERATION", retryable=False
    )


@pytest.mark.cuj
def test_profiles_capture_connector_specific_create_constraints() -> None:
    """Important request rules remain visible to a future provider payload builder."""
    mysql = documented_use_case("mysql", "defaults_and_auto_increment")
    clickhouse = documented_use_case("clickhouse", "engine_partition_sort_and_primary_key")
    glue = documented_use_case("glue", "iceberg_partition_transforms")

    assert PayloadTrait.PRIMARY_KEY in mysql.payload_traits
    assert any("unique index" in constraint for constraint in mysql.constraints)
    assert PayloadTrait.DISTRIBUTION_NONE in clickhouse.payload_traits
    assert any("ORDER BY" in constraint for constraint in clickhouse.constraints)
    assert glue.property_hints["table-format"] == "ICEBERG"
    assert glue.property_hints["warehouse"] == "<catalog-warehouse>"
    assert "NOT NULL" in documented_use_case("glue", "complex_schema").constraints[0]


@pytest.mark.cuj
def test_documentation_conflicts_are_explicitly_conditional_or_non_blocking() -> None:
    """The profile does not silently turn incomplete documentation into a passing promise."""
    declared_lance = documented_use_case("lance", "declared_table")
    starrocks_indexes = documented_use_case("starrocks", "indexes")
    starrocks_auto_increment = documented_use_case("starrocks", "auto_increment")

    assert declared_lance.support is UseCaseSupport.CONDITIONAL
    assert any("does not yet describe" in constraint for constraint in declared_lance.constraints)
    assert starrocks_indexes.support is UseCaseSupport.UNSUPPORTED
    assert declared_error_for("starrocks", "indexes").non_blocking
    assert starrocks_auto_increment.support is UseCaseSupport.CONDITIONAL
    assert any(
        "connector page" in constraint for constraint in starrocks_auto_increment.constraints
    )


@pytest.mark.cuj
def test_hologres_auto_increment_uses_the_documented_invalid_argument_contract() -> None:
    """Hologres rejects the request shape; it is not a generic unsupported-operation error."""
    record = declared_error_for("hologres", "auto_increment")

    assert record is not None
    assert record.non_blocking
    assert record.expected == ExpectedApiError(
        status=400, error_type="INVALID_ARGUMENT", retryable=False
    )


@pytest.mark.cuj
def test_lance_schema_discovery_is_a_conditional_observation_not_a_fake_error() -> None:
    """Schema discovery is observed from the provider rather than asserted as a failure contract."""
    discovery = documented_use_case("lance", "registered_table_schema_discovery")

    assert discovery.support is UseCaseSupport.CONDITIONAL
    assert discovery.should_succeed
    assert declared_error_for("lance", "registered_table_schema_discovery") is None


@pytest.mark.cuj
@pytest.mark.parametrize(
    ("provider", "use_case"),
    (
        ("lance", "blank_table_name"),
        ("lance", "missing_columns"),
        ("lance", "duplicate_column_names"),
        ("lance", "unknown_layout_field"),
        ("lance", "auto_increment"),
        ("lance", "registered_table_schema_discovery"),
        ("paimon", "property_default_values"),
        ("paimon", "column_field_default_values"),
        ("paimon", "auto_increment"),
        ("paimon", "primary_key_index"),
        ("mysql", "auto_increment_without_key"),
        ("mysql", "primary_and_unique_indexes"),
        ("postgresql", "primary_and_unique_indexes"),
        ("doris", "primary_key_index"),
        ("starrocks", "unsupported_layout"),
        ("oceanbase", "auto_increment_without_key"),
        ("oceanbase", "primary_and_unique_indexes"),
        ("clickhouse", "mergetree_order_and_primary_key"),
        ("clickhouse", "data_skipping_index"),
        ("hologres", "auto_increment"),
        ("hologres", "primary_key_index"),
    ),
)
def test_every_feature_use_case_has_a_direct_provider_profile_mapping(
    provider: str, use_case: str
) -> None:
    """Gherkin steps do not need a second alias table to resolve documented scenarios."""
    assert documented_use_case(provider, use_case).name == use_case


@pytest.mark.cuj
def test_common_error_rules_use_the_v1_public_taxonomy() -> None:
    """Precondition and authorization paths carry exact status, type, and retryability."""
    missing_schema = common_error_for("missing_schema")
    permission_denied = common_error_for("permission_denied")

    assert missing_schema.expected == ExpectedApiError(
        status=404, error_type="SCHEMA_NOT_FOUND", retryable=False
    )
    assert permission_denied.expected == ExpectedApiError(
        status=403, error_type="PERMISSION_DENIED", retryable=False
    )
    assert missing_schema.non_blocking
    assert permission_denied.non_blocking


@pytest.mark.cuj
def test_duplicate_table_has_a_strict_v1_conflict_contract() -> None:
    """Duplicate creation is a normal exact public error, not a migration observation."""
    duplicate = common_error_for("duplicate_table")

    assert not duplicate.is_contract_gap
    assert duplicate.expected == ExpectedApiError(
        status=409, error_type="TABLE_ALREADY_EXISTS", retryable=False
    )
    assert not duplicate.non_blocking


@pytest.mark.cuj
def test_profile_cleanup_modes_preserve_provider_lifecycle_semantics() -> None:
    """Fixtures can choose cleanup without erasing external data accidentally."""
    assert profile_for("paimon").cleanup_mode is CleanupMode.PURGE
    assert profile_for("delta").cleanup_mode is CleanupMode.DROP_METADATA_ONLY
    assert profile_for("hudi").cleanup_mode is CleanupMode.NONE


@pytest.mark.cuj
def test_lookup_errors_list_available_provider_or_error_names() -> None:
    """BDD authoring mistakes fail with an actionable list of supported names."""
    with pytest.raises(KeyError, match="Available providers"):
        profile_for("not-a-provider")
    with pytest.raises(KeyError, match="Available use cases"):
        documented_use_case("hive", "not-a-use-case")
    with pytest.raises(KeyError, match="Available error cases"):
        common_error_for("not-an-error")


@pytest.mark.cuj
def test_common_error_mapping_is_immutable() -> None:
    """Scenario execution cannot mutate the target contract shared by later tests."""
    with pytest.raises(TypeError):
        COMMON_CREATE_TABLE_ERRORS["new"] = common_error_for("missing_schema")  # type: ignore[index]
