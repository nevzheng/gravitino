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

"""Documented create-table CUJ profiles for relational catalog providers.

This module intentionally describes *what* a provider documents instead of
constructing JSON payloads. A BDD step can select a named use case, inspect
its payload traits and property hints, and build the target V1 request.
Keeping this provider-neutral also
means the same documented journey can be exercised through the raw Playwright
API context and verified through a generated OpenAPI client.

Expected errors are declarations, not xfails. Callers record them through
``record_non_blocking_expected_error`` from ``cuj.support.transport`` so every
known limitation stays visible without blocking the suite.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass, field
from enum import Enum
from types import MappingProxyType

from cuj.support.transport import ExpectedApiError


class UseCaseSupport(str, Enum):
    """Whether a provider documents a create-table use case as available."""

    SUPPORTED = "supported"
    CONDITIONAL = "conditional"
    METADATA_ONLY = "metadata_only"
    INVALID = "invalid"
    UNSUPPORTED = "unsupported"


class PayloadTrait(str, Enum):
    """Provider-neutral request traits consumed by future CUJ payload builders."""

    COLUMNS = "columns"
    COMMENT = "comment"
    TABLE_PROPERTIES = "table_properties"
    STORAGE_LOCATION = "storage_location"
    MANAGED_TABLE = "managed_table"
    EXTERNAL_TABLE = "external_table"
    REGISTER_EXISTING = "register_existing"
    DECLARE_METADATA_ONLY = "declare_metadata_only"
    CREATE_MODE = "create_mode"
    COMPLEX_SCHEMA = "complex_schema"
    PARAMETERIZED_SCHEMA = "parameterized_schema"
    COLUMN_DEFAULT = "column_default"
    PROPERTY_DEFAULT = "property_default"
    AUTO_INCREMENT = "auto_increment"
    NOT_NULL = "not_null"
    PARTITION_IDENTITY = "partition_identity"
    PARTITION_TRANSFORM = "partition_transform"
    PARTITION_RANGE = "partition_range"
    PARTITION_LIST = "partition_list"
    SORT_ORDER = "sort_order"
    DISTRIBUTION_HASH = "distribution_hash"
    DISTRIBUTION_RANGE = "distribution_range"
    DISTRIBUTION_EVEN = "distribution_even"
    DISTRIBUTION_NONE = "distribution_none"
    PRIMARY_KEY = "primary_key"
    UNIQUE_KEY = "unique_key"
    DATA_SKIPPING_INDEX = "data_skipping_index"
    ENGINE = "engine"
    CLUSTER = "cluster"


class CleanupMode(str, Enum):
    """How a CUJ fixture removes a table it owns after a successful journey."""

    DROP = "drop"
    PURGE = "purge"
    DROP_METADATA_ONLY = "drop_metadata_only"
    NONE = "none"


@dataclass(frozen=True)
class DocumentedUseCase:
    """One named provider behavior that a Gherkin scenario may select."""

    name: str
    summary: str
    support: UseCaseSupport
    payload_traits: frozenset[PayloadTrait] = field(default_factory=frozenset)
    property_hints: Mapping[str, str] = field(default_factory=dict)
    constraints: tuple[str, ...] = ()
    expected_error: ExpectedApiError | None = None
    sources: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        """Makes collection metadata immutable for deterministic BDD selection."""
        if not self.name:
            raise ValueError("Documented use cases must have a name.")
        negative_support = {UseCaseSupport.INVALID, UseCaseSupport.UNSUPPORTED}
        if self.support in negative_support and self.expected_error is None:
            raise ValueError("Negative use cases must declare a public expected error.")
        if self.support not in negative_support and self.expected_error is not None:
            raise ValueError("Successful or observational use cases cannot declare an expected error.")
        object.__setattr__(self, "payload_traits", frozenset(self.payload_traits))
        object.__setattr__(self, "property_hints", MappingProxyType(dict(self.property_hints)))
        object.__setattr__(self, "constraints", tuple(self.constraints))
        object.__setattr__(self, "sources", tuple(self.sources))

    @property
    def should_succeed(self) -> bool:
        """Returns whether a configured provider is expected to accept the scenario."""
        return self.support not in {UseCaseSupport.INVALID, UseCaseSupport.UNSUPPORTED}


@dataclass(frozen=True)
class DeclaredErrorRecord:
    """A non-blocking error expectation or an explicitly tracked contract gap."""

    scenario: str
    reason: str
    expected: ExpectedApiError | None
    sources: tuple[str, ...] = ()
    non_blocking: bool = True

    def __post_init__(self) -> None:
        """Normalizes source metadata used in warning output."""
        if not self.scenario:
            raise ValueError("Error records must identify a scenario.")
        object.__setattr__(self, "sources", tuple(self.sources))

    @property
    def is_contract_gap(self) -> bool:
        """Returns true when V1 has no stable public error for this scenario yet."""
        return self.expected is None

    def warning_reason(self, provider: str | None = None) -> str:
        """Formats a concise reason for the transport's non-blocking warning."""
        prefix = f"{provider}: " if provider else ""
        return f"{prefix}{self.reason}"


@dataclass(frozen=True)
class ProviderProfile:
    """The documented create-table behavior of one relational catalog provider."""

    name: str
    display_name: str
    cleanup_mode: CleanupMode
    use_cases: Mapping[str, DocumentedUseCase]
    sources: tuple[str, ...]
    setup_notes: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        """Makes the catalog use-case lookup immutable and self-consistent."""
        if not self.name:
            raise ValueError("Provider profiles must have a name.")
        copied_use_cases = dict(self.use_cases)
        for name, use_case in copied_use_cases.items():
            if name != use_case.name:
                raise ValueError(
                    f"Use case key {name!r} does not match {use_case.name!r}."
                )
        object.__setattr__(self, "use_cases", MappingProxyType(copied_use_cases))
        object.__setattr__(self, "sources", tuple(self.sources))
        object.__setattr__(self, "setup_notes", tuple(self.setup_notes))

    def use_case(self, name: str) -> DocumentedUseCase:
        """Returns one documented use case or explains which names are available."""
        try:
            return self.use_cases[name]
        except KeyError as error:
            available = ", ".join(sorted(self.use_cases))
            raise KeyError(
                f"Unknown {self.name} create-table use case {name!r}. "
                f"Available use cases: {available}."
            ) from error

    def error_record_for(self, name: str) -> DeclaredErrorRecord | None:
        """Returns the non-blocking error declaration for an unsupported use case."""
        use_case = self.use_case(name)
        if use_case.expected_error is None:
            return None
        return DeclaredErrorRecord(
            scenario=f"{self.name}/{name}",
            reason=use_case.summary,
            expected=use_case.expected_error,
            sources=use_case.sources,
        )


INVALID_ARGUMENT = ExpectedApiError(400, "INVALID_ARGUMENT", False)
UNAUTHENTICATED = ExpectedApiError(401, "UNAUTHENTICATED", False)
PERMISSION_DENIED = ExpectedApiError(403, "PERMISSION_DENIED", False)
METALAKE_NOT_FOUND = ExpectedApiError(404, "METALAKE_NOT_FOUND", False)
CATALOG_NOT_FOUND = ExpectedApiError(404, "CATALOG_NOT_FOUND", False)
SCHEMA_NOT_FOUND = ExpectedApiError(404, "SCHEMA_NOT_FOUND", False)
TABLE_NOT_FOUND = ExpectedApiError(404, "TABLE_NOT_FOUND", False)
RESOURCE_NOT_ACTIVE = ExpectedApiError(409, "RESOURCE_NOT_ACTIVE", False)
TABLE_ALREADY_EXISTS = ExpectedApiError(409, "TABLE_ALREADY_EXISTS", False)
UNSUPPORTED_OPERATION = ExpectedApiError(501, "UNSUPPORTED_OPERATION", False)


def _case(
    name: str,
    summary: str,
    support: UseCaseSupport = UseCaseSupport.SUPPORTED,
    *,
    traits: tuple[PayloadTrait, ...] = (),
    hints: Mapping[str, str] | None = None,
    constraints: tuple[str, ...] = (),
    expected_error: ExpectedApiError | None = None,
    sources: tuple[str, ...] = (),
) -> DocumentedUseCase:
    """Creates concise immutable profile data without constructing a wire payload."""
    return DocumentedUseCase(
        name=name,
        summary=summary,
        support=support,
        payload_traits=frozenset(traits),
        property_hints={} if hints is None else hints,
        constraints=constraints,
        expected_error=expected_error,
        sources=sources,
    )


def _profile(
    name: str,
    display_name: str,
    cleanup_mode: CleanupMode,
    use_cases: tuple[DocumentedUseCase, ...],
    *,
    sources: tuple[str, ...],
    setup_notes: tuple[str, ...] = (),
) -> ProviderProfile:
    """Creates one provider profile with a direct named-use-case lookup."""
    return ProviderProfile(
        name=name,
        display_name=display_name,
        cleanup_mode=cleanup_mode,
        use_cases={use_case.name: use_case for use_case in use_cases},
        sources=sources,
        setup_notes=setup_notes,
    )


HIVE_SOURCE = "docs/apache-hive-catalog.md#table"
ICEBERG_SOURCE = "docs/lakehouse-iceberg-catalog.md#table"
PAIMON_SOURCE = "docs/lakehouse-paimon-catalog.md#table"
HUDI_SOURCE = "docs/lakehouse-hudi-catalog.md#table"
LANCE_SOURCE = "docs/lakehouse-generic-lance-table.md#table-management"
DELTA_SOURCE = "docs/lakehouse-generic-delta-table.md#table-management"
GLUE_SOURCE = "docs/aws-glue-catalog.md#table"
MYSQL_SOURCE = "docs/jdbc-mysql-catalog.md#table"
POSTGRESQL_SOURCE = "docs/jdbc-postgresql-catalog.md#table"
DORIS_SOURCE = "docs/jdbc-doris-catalog.md#table"
STARROCKS_SOURCE = "docs/jdbc-starrocks-catalog.md#table"
OCEANBASE_SOURCE = "docs/jdbc-oceanbase-catalog.md#table"
CLICKHOUSE_SOURCE = "docs/jdbc-clickhouse-catalog.md#table"
HOLOGRES_SOURCE = "docs/jdbc-hologres-catalog.md#table"
RELATIONAL_SOURCE = "docs/manage-relational-metadata-using-gravitino.md#table-operations"
LAYOUT_SOURCE = "docs/table-partitioning-distribution-sort-order-indexes.md"
V1_ERROR_SOURCE = "docs/open-api/v1/components/schemas/errors.yaml"


PROFILES: Mapping[str, ProviderProfile] = MappingProxyType(
    {
        "hive": _profile(
            "hive",
            "Apache Hive",
            CleanupMode.PURGE,
            (
                _case(
                    "minimal",
                    "Create a managed Hive table with primitive columns.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.MANAGED_TABLE),
                    sources=(HIVE_SOURCE, RELATIONAL_SOURCE),
                ),
                _case(
                    "comment_and_properties",
                    "Create a Hive table with a comment and storage properties.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMMENT, PayloadTrait.TABLE_PROPERTIES),
                    hints={"format": "ORC"},
                    sources=(HIVE_SOURCE,),
                ),
                _case(
                    "complex_schema",
                    "Create a Hive table using documented complex types.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMPLEX_SCHEMA),
                    constraints=(
                        "Do not attach comments to struct fields; Hive documents that as an error.",
                    ),
                    sources=(HIVE_SOURCE,),
                ),
                _case(
                    "identity_partition_hash_distribution_sort",
                    "Create a Hive bucketed, sorted table with an identity partition.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.PARTITION_IDENTITY,
                        PayloadTrait.DISTRIBUTION_HASH,
                        PayloadTrait.SORT_ORDER,
                    ),
                    constraints=(
                        "Partition, distribution, and sort fields must name declared columns.",
                    ),
                    sources=(HIVE_SOURCE, LAYOUT_SOURCE),
                ),
                _case(
                    "external_table",
                    "Create an external Hive table at a caller-supplied location.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.EXTERNAL_TABLE,
                        PayloadTrait.STORAGE_LOCATION,
                    ),
                    hints={"table-type": "EXTERNAL_TABLE", "location": "<table-location>"},
                    sources=(HIVE_SOURCE,),
                ),
                _case(
                    "default_values",
                    "Hive does not support column default values.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COLUMN_DEFAULT),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(HIVE_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "indexes",
                    "Hive does not support table indexes through this catalog.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(HIVE_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "unsupported_layout",
                    "Hive supports only identity partitioning and hash distribution.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.PARTITION_TRANSFORM, PayloadTrait.DISTRIBUTION_RANGE),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(HIVE_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(HIVE_SOURCE, RELATIONAL_SOURCE),
        ),
        "iceberg": _profile(
            "iceberg",
            "Apache Iceberg",
            CleanupMode.PURGE,
            (
                _case(
                    "minimal",
                    "Create a managed Iceberg table with primitive columns.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.MANAGED_TABLE),
                    sources=(ICEBERG_SOURCE, RELATIONAL_SOURCE),
                ),
                _case(
                    "comment_and_properties",
                    "Create an Iceberg table with documented Iceberg properties.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMMENT, PayloadTrait.TABLE_PROPERTIES),
                    hints={"format-version": "2"},
                    sources=(ICEBERG_SOURCE,),
                ),
                _case(
                    "complex_schema",
                    "Create an Iceberg table using struct, list, map, and supported primitive types.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMPLEX_SCHEMA),
                    constraints=(
                        "Do not use varchar, fixedchar, byte, short, or union Gravitino types.",
                    ),
                    sources=(ICEBERG_SOURCE,),
                ),
                _case(
                    "partition_sort_and_distribution",
                    "Create an Iceberg table with supported partition transforms, sort order, and distribution.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.PARTITION_TRANSFORM,
                        PayloadTrait.SORT_ORDER,
                        PayloadTrait.DISTRIBUTION_HASH,
                        PayloadTrait.DISTRIBUTION_RANGE,
                    ),
                    constraints=(
                        "Bucket transforms accept one field; even distribution is unsupported.",
                    ),
                    sources=(ICEBERG_SOURCE, LAYOUT_SOURCE),
                ),
                _case(
                    "iceberg_partition_transforms",
                    "Create an Iceberg table with identity, bucket, truncate, or time partition transforms.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PARTITION_TRANSFORM),
                    constraints=("Apply, range, and list transforms are unsupported.",),
                    sources=(ICEBERG_SOURCE,),
                ),
                _case(
                    "default_values",
                    "Iceberg does not support column default values through this catalog.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COLUMN_DEFAULT),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(ICEBERG_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "indexes",
                    "Iceberg does not support table indexes through this catalog.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(ICEBERG_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "unsupported_layout",
                    "Iceberg does not support even distribution or apply, range, and list partition transforms.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.DISTRIBUTION_EVEN, PayloadTrait.PARTITION_RANGE),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(ICEBERG_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(ICEBERG_SOURCE, RELATIONAL_SOURCE),
        ),
        "paimon": _profile(
            "paimon",
            "Apache Paimon",
            CleanupMode.PURGE,
            (
                _case(
                    "minimal",
                    "Create a Paimon table with primitive columns.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.MANAGED_TABLE),
                    sources=(PAIMON_SOURCE,),
                ),
                _case(
                    "comment_and_properties",
                    "Create a Paimon table with documented table properties.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMMENT, PayloadTrait.TABLE_PROPERTIES),
                    hints={"merge-engine": "<paimon-merge-engine>"},
                    sources=(PAIMON_SOURCE,),
                ),
                _case(
                    "complex_schema",
                    "Create a Paimon table using documented struct, map, and list types.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMPLEX_SCHEMA),
                    sources=(PAIMON_SOURCE,),
                ),
                _case(
                    "identity_partition_and_primary_key",
                    "Create a Paimon table with identity partitions and one primary-key index.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.PARTITION_IDENTITY,
                        PayloadTrait.PRIMARY_KEY,
                    ),
                    constraints=(
                        "Use exactly one primary key and do not make it identical to partition fields.",
                    ),
                    sources=(PAIMON_SOURCE,),
                ),
                _case(
                    "indexes",
                    "Create a Paimon table with one primary-key index.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY),
                    constraints=("Only one primary-key index is supported.",),
                    sources=(PAIMON_SOURCE,),
                ),
                _case(
                    "primary_key_index",
                    "Create a Paimon table with its documented single primary-key index.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY),
                    constraints=("Only one primary-key index is supported.",),
                    sources=(PAIMON_SOURCE,),
                ),
                _case(
                    "default_values_via_properties",
                    "Create a Paimon table with a documented property-based default value.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PROPERTY_DEFAULT, PayloadTrait.TABLE_PROPERTIES),
                    hints={"fields.<column>.default-value": "<literal-default>"},
                    constraints=("Paimon defaults are table properties, not column expressions.",),
                    sources=(PAIMON_SOURCE,),
                ),
                _case(
                    "property_default_values",
                    "Create a Paimon table with a documented property-based default value.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PROPERTY_DEFAULT, PayloadTrait.TABLE_PROPERTIES),
                    hints={"fields.<column>.default-value": "<literal-default>"},
                    constraints=("Paimon defaults are table properties, not column expressions.",),
                    sources=(PAIMON_SOURCE,),
                ),
                _case(
                    "default_values",
                    "Paimon does not accept generic column-default expressions; use table properties instead.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COLUMN_DEFAULT),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(PAIMON_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "column_field_default_values",
                    "Paimon does not accept generic column-default expressions; use table properties instead.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COLUMN_DEFAULT),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(PAIMON_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "defaults_and_auto_increment",
                    "Paimon does not support auto-increment columns.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.AUTO_INCREMENT),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(PAIMON_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "auto_increment",
                    "Paimon does not support auto-increment columns.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.AUTO_INCREMENT),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(PAIMON_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "unsupported_layout",
                    "Paimon does not support sort orders or table distributions.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.SORT_ORDER, PayloadTrait.DISTRIBUTION_HASH),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(PAIMON_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(PAIMON_SOURCE, RELATIONAL_SOURCE),
            setup_notes=(
                "Use purge for cleanup: Paimon does not support dropTable through Gravitino.",
                "The documentation reserves bucket settings even though its capabilities section says distribution is unsupported; do not add a distribution CUJ until the contract is clarified.",
            ),
        ),
        "hudi": _profile(
            "hudi",
            "Apache Hudi",
            CleanupMode.NONE,
            (
                _case(
                    "minimal",
                    "Hudi catalogs are read-only and do not support creating tables.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS,),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(HUDI_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "unsupported_layout",
                    "Hudi catalogs are read-only, so a table-layout create request is unsupported.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.PARTITION_IDENTITY,),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(HUDI_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(HUDI_SOURCE,),
            setup_notes=("Hudi CUJs cover list/load/tableExists separately; they do not own tables.",),
        ),
        "lance": _profile(
            "lance",
            "Generic Lakehouse Lance",
            CleanupMode.DROP,
            (
                _case(
                    "minimal",
                    "Create a managed Lance table with a schema and location.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.MANAGED_TABLE,
                        PayloadTrait.STORAGE_LOCATION,
                    ),
                    hints={"format": "lance", "location": "<table-location>"},
                    sources=(LANCE_SOURCE,),
                ),
                _case(
                    "comment_and_properties",
                    "Create a Lance table with a comment and supported storage properties.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.COMMENT,
                        PayloadTrait.TABLE_PROPERTIES,
                        PayloadTrait.STORAGE_LOCATION,
                    ),
                    hints={"format": "lance", "location": "<table-location>"},
                    sources=(LANCE_SOURCE,),
                ),
                _case(
                    "complex_schema",
                    "Create a Lance table with documented Arrow-compatible complex types.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMPLEX_SCHEMA),
                    constraints=("Do not use Map or Interval_year types.",),
                    sources=(LANCE_SOURCE,),
                ),
                _case(
                    "managed_table",
                    "Create a managed Lance dataset and initialize its data directory.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.MANAGED_TABLE,
                        PayloadTrait.STORAGE_LOCATION,
                    ),
                    hints={"format": "lance", "location": "<table-location>"},
                    sources=(LANCE_SOURCE,),
                ),
                _case(
                    "external_table",
                    "Create a Lance table marked external so drop preserves its data directory.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.EXTERNAL_TABLE,
                        PayloadTrait.STORAGE_LOCATION,
                    ),
                    hints={"format": "lance", "external": "true", "location": "<table-location>"},
                    sources=(LANCE_SOURCE,),
                ),
                _case(
                    "registered_table",
                    "Register an existing Lance dataset without creating a data directory.",
                    traits=(
                        PayloadTrait.REGISTER_EXISTING,
                        PayloadTrait.STORAGE_LOCATION,
                        PayloadTrait.EXTERNAL_TABLE,
                    ),
                    hints={"format": "lance", "lance.register": "true", "location": "<existing-location>"},
                    constraints=("Columns may be empty because Lance can detect the existing schema.",),
                    sources=(LANCE_SOURCE,),
                ),
                _case(
                    "registered_table_schema_discovery",
                    "Observe Lance schema discovery when registering an existing dataset without caller columns.",
                    UseCaseSupport.CONDITIONAL,
                    traits=(
                        PayloadTrait.REGISTER_EXISTING,
                        PayloadTrait.STORAGE_LOCATION,
                    ),
                    hints={"format": "lance", "lance.register": "true", "location": "<existing-location>"},
                    constraints=(
                        "This is an observational journey: record the loaded schema without fabricating a public error expectation.",
                    ),
                    sources=(LANCE_SOURCE,),
                ),
                _case(
                    "declared_table",
                    "Exercise a Lance declared-table schema-refresh state when the configured runtime exposes it.",
                    UseCaseSupport.CONDITIONAL,
                    traits=(PayloadTrait.DECLARE_METADATA_ONLY, PayloadTrait.STORAGE_LOCATION),
                    hints={"lance.declared": "true", "location": "<table-location>"},
                    constraints=(
                        "The public table-properties documentation does not yet describe a create-time lance.declared property; enable this only after the V1 mapper explicitly supports it.",
                    ),
                    sources=(
                        LANCE_SOURCE,
                        "catalogs/catalog-lakehouse-generic/src/main/java/org/apache/gravitino/catalog/lakehouse/lance/LanceTableOperations.java",
                    ),
                ),
                _case(
                    "creation_mode_exist_ok",
                    "Create a Lance table with the documented EXIST_OK creation mode.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.CREATE_MODE, PayloadTrait.STORAGE_LOCATION),
                    hints={"format": "lance", "lance.creation-mode": "EXIST_OK"},
                    sources=(LANCE_SOURCE,),
                ),
                _case(
                    "creation_mode_overwrite",
                    "Create a Lance table with the documented OVERWRITE creation mode.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.CREATE_MODE, PayloadTrait.STORAGE_LOCATION),
                    hints={"format": "lance", "lance.creation-mode": "OVERWRITE"},
                    constraints=("The fixture must own the target directory because overwrite deletes it.",),
                    sources=(LANCE_SOURCE,),
                ),
                _case(
                    "unsupported_layout",
                    "Lance table creation does not support partitioning, sort orders, distributions, or indexes.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.PARTITION_IDENTITY, PayloadTrait.SORT_ORDER),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(LANCE_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "auto_increment",
                    "Generic lakehouse tables do not support auto-increment columns.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.AUTO_INCREMENT),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(RELATIONAL_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "blank_table_name",
                    "A blank table name is invalid request input.",
                    UseCaseSupport.INVALID,
                    traits=(PayloadTrait.COLUMNS,),
                    expected_error=INVALID_ARGUMENT,
                    sources=(V1_ERROR_SOURCE,),
                ),
                _case(
                    "missing_columns",
                    "A create-table request without its required column definition is invalid.",
                    UseCaseSupport.INVALID,
                    expected_error=INVALID_ARGUMENT,
                    sources=(V1_ERROR_SOURCE,),
                ),
                _case(
                    "duplicate_column_names",
                    "Duplicate column names are invalid request input.",
                    UseCaseSupport.INVALID,
                    traits=(PayloadTrait.COLUMNS,),
                    expected_error=INVALID_ARGUMENT,
                    sources=(V1_ERROR_SOURCE,),
                ),
                _case(
                    "unknown_layout_field",
                    "A layout reference that does not name a declared column is invalid request input.",
                    UseCaseSupport.INVALID,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PARTITION_IDENTITY),
                    expected_error=INVALID_ARGUMENT,
                    sources=(LAYOUT_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(LANCE_SOURCE, RELATIONAL_SOURCE),
            setup_notes=(
                "Location must be configured at the catalog, schema, or table level.",
                "Only purge an external table when the fixture owns its data directory.",
            ),
        ),
        "delta": _profile(
            "delta",
            "Generic Lakehouse Delta",
            CleanupMode.DROP_METADATA_ONLY,
            (
                _case(
                    "minimal",
                    "Generic Delta supports registration of external tables, not managed-table creation.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.MANAGED_TABLE),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(DELTA_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "register_existing_delta",
                    "Register an existing external Delta table with its schema and location.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.REGISTER_EXISTING,
                        PayloadTrait.EXTERNAL_TABLE,
                        PayloadTrait.STORAGE_LOCATION,
                    ),
                    hints={"format": "delta", "external": "true", "location": "<existing-delta-location>"},
                    constraints=("The location must contain a Delta _delta_log and schema is not validated against it.",),
                    sources=(DELTA_SOURCE,),
                ),
                _case(
                    "external_table",
                    "Register an external Delta table and preserve its physical data on drop.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.REGISTER_EXISTING,
                        PayloadTrait.EXTERNAL_TABLE,
                        PayloadTrait.STORAGE_LOCATION,
                    ),
                    hints={"format": "delta", "external": "true", "location": "<existing-delta-location>"},
                    sources=(DELTA_SOURCE,),
                ),
                _case(
                    "identity_partition_metadata",
                    "Register an external Delta table with identity partition metadata.",
                    UseCaseSupport.METADATA_ONLY,
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.EXTERNAL_TABLE,
                        PayloadTrait.STORAGE_LOCATION,
                        PayloadTrait.PARTITION_IDENTITY,
                    ),
                    constraints=("The declared partition metadata must match the Delta transaction log; Gravitino does not validate it.",),
                    sources=(DELTA_SOURCE,),
                ),
                _case(
                    "complex_schema",
                    "Register an external Delta table with documented Spark complex types.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMPLEX_SCHEMA, PayloadTrait.EXTERNAL_TABLE),
                    sources=(DELTA_SOURCE,),
                ),
                _case(
                    "unsupported_layout",
                    "Delta registration does not support sort orders, distributions, or indexes.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.SORT_ORDER, PayloadTrait.DISTRIBUTION_HASH, PayloadTrait.PRIMARY_KEY),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(DELTA_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "purge_table",
                    "Delta external tables cannot be purged through Gravitino.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.EXTERNAL_TABLE,),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(DELTA_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(DELTA_SOURCE, RELATIONAL_SOURCE),
            setup_notes=("Drop removes only Gravitino metadata; fixtures must clean Delta files themselves if they created them.",),
        ),
        "glue": _profile(
            "glue",
            "AWS Glue",
            CleanupMode.DROP,
            (
                _case(
                    "minimal",
                    "Create a Glue table using the catalog default Hive or Iceberg table format.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.MANAGED_TABLE),
                    sources=(GLUE_SOURCE,),
                ),
                _case(
                    "comment_and_properties",
                    "Create a Glue table with a comment and documented table parameters.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMMENT, PayloadTrait.TABLE_PROPERTIES),
                    hints={"format": "parquet"},
                    sources=(GLUE_SOURCE,),
                ),
                _case(
                    "complex_schema",
                    "Create a Glue table with documented Hive-compatible complex column types.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMPLEX_SCHEMA),
                    constraints=("Glue does not support NOT NULL column constraints.",),
                    sources=(GLUE_SOURCE,),
                ),
                _case(
                    "hive_identity_partition_hash_distribution_sort",
                    "Create a Hive-format Glue table with identity partitions, hash distribution, and sort order.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.PARTITION_IDENTITY,
                        PayloadTrait.DISTRIBUTION_HASH,
                        PayloadTrait.SORT_ORDER,
                    ),
                    hints={"table-format": "HIVE"},
                    constraints=("Every layout field must name a declared column.",),
                    sources=(GLUE_SOURCE,),
                ),
                _case(
                    "iceberg_partition_transforms",
                    "Create an Iceberg-format Glue table with documented Iceberg partition transforms.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PARTITION_TRANSFORM),
                    hints={"table-format": "ICEBERG", "warehouse": "<catalog-warehouse>"},
                    sources=(GLUE_SOURCE,),
                ),
                _case(
                    "iceberg_managed_table",
                    "Create a managed Iceberg table through Glue and write its metadata to S3.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.MANAGED_TABLE, PayloadTrait.STORAGE_LOCATION),
                    hints={"table-format": "ICEBERG", "warehouse": "<catalog-warehouse>"},
                    sources=(GLUE_SOURCE,),
                ),
                _case(
                    "register_existing_iceberg",
                    "Register an existing Iceberg table using its metadata_location.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.REGISTER_EXISTING, PayloadTrait.STORAGE_LOCATION),
                    hints={"table-format": "ICEBERG", "metadata_location": "<metadata-json-location>"},
                    sources=(GLUE_SOURCE,),
                ),
                _case(
                    "default_values",
                    "Glue does not support column default values.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COLUMN_DEFAULT),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(GLUE_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "not_null_columns",
                    "Glue does not support NOT NULL column constraints.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.NOT_NULL),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(GLUE_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "indexes",
                    "Glue does not support table indexes.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(GLUE_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(GLUE_SOURCE, RELATIONAL_SOURCE),
            setup_notes=("Glue table names are case-insensitive and folded to lowercase.",),
        ),
        "mysql": _profile(
            "mysql",
            "MySQL",
            CleanupMode.DROP,
            (
                _case(
                    "minimal",
                    "Create a basic MySQL table through JDBC.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.MANAGED_TABLE),
                    sources=(MYSQL_SOURCE,),
                ),
                _case(
                    "comment_and_properties",
                    "Create a MySQL table with documented storage-engine properties.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMMENT, PayloadTrait.TABLE_PROPERTIES),
                    hints={"engine": "InnoDB"},
                    sources=(MYSQL_SOURCE,),
                ),
                _case(
                    "parameterized_schema",
                    "Create a MySQL table with documented parameterized scalar column types.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PARAMETERIZED_SCHEMA),
                    sources=(MYSQL_SOURCE,),
                ),
                _case(
                    "default_values",
                    "Create a MySQL table with column default values.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COLUMN_DEFAULT),
                    sources=(MYSQL_SOURCE,),
                ),
                _case(
                    "defaults_and_auto_increment",
                    "Create a MySQL auto-increment column with its required unique index.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.COLUMN_DEFAULT,
                        PayloadTrait.AUTO_INCREMENT,
                        PayloadTrait.PRIMARY_KEY,
                    ),
                    constraints=("MySQL requires an auto-increment column to have a unique index.",),
                    sources=(MYSQL_SOURCE,),
                ),
                _case(
                    "indexes",
                    "Create MySQL primary-key and unique-key indexes.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY, PayloadTrait.UNIQUE_KEY),
                    constraints=("The primary key index name must be PRIMARY.",),
                    sources=(MYSQL_SOURCE,),
                ),
                _case(
                    "primary_and_unique_indexes",
                    "Create MySQL primary-key and unique-key indexes.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY, PayloadTrait.UNIQUE_KEY),
                    constraints=("The primary key index name must be PRIMARY.",),
                    sources=(MYSQL_SOURCE,),
                ),
                _case(
                    "auto_increment_without_key",
                    "MySQL rejects an auto-increment column without a unique index.",
                    UseCaseSupport.INVALID,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.AUTO_INCREMENT),
                    expected_error=INVALID_ARGUMENT,
                    sources=(MYSQL_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(MYSQL_SOURCE, RELATIONAL_SOURCE),
        ),
        "postgresql": _profile(
            "postgresql",
            "PostgreSQL",
            CleanupMode.DROP,
            (
                _case(
                    "minimal",
                    "Create a basic PostgreSQL table through JDBC.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.MANAGED_TABLE),
                    sources=(POSTGRESQL_SOURCE,),
                ),
                _case(
                    "parameterized_schema",
                    "Create a PostgreSQL table with documented parameterized scalar and array types.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PARAMETERIZED_SCHEMA),
                    sources=(POSTGRESQL_SOURCE,),
                ),
                _case(
                    "default_values",
                    "Create a PostgreSQL table with column default values.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COLUMN_DEFAULT),
                    sources=(POSTGRESQL_SOURCE,),
                ),
                _case(
                    "defaults_and_auto_increment",
                    "Create a PostgreSQL table with defaults and an auto-increment column.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.COLUMN_DEFAULT,
                        PayloadTrait.AUTO_INCREMENT,
                    ),
                    sources=(POSTGRESQL_SOURCE,),
                ),
                _case(
                    "indexes",
                    "Create PostgreSQL primary-key and unique-key indexes.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY, PayloadTrait.UNIQUE_KEY),
                    sources=(POSTGRESQL_SOURCE,),
                ),
                _case(
                    "primary_and_unique_indexes",
                    "Create PostgreSQL primary-key and unique-key indexes.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY, PayloadTrait.UNIQUE_KEY),
                    sources=(POSTGRESQL_SOURCE,),
                ),
                _case(
                    "comment_and_properties",
                    "PostgreSQL does not support table property settings through this catalog.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.TABLE_PROPERTIES),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(POSTGRESQL_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(POSTGRESQL_SOURCE, RELATIONAL_SOURCE),
        ),
        "doris": _profile(
            "doris",
            "Apache Doris",
            CleanupMode.DROP,
            (
                _case(
                    "minimal",
                    "Create a basic Doris table through JDBC.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.MANAGED_TABLE),
                    sources=(DORIS_SOURCE,),
                ),
                _case(
                    "comment_and_properties",
                    "Create a Doris table with documented Doris-specific table properties.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMMENT, PayloadTrait.TABLE_PROPERTIES),
                    sources=(DORIS_SOURCE,),
                ),
                _case(
                    "default_values",
                    "Create a Doris table with documented column default values.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COLUMN_DEFAULT),
                    sources=(DORIS_SOURCE,),
                ),
                _case(
                    "indexes",
                    "Create a Doris table with a single-column primary-key index.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY),
                    constraints=("A Doris primary-key index can apply to only one column.",),
                    sources=(DORIS_SOURCE,),
                ),
                _case(
                    "primary_key_index",
                    "Create a Doris table with its documented single-column primary-key index.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY),
                    constraints=("A Doris primary-key index can apply to only one column.",),
                    sources=(DORIS_SOURCE,),
                ),
                _case(
                    "partition_and_required_distribution",
                    "Create a Doris table with RANGE or LIST partitioning and HASH or EVEN distribution.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.PARTITION_RANGE,
                        PayloadTrait.PARTITION_LIST,
                        PayloadTrait.DISTRIBUTION_HASH,
                        PayloadTrait.DISTRIBUTION_EVEN,
                    ),
                    constraints=("Partition fields must be declared columns.",),
                    sources=(DORIS_SOURCE, LAYOUT_SOURCE),
                ),
                _case(
                    "defaults_and_auto_increment",
                    "Doris does not support auto-increment columns.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.AUTO_INCREMENT),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(DORIS_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(DORIS_SOURCE, RELATIONAL_SOURCE),
        ),
        "starrocks": _profile(
            "starrocks",
            "StarRocks",
            CleanupMode.DROP,
            (
                _case(
                    "minimal",
                    "Create a basic StarRocks table through JDBC.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.MANAGED_TABLE),
                    sources=(STARROCKS_SOURCE,),
                ),
                _case(
                    "comment_and_properties",
                    "Create a StarRocks table with documented StarRocks-specific table properties.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMMENT, PayloadTrait.TABLE_PROPERTIES),
                    sources=(STARROCKS_SOURCE,),
                ),
                _case(
                    "default_values",
                    "Create a StarRocks table with documented column default values.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COLUMN_DEFAULT),
                    sources=(STARROCKS_SOURCE,),
                ),
                _case(
                    "partition_and_required_distribution",
                    "Create a StarRocks table with RANGE or LIST partitioning and HASH or EVEN distribution.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.PARTITION_RANGE,
                        PayloadTrait.PARTITION_LIST,
                        PayloadTrait.DISTRIBUTION_HASH,
                        PayloadTrait.DISTRIBUTION_EVEN,
                    ),
                    constraints=("Partition fields must be declared columns.",),
                    sources=(STARROCKS_SOURCE, LAYOUT_SOURCE),
                ),
                _case(
                    "indexes",
                    "StarRocks does not support table indexes through this catalog.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(STARROCKS_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "defaults_and_auto_increment",
                    "StarRocks does not support auto-increment columns.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.AUTO_INCREMENT),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(STARROCKS_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "auto_increment",
                    "Resolve the documented StarRocks auto-increment conflict before enabling this CUJ.",
                    UseCaseSupport.CONDITIONAL,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.AUTO_INCREMENT),
                    constraints=(
                        "The connector page says auto-increment is unsupported, while the shared relational guide lists jdbc-starrocks as supported.",
                    ),
                    sources=(STARROCKS_SOURCE, RELATIONAL_SOURCE),
                ),
                _case(
                    "unsupported_layout",
                    "StarRocks does not document sort-order support for create-table requests.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.SORT_ORDER,),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(STARROCKS_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(STARROCKS_SOURCE, RELATIONAL_SOURCE),
            setup_notes=("Schema alterations are asynchronous; do not reuse that timing assumption for create-table CUJs.",),
        ),
        "oceanbase": _profile(
            "oceanbase",
            "OceanBase",
            CleanupMode.DROP,
            (
                _case(
                    "minimal",
                    "Create a basic OceanBase table through JDBC.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.MANAGED_TABLE),
                    sources=(OCEANBASE_SOURCE,),
                ),
                _case(
                    "parameterized_schema",
                    "Create an OceanBase table with documented parameterized scalar types.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PARAMETERIZED_SCHEMA),
                    sources=(OCEANBASE_SOURCE,),
                ),
                _case(
                    "default_values",
                    "Create an OceanBase table with column default values.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COLUMN_DEFAULT),
                    sources=(OCEANBASE_SOURCE,),
                ),
                _case(
                    "defaults_and_auto_increment",
                    "Create an OceanBase auto-increment column with its required unique index.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.COLUMN_DEFAULT,
                        PayloadTrait.AUTO_INCREMENT,
                        PayloadTrait.PRIMARY_KEY,
                    ),
                    constraints=("OceanBase requires an auto-increment column to have a unique index.",),
                    sources=(OCEANBASE_SOURCE,),
                ),
                _case(
                    "indexes",
                    "Create OceanBase primary-key and unique-key indexes.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY, PayloadTrait.UNIQUE_KEY),
                    sources=(OCEANBASE_SOURCE,),
                ),
                _case(
                    "primary_and_unique_indexes",
                    "Create OceanBase primary-key and unique-key indexes.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY, PayloadTrait.UNIQUE_KEY),
                    sources=(OCEANBASE_SOURCE,),
                ),
                _case(
                    "auto_increment_without_key",
                    "OceanBase rejects an auto-increment column without a unique index.",
                    UseCaseSupport.INVALID,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.AUTO_INCREMENT),
                    expected_error=INVALID_ARGUMENT,
                    sources=(OCEANBASE_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "comment_and_properties",
                    "OceanBase does not support table property settings through this catalog.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.TABLE_PROPERTIES),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(OCEANBASE_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "unsupported_layout",
                    "OceanBase does not support creating partitioned tables through this catalog.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.PARTITION_IDENTITY,),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(OCEANBASE_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(OCEANBASE_SOURCE, RELATIONAL_SOURCE),
            setup_notes=("OceanBase is not included in the standard distribution as of the documented version.",),
        ),
        "clickhouse": _profile(
            "clickhouse",
            "ClickHouse",
            CleanupMode.DROP,
            (
                _case(
                    "minimal",
                    "Create a basic durable ClickHouse table using a documented MergeTree engine.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.MANAGED_TABLE,
                        PayloadTrait.ENGINE,
                        PayloadTrait.SORT_ORDER,
                        PayloadTrait.DISTRIBUTION_NONE,
                    ),
                    hints={"engine": "MergeTree"},
                    constraints=("MergeTree-family tables require one ORDER BY column.",),
                    sources=(CLICKHOUSE_SOURCE,),
                ),
                _case(
                    "comment_and_properties",
                    "Create a ClickHouse table with engine and SETTINGS properties.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.COMMENT,
                        PayloadTrait.TABLE_PROPERTIES,
                        PayloadTrait.ENGINE,
                        PayloadTrait.SORT_ORDER,
                        PayloadTrait.DISTRIBUTION_NONE,
                    ),
                    hints={"engine": "MergeTree", "settings.<name>": "<setting-value>"},
                    constraints=("MergeTree-family tables require one ORDER BY column.",),
                    sources=(CLICKHOUSE_SOURCE,),
                ),
                _case(
                    "parameterized_schema",
                    "Create a ClickHouse table with documented parameterized scalar types.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PARAMETERIZED_SCHEMA),
                    sources=(CLICKHOUSE_SOURCE,),
                ),
                _case(
                    "default_values",
                    "Create a ClickHouse table with documented column default values.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COLUMN_DEFAULT),
                    sources=(CLICKHOUSE_SOURCE,),
                ),
                _case(
                    "engine_partition_sort_and_primary_key",
                    "Create a MergeTree-family table with engine, order, optional partition, and primary key.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.ENGINE,
                        PayloadTrait.SORT_ORDER,
                        PayloadTrait.PARTITION_IDENTITY,
                        PayloadTrait.PRIMARY_KEY,
                        PayloadTrait.DISTRIBUTION_NONE,
                    ),
                    hints={"engine": "MergeTree"},
                    constraints=(
                        "MergeTree-family tables require one ORDER BY column.",
                        "Only single-column identity or documented function partitions are supported.",
                    ),
                    sources=(CLICKHOUSE_SOURCE,),
                ),
                _case(
                    "mergetree_order_and_primary_key",
                    "Create a MergeTree table with its required ORDER BY and documented primary-key form.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.ENGINE,
                        PayloadTrait.SORT_ORDER,
                        PayloadTrait.PRIMARY_KEY,
                        PayloadTrait.DISTRIBUTION_NONE,
                    ),
                    hints={"engine": "MergeTree"},
                    constraints=("MergeTree-family tables require one ORDER BY column.",),
                    sources=(CLICKHOUSE_SOURCE,),
                ),
                _case(
                    "indexes",
                    "Create ClickHouse primary-key and documented data-skipping indexes.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.PRIMARY_KEY,
                        PayloadTrait.DATA_SKIPPING_INDEX,
                        PayloadTrait.ENGINE,
                        PayloadTrait.SORT_ORDER,
                        PayloadTrait.DISTRIBUTION_NONE,
                    ),
                    hints={"engine": "MergeTree"},
                    constraints=("MergeTree-family index creation requires one ORDER BY column.",),
                    sources=(CLICKHOUSE_SOURCE,),
                ),
                _case(
                    "data_skipping_index",
                    "Create a ClickHouse MergeTree table with a documented data-skipping index.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.DATA_SKIPPING_INDEX,
                        PayloadTrait.ENGINE,
                        PayloadTrait.SORT_ORDER,
                        PayloadTrait.DISTRIBUTION_NONE,
                    ),
                    hints={"engine": "MergeTree"},
                    constraints=("MergeTree-family index creation requires one ORDER BY column.",),
                    sources=(CLICKHOUSE_SOURCE,),
                ),
                _case(
                    "unsupported_layout",
                    "ClickHouse accepts only Distributions.NONE; custom distribution strategies are unsupported.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.DISTRIBUTION_HASH,),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(CLICKHOUSE_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "defaults_and_auto_increment",
                    "ClickHouse does not support auto-increment columns.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.AUTO_INCREMENT),
                    expected_error=UNSUPPORTED_OPERATION,
                    sources=(CLICKHOUSE_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(CLICKHOUSE_SOURCE, RELATIONAL_SOURCE),
            setup_notes=(
                "Do not use the Memory engine for durable create-table CUJs.",
                "Distributed engines require remote database/table and sharding-key properties.",
            ),
        ),
        "hologres": _profile(
            "hologres",
            "Hologres",
            CleanupMode.DROP,
            (
                _case(
                    "minimal",
                    "Create a basic Hologres table through JDBC.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.MANAGED_TABLE),
                    sources=(HOLOGRES_SOURCE,),
                ),
                _case(
                    "comment_and_properties",
                    "Create a Hologres table with documented WITH-clause properties.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COMMENT, PayloadTrait.TABLE_PROPERTIES),
                    hints={"orientation": "column"},
                    sources=(HOLOGRES_SOURCE,),
                ),
                _case(
                    "parameterized_schema",
                    "Create a Hologres table with documented parameterized scalar and array types.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PARAMETERIZED_SCHEMA),
                    constraints=("Array element types must be non-nullable and arrays cannot be multidimensional.",),
                    sources=(HOLOGRES_SOURCE,),
                ),
                _case(
                    "default_values",
                    "Create a Hologres table with documented default or expression columns.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.COLUMN_DEFAULT),
                    sources=(HOLOGRES_SOURCE,),
                ),
                _case(
                    "distribution_and_partitioning",
                    "Create a Hologres table with hash distribution and LIST partitioning.",
                    traits=(
                        PayloadTrait.COLUMNS,
                        PayloadTrait.DISTRIBUTION_HASH,
                        PayloadTrait.PARTITION_LIST,
                    ),
                    constraints=(
                        "Physical LIST partitions use one column; logical LIST partitions use one or two columns and require a property.",
                    ),
                    sources=(HOLOGRES_SOURCE,),
                ),
                _case(
                    "indexes",
                    "Create a Hologres table with a primary-key index.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY),
                    sources=(HOLOGRES_SOURCE,),
                ),
                _case(
                    "primary_key_index",
                    "Create a Hologres table with its documented primary-key index.",
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.PRIMARY_KEY),
                    sources=(HOLOGRES_SOURCE,),
                ),
                _case(
                    "defaults_and_auto_increment",
                    "Hologres rejects auto-increment columns in CREATE TABLE.",
                    UseCaseSupport.UNSUPPORTED,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.AUTO_INCREMENT),
                    expected_error=INVALID_ARGUMENT,
                    sources=(HOLOGRES_SOURCE, V1_ERROR_SOURCE),
                ),
                _case(
                    "auto_increment",
                    "Hologres rejects auto-increment columns in CREATE TABLE.",
                    UseCaseSupport.INVALID,
                    traits=(PayloadTrait.COLUMNS, PayloadTrait.AUTO_INCREMENT),
                    expected_error=INVALID_ARGUMENT,
                    sources=(HOLOGRES_SOURCE, V1_ERROR_SOURCE),
                ),
            ),
            sources=(HOLOGRES_SOURCE, RELATIONAL_SOURCE),
        ),
    }
)


PROFILE_ALIASES: Mapping[str, str] = MappingProxyType(
    {
        "apache-hive": "hive",
        "aws-glue": "glue",
        "generic-delta": "delta",
        "generic-lance": "lance",
        "lakehouse-iceberg": "iceberg",
        "lakehouse-paimon": "paimon",
        "lakehouse-hudi": "hudi",
        "postgres": "postgresql",
    }
)


COMMON_CREATE_TABLE_ERRORS: Mapping[str, DeclaredErrorRecord] = MappingProxyType(
    {
        "blank_table_name": DeclaredErrorRecord(
            scenario="create_table/blank_table_name",
            reason="A blank table name is invalid request input.",
            expected=INVALID_ARGUMENT,
            sources=(V1_ERROR_SOURCE,),
        ),
        "missing_columns": DeclaredErrorRecord(
            scenario="create_table/missing_columns",
            reason="A create-table request without the required column definition is invalid.",
            expected=INVALID_ARGUMENT,
            sources=(V1_ERROR_SOURCE,),
        ),
        "duplicate_column_names": DeclaredErrorRecord(
            scenario="create_table/duplicate_column_names",
            reason="Duplicate column names are invalid request input.",
            expected=INVALID_ARGUMENT,
            sources=(V1_ERROR_SOURCE,),
        ),
        "unknown_layout_field": DeclaredErrorRecord(
            scenario="create_table/unknown_layout_field",
            reason="A partition, distribution, sort, or index reference must identify a declared column.",
            expected=INVALID_ARGUMENT,
            sources=(LAYOUT_SOURCE, V1_ERROR_SOURCE),
        ),
        "invalid_auto_increment": DeclaredErrorRecord(
            scenario="create_table/invalid_auto_increment",
            reason="An invalid auto-increment combination is invalid request input.",
            expected=INVALID_ARGUMENT,
            sources=(RELATIONAL_SOURCE, V1_ERROR_SOURCE),
        ),
        "missing_metalake": DeclaredErrorRecord(
            scenario="create_table/missing_metalake",
            reason="The requested metalake does not exist.",
            expected=METALAKE_NOT_FOUND,
            sources=(V1_ERROR_SOURCE,),
        ),
        "missing_catalog": DeclaredErrorRecord(
            scenario="create_table/missing_catalog",
            reason="The requested catalog does not exist.",
            expected=CATALOG_NOT_FOUND,
            sources=(V1_ERROR_SOURCE,),
        ),
        "missing_schema": DeclaredErrorRecord(
            scenario="create_table/missing_schema",
            reason="The requested schema does not exist.",
            expected=SCHEMA_NOT_FOUND,
            sources=(V1_ERROR_SOURCE,),
        ),
        "missing_table": DeclaredErrorRecord(
            scenario="create_table/missing_table",
            reason="The requested table does not exist.",
            expected=TABLE_NOT_FOUND,
            sources=(V1_ERROR_SOURCE,),
        ),
        "unauthenticated": DeclaredErrorRecord(
            scenario="create_table/unauthenticated",
            reason="The caller did not provide valid authentication credentials.",
            expected=UNAUTHENTICATED,
            sources=(V1_ERROR_SOURCE,),
        ),
        "permission_denied": DeclaredErrorRecord(
            scenario="create_table/permission_denied",
            reason="The caller lacks the create-table privilege on the target schema.",
            expected=PERMISSION_DENIED,
            sources=(V1_ERROR_SOURCE,),
        ),
        "inactive_catalog": DeclaredErrorRecord(
            scenario="create_table/inactive_catalog",
            reason="The catalog or parent resource is not active.",
            expected=RESOURCE_NOT_ACTIVE,
            sources=(RELATIONAL_SOURCE, V1_ERROR_SOURCE),
        ),
        "duplicate_table": DeclaredErrorRecord(
            scenario="create_table/duplicate_table",
            reason="The requested table name already exists in the target schema.",
            expected=TABLE_ALREADY_EXISTS,
            sources=(V1_ERROR_SOURCE,),
            non_blocking=False,
        ),
    }
)


def profile_for(name: str) -> ProviderProfile:
    """Returns a profile by canonical provider name or documented alias."""
    canonical_name = PROFILE_ALIASES.get(name.strip().lower(), name.strip().lower())
    try:
        return PROFILES[canonical_name]
    except KeyError as error:
        available = ", ".join(sorted(PROFILES))
        raise KeyError(
            f"Unknown create-table provider {name!r}. Available providers: {available}."
        ) from error


def documented_use_case(provider: str, use_case: str) -> DocumentedUseCase:
    """Returns a named documented use case for generic BDD step selection."""
    return profile_for(provider).use_case(use_case)


def declared_error_for(provider: str, use_case: str) -> DeclaredErrorRecord | None:
    """Returns a provider limitation as a non-blocking expected-error record."""
    return profile_for(provider).error_record_for(use_case)


def common_error_for(name: str) -> DeclaredErrorRecord:
    """Returns one cross-provider create-table public-error declaration."""
    try:
        return COMMON_CREATE_TABLE_ERRORS[name]
    except KeyError as error:
        available = ", ".join(sorted(COMMON_CREATE_TABLE_ERRORS))
        raise KeyError(
            f"Unknown create-table error case {name!r}. Available error cases: {available}."
        ) from error
