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

"""Target V1 create-table payloads for documented connector customer journeys.

This is deliberately a data-only layer.  It does not import Playwright, a
generated client, or a legacy REST model, and it never sends a request.  Its
payloads are the intended ``POST /api/v1/.../tables`` request bodies: they use
the V1 table, type, expression, partition, distribution, and index wire shapes
already published for table reads.  A future generated ``CreateTableRequest``
model can consume each returned body directly.

Every provider/use-case pair currently named by the create-table Gherkin
features has a concrete body.  Provider limitations and migration gaps are
represented as expected outcomes, not skipped payload construction.  The
caller decides whether an expected-error outcome is a strict contract assertion
or a non-blocking documented limitation.
"""

from __future__ import annotations

from collections.abc import Callable, Mapping, Sequence
from copy import deepcopy
from dataclasses import dataclass
from enum import Enum
from functools import partial
from types import MappingProxyType
from typing import TypeAlias


JsonScalar: TypeAlias = str | int | float | bool | None
JsonValue: TypeAlias = JsonScalar | list["JsonValue"] | dict[str, "JsonValue"]
WirePayload: TypeAlias = dict[str, JsonValue]
CaseKey: TypeAlias = tuple[str, str]
PayloadBuilder: TypeAlias = Callable[["PayloadInputs"], WirePayload]


V1_TABLE_SCHEMA = "docs/open-api/v1/components/schemas/tables.yaml"
V1_TYPE_SCHEMA = "docs/open-api/v1/components/schemas/types.yaml"
V1_EXPRESSION_SCHEMA = "docs/open-api/v1/components/schemas/expressions.yaml"
V1_PARTITION_SCHEMA = "docs/open-api/v1/components/schemas/partitioning.yaml"
V1_INDEX_SCHEMA = "docs/open-api/v1/components/schemas/indexes.yaml"
V1_ERROR_SCHEMA = "docs/open-api/v1/components/schemas/errors.yaml"
RELATIONAL_GUIDE = "docs/manage-relational-metadata-using-gravitino.md"
LAYOUT_GUIDE = "docs/table-partitioning-distribution-sort-order-indexes.md"


class CaseOutcome(str, Enum):
    """The expected result mode of one documented create-table journey."""

    SUCCESS = "success"
    ASSERT_ERROR = "assert_error"
    NON_BLOCKING_ERROR = "non_blocking_error"
    NON_BLOCKING_OBSERVATION = "non_blocking_observation"


class ErrorDetailKind(str, Enum):
    """The public V1 error-detail category a scenario should verify."""

    FIELD_VIOLATION = "FIELD_VIOLATION"
    RESOURCE_INFO = "RESOURCE_INFO"


@dataclass(frozen=True)
class ExpectedPublicError:
    """An HTTP/public-error expectation independent of a transport library."""

    status: int
    error_type: str
    retryable: bool

    def __post_init__(self) -> None:
        """Validates the stable error tuple before it is used in a test plan."""
        if not 400 <= self.status <= 599:
            raise ValueError("status must be an HTTP error status.")
        if not self.error_type:
            raise ValueError("error_type must not be empty.")


@dataclass(frozen=True)
class PublicErrorExpectation:
    """Expected V1 error details, including whether a known gap may warn only."""

    error: ExpectedPublicError | None
    detail_kind: ErrorDetailKind | None = None
    non_blocking: bool = False
    reason: str | None = None

    def __post_init__(self) -> None:
        """Rejects an ambiguous strict expectation without a public error tuple."""
        if self.error is None and not self.non_blocking:
            raise ValueError("A strict error expectation needs a public error tuple.")


@dataclass(frozen=True)
class PayloadInputs:
    """Fixture-owned values substituted into a target V1 create request.

    ``table_location``, ``existing_dataset_location``, and
    ``metadata_location`` are intentionally supplied by a fixture instead of
    invented by the payload layer.  When omitted, the plan contains an obvious
    placeholder and records the missing binding so it cannot be mistaken for a
    ready-to-send request.
    """

    table_name: str
    table_location: str | None = None
    existing_dataset_location: str | None = None
    metadata_location: str | None = None
    existing_table: bool = False

    def __post_init__(self) -> None:
        """Makes normal scenario setup fail early for an unusable table name."""
        if not self.table_name or not self.table_name.strip():
            raise ValueError("table_name must be non-empty; invalid-name cases mutate the body.")


@dataclass(frozen=True)
class CreateTablePayloadPlan:
    """One target V1 request body plus its provider-specific verification contract."""

    provider: str
    use_case: str
    payload: WirePayload
    outcome: CaseOutcome
    expected_error: PublicErrorExpectation | None
    preconditions: tuple[str, ...]
    required_bindings: tuple[str, ...]
    unmet_requirements: tuple[str, ...]
    sources: tuple[str, ...]
    profile_use_case: str | None = None

    @property
    def is_ready_to_send(self) -> bool:
        """Returns whether fixture bindings and lifecycle preconditions are satisfied."""
        return not self.unmet_requirements

    def body(self) -> WirePayload:
        """Returns a deep copy suitable for a future generated V1 request model."""
        return deepcopy(self.payload)


@dataclass(frozen=True)
class _CaseDefinition:
    """Internal immutable registration for a provider/use-case payload builder."""

    builder: PayloadBuilder
    outcome: CaseOutcome
    expected_error: PublicErrorExpectation | None
    preconditions: tuple[str, ...]
    required_bindings: tuple[str, ...]
    requires_existing_table: bool
    sources: tuple[str, ...]
    profile_use_case: str | None


INVALID_ARGUMENT = ExpectedPublicError(400, "INVALID_ARGUMENT", False)
UNAUTHENTICATED = ExpectedPublicError(401, "UNAUTHENTICATED", False)
PERMISSION_DENIED = ExpectedPublicError(403, "PERMISSION_DENIED", False)
METALAKE_NOT_FOUND = ExpectedPublicError(404, "METALAKE_NOT_FOUND", False)
CATALOG_NOT_FOUND = ExpectedPublicError(404, "CATALOG_NOT_FOUND", False)
SCHEMA_NOT_FOUND = ExpectedPublicError(404, "SCHEMA_NOT_FOUND", False)
TABLE_ALREADY_EXISTS = ExpectedPublicError(409, "TABLE_ALREADY_EXISTS", False)
UNSUPPORTED_OPERATION = ExpectedPublicError(501, "UNSUPPORTED_OPERATION", False)

FIELD_INVALID = PublicErrorExpectation(INVALID_ARGUMENT, ErrorDetailKind.FIELD_VIOLATION)
RESOURCE_METALAKE = PublicErrorExpectation(
    METALAKE_NOT_FOUND, ErrorDetailKind.RESOURCE_INFO
)
RESOURCE_CATALOG = PublicErrorExpectation(
    CATALOG_NOT_FOUND, ErrorDetailKind.RESOURCE_INFO
)
RESOURCE_SCHEMA = PublicErrorExpectation(SCHEMA_NOT_FOUND, ErrorDetailKind.RESOURCE_INFO)
RESOURCE_TABLE_ALREADY_EXISTS = PublicErrorExpectation(
    TABLE_ALREADY_EXISTS, ErrorDetailKind.RESOURCE_INFO
)
AUTHENTICATION_REQUIRED = PublicErrorExpectation(UNAUTHENTICATED)
AUTHORIZATION_REQUIRED = PublicErrorExpectation(PERMISSION_DENIED)
UNSUPPORTED = PublicErrorExpectation(
    UNSUPPORTED_OPERATION,
    non_blocking=True,
    reason="Documented connector capability limitation; record it without blocking the CUJ.",
)


def _simple(kind: str) -> dict[str, JsonValue]:
    """Builds a V1 simple data type."""
    return {"kind": kind}


def _integer(kind: str = "LONG") -> dict[str, JsonValue]:
    """Builds a signed V1 integral data type."""
    return {"kind": kind, "signed": True}


def _decimal(precision: int = 18, scale: int = 2) -> dict[str, JsonValue]:
    """Builds a V1 fixed-precision decimal data type."""
    return {"kind": "DECIMAL", "precision": precision, "scale": scale}


def _varchar(length: int = 255) -> dict[str, JsonValue]:
    """Builds a V1 length-qualified character data type."""
    return {"kind": "VARCHAR", "length": length}


def _timestamp() -> dict[str, JsonValue]:
    """Builds a timezone-free V1 timestamp without provider-sensitive precision."""
    return {"kind": "TIMESTAMP", "withTimeZone": False}


def _list(element_type: dict[str, JsonValue], *, nullable: bool = False) -> dict[str, JsonValue]:
    """Builds a V1 list type with explicit element nullability."""
    return {
        "kind": "LIST",
        "elementType": element_type,
        "elementNullable": nullable,
    }


def _map(
    key_type: dict[str, JsonValue],
    value_type: dict[str, JsonValue],
    *,
    value_nullable: bool = True,
) -> dict[str, JsonValue]:
    """Builds a V1 map type with explicit value nullability."""
    return {
        "kind": "MAP",
        "keyType": key_type,
        "valueType": value_type,
        "valueNullable": value_nullable,
    }


def _struct(fields: Sequence[dict[str, JsonValue]]) -> dict[str, JsonValue]:
    """Builds a V1 struct type from ordered field definitions."""
    return {"kind": "STRUCT", "fields": list(fields)}


def _struct_field(
    name: str, data_type: dict[str, JsonValue], *, nullable: bool
) -> dict[str, JsonValue]:
    """Builds a V1 struct field without optional comments."""
    return {"name": name, "type": data_type, "nullable": nullable}


def _column(
    name: str,
    data_type: dict[str, JsonValue],
    *,
    nullable: bool,
    auto_increment: bool = False,
    comment: str | None = None,
    default_value: dict[str, JsonValue] | None = None,
) -> dict[str, JsonValue]:
    """Builds a complete V1 table-column wire object."""
    column: dict[str, JsonValue] = {
        "name": name,
        "type": data_type,
        "nullable": nullable,
        "autoIncrement": auto_increment,
    }
    if comment is not None:
        column["comment"] = comment
    if default_value is not None:
        column["defaultValue"] = default_value
    return column


def _literal(value: JsonScalar, data_type: dict[str, JsonValue]) -> dict[str, JsonValue]:
    """Builds an explicit V1 literal expression for a column default."""
    return {"type": "literal", "value": value, "data-type": data_type}


def _reference(name: str) -> dict[str, JsonValue]:
    """Builds an unbound V1 named-reference expression."""
    return {"type": "reference", "name": name}


def _sort(name: str, direction: str = "ASC") -> dict[str, JsonValue]:
    """Builds one V1 sort term with explicit null ordering."""
    null_ordering = "NULLS_LAST" if direction == "ASC" else "NULLS_FIRST"
    return {
        "expression": _reference(name),
        "direction": direction,
        "nullOrdering": null_ordering,
    }


def _identity(name: str) -> dict[str, JsonValue]:
    """Builds a V1 identity partition transform."""
    return {"kind": "IDENTITY", "fieldName": [name]}


def _year(name: str) -> dict[str, JsonValue]:
    """Builds a V1 year partition transform."""
    return {"kind": "YEAR", "fieldName": [name]}


def _bucket(name: str, count: int) -> dict[str, JsonValue]:
    """Builds a V1 bucket partition transform."""
    return {"kind": "BUCKET", "numBuckets": count, "fieldNames": [[name]]}


def _range(name: str) -> dict[str, JsonValue]:
    """Builds a V1 unassigned range partition transform."""
    return {"kind": "RANGE", "fieldName": [name], "assignments": []}


def _list_partition(name: str) -> dict[str, JsonValue]:
    """Builds a V1 unassigned single-field list partition transform."""
    return {"kind": "LIST", "fieldNames": [[name]], "assignments": []}


def _hash_distribution(name: str, buckets: int | None = 8) -> dict[str, JsonValue]:
    """Builds a V1 hash distribution, omitting count when the catalog chooses it."""
    distribution: dict[str, JsonValue] = {
        "strategy": "HASH",
        "expressions": [_reference(name)],
    }
    if buckets is not None:
        distribution["bucketCount"] = buckets
    return distribution


def _none_distribution() -> dict[str, JsonValue]:
    """Builds the explicit V1 no-distribution form required by ClickHouse."""
    return {"strategy": "NONE", "expressions": []}


def _index(
    index_type: str, name: str, fields: Sequence[str], *, properties: Mapping[str, str] | None = None
) -> dict[str, JsonValue]:
    """Builds a V1 public table-index definition."""
    return {
        "type": index_type,
        "name": name,
        "fieldNames": [[field] for field in fields],
        "properties": dict(properties or {}),
    }


def _request(
    name: str,
    columns: Sequence[dict[str, JsonValue]],
    *,
    comment: str | None = None,
    properties: Mapping[str, str] | None = None,
    partitioning: Sequence[dict[str, JsonValue]] | None = None,
    distribution: dict[str, JsonValue] | None = None,
    sort_orders: Sequence[dict[str, JsonValue]] | None = None,
    indexes: Sequence[dict[str, JsonValue]] | None = None,
) -> WirePayload:
    """Builds the proposed strict V1 create request shared by all scenarios."""
    payload: WirePayload = {
        "name": name,
        "columns": list(columns),
        "properties": dict(properties or {}),
        "partitioning": list(partitioning or []),
        "sortOrders": list(sort_orders or []),
        "indexes": list(indexes or []),
    }
    if comment is not None:
        payload["comment"] = comment
    if distribution is not None:
        payload["distribution"] = distribution
    return payload


def _standard_columns() -> list[dict[str, JsonValue]]:
    """Returns a small cross-provider schema with a durable identifier column."""
    return [
        _column("id", _integer("LONG"), nullable=False, comment="customer journey id"),
        _column("description", _simple("STRING"), nullable=True),
    ]


def _glue_columns() -> list[dict[str, JsonValue]]:
    """Returns Glue-safe columns with no unsupported NOT NULL constraint."""
    return [
        _column("id", _integer("LONG"), nullable=True),
        _column("ds", _simple("DATE"), nullable=True),
    ]


def _location(inputs: PayloadInputs, name: str) -> str:
    """Returns a fixture binding or an unmistakable non-runnable placeholder."""
    value = getattr(inputs, name)
    return value if value is not None else f"<fixture:{name}>"


def _lance_properties(
    inputs: PayloadInputs,
    *,
    location_name: str = "table_location",
    external: bool = False,
    register: bool = False,
    creation_mode: str | None = None,
    declared: bool = False,
) -> dict[str, str]:
    """Builds documented Lance properties without embedding fixture storage paths."""
    properties = {
        "format": "lance",
        "location": _location(inputs, location_name),
        "external": str(external).lower(),
    }
    if register:
        properties["lance.register"] = "true"
    if creation_mode is not None:
        properties["lance.creation-mode"] = creation_mode
    if declared:
        properties["lance.declared"] = "true"
    return properties


def _minimal(provider: str, inputs: PayloadInputs) -> WirePayload:
    """Builds the smallest documented valid request for each feature provider."""
    if provider == "lance":
        return _request(
            inputs.table_name,
            _standard_columns(),
            properties=_lance_properties(inputs),
        )
    if provider == "glue":
        return _request(inputs.table_name, _glue_columns())
    if provider in {"doris", "starrocks"}:
        return _request(
            inputs.table_name,
            _standard_columns(),
            distribution=_hash_distribution("id"),
        )
    if provider == "clickhouse":
        return _request(
            inputs.table_name,
            _standard_columns(),
            properties={"engine": "MergeTree"},
            distribution=_none_distribution(),
            sort_orders=[_sort("id")],
        )
    return _request(inputs.table_name, _standard_columns())


def _comment_and_properties(provider: str, inputs: PayloadInputs) -> WirePayload:
    """Adds connector-documented public metadata to the provider-minimal request."""
    payload = _minimal(provider, inputs)
    payload["comment"] = "OpenAPI V1 documented customer-journey table"
    properties = payload["properties"]
    assert isinstance(properties, dict)
    provider_properties: dict[str, str] = {
        "iceberg": "2",
        "hive": "ORC",
        "glue": "parquet",
        "paimon": "deduplicate",
        "mysql": "InnoDB",
        "doris": "1",
        "starrocks": "1",
        "hologres": "column",
    }
    if provider == "iceberg":
        properties["format-version"] = provider_properties[provider]
    elif provider == "hive":
        properties["format"] = provider_properties[provider]
    elif provider == "glue":
        properties["format"] = provider_properties[provider]
    elif provider == "paimon":
        properties["merge-engine"] = provider_properties[provider]
    elif provider == "mysql":
        properties["engine"] = provider_properties[provider]
    elif provider in {"doris", "starrocks"}:
        properties["replication_num"] = provider_properties[provider]
    elif provider == "clickhouse":
        properties["settings.index_granularity"] = "8192"
    elif provider == "hologres":
        properties["orientation"] = provider_properties[provider]
    elif provider == "lance":
        properties["cuj.owner"] = "openapi-v1"
    return payload


def _complex_schema(provider: str, inputs: PayloadInputs) -> WirePayload:
    """Builds provider-safe complex schemas without connector-known invalid forms."""
    if provider == "lance":
        columns = [
            _column("id", _integer("LONG"), nullable=False),
            _column(
                "customer",
                _struct(
                    [
                        _struct_field("name", _simple("STRING"), nullable=True),
                        _struct_field(
                            "tags", _list(_simple("STRING"), nullable=False), nullable=True
                        ),
                    ]
                ),
                nullable=True,
            ),
        ]
        return _request(
            inputs.table_name, columns, properties=_lance_properties(inputs)
        )

    nullable = provider == "glue"
    columns = [
        _column("id", _integer("LONG"), nullable=nullable),
        _column(
            "customer",
            _struct(
                [
                    _struct_field("name", _simple("STRING"), nullable=True),
                    _struct_field(
                        "tags", _list(_simple("STRING"), nullable=False), nullable=True
                    ),
                ]
            ),
            nullable=True,
        ),
        _column(
            "attributes",
            _map(_simple("STRING"), _simple("STRING")),
            nullable=True,
        ),
    ]
    if provider == "glue":
        return _request(inputs.table_name, columns)
    return _request(inputs.table_name, columns)


def _parameterized_schema(provider: str, inputs: PayloadInputs) -> WirePayload:
    """Builds a parameterized schema compatible with each JDBC provider CUJ."""
    columns = [
        _column("id", _integer("INTEGER"), nullable=False),
        _column("code", _varchar(64), nullable=False),
        _column("amount", _decimal(18, 2), nullable=False),
    ]
    if provider == "postgresql":
        columns.append(_column("labels", _list(_simple("STRING")), nullable=True))
    if provider == "hologres":
        columns.append(
            _column(
                "labels", _list(_simple("STRING"), nullable=False), nullable=True
            )
        )
    if provider == "clickhouse":
        return _request(
            inputs.table_name,
            columns,
            properties={"engine": "MergeTree"},
            distribution=_none_distribution(),
            sort_orders=[_sort("id")],
        )
    return _request(inputs.table_name, columns)


def _default_values(provider: str, inputs: PayloadInputs) -> WirePayload:
    """Builds a provider-supported default-value request using V1 expressions."""
    nullable = provider == "glue"
    columns = [
        _column("id", _integer("LONG"), nullable=nullable),
        _column(
            "state",
            _simple("STRING"),
            nullable=nullable,
            default_value=_literal("NEW", _simple("STRING")),
        ),
        _column(
            "amount",
            _decimal(),
            nullable=nullable,
            default_value=_literal("0.00", _decimal()),
        ),
    ]
    if provider in {"doris", "starrocks"}:
        return _request(
            inputs.table_name,
            columns,
            distribution=_hash_distribution("id"),
        )
    if provider == "clickhouse":
        return _request(
            inputs.table_name,
            columns,
            properties={"engine": "MergeTree"},
            distribution=_none_distribution(),
            sort_orders=[_sort("id")],
        )
    return _request(inputs.table_name, columns)


def _defaults_and_auto_increment(provider: str, inputs: PayloadInputs) -> WirePayload:
    """Builds documented default and auto-increment combinations."""
    columns = [
        _column("id", _integer("LONG"), nullable=False, auto_increment=True),
        _column(
            "state",
            _simple("STRING"),
            nullable=False,
            default_value=_literal("NEW", _simple("STRING")),
        ),
    ]
    if provider in {"mysql", "oceanbase"}:
        return _request(
            inputs.table_name,
            columns,
            indexes=[_index("PRIMARY_KEY", "PRIMARY", ["id"])],
        )
    return _request(inputs.table_name, columns)


def _property_default_values(inputs: PayloadInputs) -> WirePayload:
    """Builds Paimon's documented property-based default, not a column default."""
    return _request(
        inputs.table_name,
        [
            _column("id", _integer("LONG"), nullable=False),
            _column("state", _simple("STRING"), nullable=False),
        ],
        properties={"fields.state.default-value": "NEW"},
    )


def _hive_layout(inputs: PayloadInputs) -> WirePayload:
    """Builds Hive's identity-partitioned bucketed and sorted table form."""
    return _request(
        inputs.table_name,
        [
            _column("id", _integer("LONG"), nullable=False),
            _column("ds", _simple("DATE"), nullable=False),
        ],
        partitioning=[_identity("ds")],
        distribution=_hash_distribution("id", 8),
        sort_orders=[_sort("id")],
    )


def _glue_hive_layout(inputs: PayloadInputs) -> WirePayload:
    """Builds a Glue Hive-format layout without unsupported NOT NULL columns."""
    return _request(
        inputs.table_name,
        _glue_columns(),
        properties={"table-format": "HIVE"},
        partitioning=[_identity("ds")],
        distribution=_hash_distribution("id", 8),
        sort_orders=[_sort("id")],
    )


def _glue_iceberg_partitions(inputs: PayloadInputs) -> WirePayload:
    """Builds Glue's Iceberg-transform variant; warehouse is a catalog precondition."""
    return _request(
        inputs.table_name,
        [
            _column("id", _integer("LONG"), nullable=True),
            _column("created_at", _timestamp(), nullable=True),
        ],
        properties={"table-format": "ICEBERG"},
        partitioning=[_year("created_at"), _bucket("id", 16)],
    )


def _iceberg_layout(inputs: PayloadInputs) -> WirePayload:
    """Builds an Iceberg transform, sort, and hash-distribution request."""
    return _request(
        inputs.table_name,
        [
            _column("id", _integer("LONG"), nullable=False),
            _column("created_at", _timestamp(), nullable=False),
        ],
        partitioning=[_year("created_at"), _bucket("id", 16)],
        distribution=_hash_distribution("id", 16),
        sort_orders=[_sort("created_at", "DESC")],
    )


def _paimon_partition_and_primary_key(inputs: PayloadInputs) -> WirePayload:
    """Keeps Paimon's primary key distinct from its identity partition field."""
    return _request(
        inputs.table_name,
        [
            _column("order_id", _integer("LONG"), nullable=False),
            _column("ds", _simple("DATE"), nullable=False),
        ],
        partitioning=[_identity("ds")],
        indexes=[_index("PRIMARY_KEY", "orders_pk", ["order_id"])],
    )


def _doris_or_starrocks_layout(provider: str, inputs: PayloadInputs) -> WirePayload:
    """Builds the documented range partition and required hash distribution form."""
    return _request(
        inputs.table_name,
        [
            _column("id", _integer("LONG"), nullable=False),
            _column("ds", _simple("DATE"), nullable=False),
        ],
        partitioning=[_range("ds")],
        distribution=_hash_distribution("id", 8),
    )


def _clickhouse_layout(inputs: PayloadInputs, *, include_partition: bool) -> WirePayload:
    """Builds a durable MergeTree-family request with V1 NONE distribution."""
    partitioning = [_identity("ds")] if include_partition else []
    return _request(
        inputs.table_name,
        [
            _column("id", _integer("LONG"), nullable=False),
            _column("amount", _decimal(), nullable=False),
            _column("ds", _simple("DATE"), nullable=False),
        ],
        properties={"engine": "MergeTree"},
        partitioning=partitioning,
        distribution=_none_distribution(),
        sort_orders=[_sort("id")],
        indexes=[_index("PRIMARY_KEY", "orders_pk", ["id"])],
    )


def _clickhouse_data_skipping_index(inputs: PayloadInputs) -> WirePayload:
    """Builds a MergeTree table with an explicitly supported data-skipping index."""
    payload = _clickhouse_layout(inputs, include_partition=False)
    indexes = payload["indexes"]
    assert isinstance(indexes, list)
    indexes.append(
        _index("DATA_SKIPPING_MINMAX", "amount_minmax", ["amount"])
    )
    return payload


def _hologres_layout(inputs: PayloadInputs) -> WirePayload:
    """Builds Hologres HASH plus one-column physical LIST partitioning."""
    return _request(
        inputs.table_name,
        [
            _column("id", _integer("LONG"), nullable=False),
            _column("ds", _simple("DATE"), nullable=False),
        ],
        partitioning=[_list_partition("ds")],
        distribution=_hash_distribution("id", None),
    )


def _primary_and_unique_indexes(provider: str, inputs: PayloadInputs) -> WirePayload:
    """Builds provider-supported primary and unique index combinations."""
    return _request(
        inputs.table_name,
        [
            _column("id", _integer("LONG"), nullable=False),
            _column("external_id", _varchar(64), nullable=False),
        ],
        indexes=[
            _index("PRIMARY_KEY", "PRIMARY", ["id"]),
            _index("UNIQUE_KEY", "external_id_uq", ["external_id"]),
        ],
    )


def _primary_key_index(provider: str, inputs: PayloadInputs) -> WirePayload:
    """Builds each connector's documented single primary-key index form."""
    payload = _request(
        inputs.table_name,
        [
            _column("id", _integer("LONG"), nullable=False),
            _column("description", _simple("STRING"), nullable=True),
        ],
        indexes=[_index("PRIMARY_KEY", "table_pk", ["id"])],
    )
    if provider in {"doris", "starrocks"}:
        payload["distribution"] = _hash_distribution("id", 8)
    return payload


def _lance_managed(inputs: PayloadInputs) -> WirePayload:
    """Builds a fresh managed Lance table request."""
    return _request(
        inputs.table_name,
        _standard_columns(),
        properties=_lance_properties(inputs),
    )


def _lance_external(inputs: PayloadInputs) -> WirePayload:
    """Builds a fresh external Lance table request."""
    return _request(
        inputs.table_name,
        _standard_columns(),
        properties=_lance_properties(inputs, external=True),
    )


def _lance_declared(inputs: PayloadInputs) -> WirePayload:
    """Builds the proposed declared-Lance form behind the conditional CUJ."""
    return _request(
        inputs.table_name,
        _standard_columns(),
        properties=_lance_properties(inputs, declared=True),
    )


def _lance_registered(inputs: PayloadInputs) -> WirePayload:
    """Builds a zero-column Lance registration request for an existing dataset."""
    return _request(
        inputs.table_name,
        [],
        properties=_lance_properties(
            inputs, location_name="existing_dataset_location", external=True, register=True
        ),
    )


def _lance_creation_mode(inputs: PayloadInputs, mode: str) -> WirePayload:
    """Builds an existing-table Lance create-mode request."""
    return _request(
        inputs.table_name,
        _standard_columns(),
        properties=_lance_properties(inputs, creation_mode=mode),
    )


def _delta_registration(inputs: PayloadInputs) -> WirePayload:
    """Builds a Delta registration request with caller-supplied schema and location."""
    return _request(
        inputs.table_name,
        _standard_columns(),
        properties={
            "format": "delta",
            "external": "true",
            "location": _location(inputs, "existing_dataset_location"),
        },
    )


def _glue_iceberg_registration(inputs: PayloadInputs) -> WirePayload:
    """Builds registration against a pre-existing Glue/Iceberg metadata JSON file."""
    return _request(
        inputs.table_name,
        _glue_columns(),
        properties={
            "table-format": "ICEBERG",
            "metadata_location": _location(inputs, "metadata_location"),
        },
    )


def _invalid_blank_name(inputs: PayloadInputs) -> WirePayload:
    """Builds a structurally complete request with an invalid blank table name."""
    payload = _request("", _standard_columns())
    return payload


def _invalid_missing_columns(inputs: PayloadInputs) -> WirePayload:
    """Builds the create request that intentionally lacks a table schema."""
    return _request(inputs.table_name, [])


def _invalid_duplicate_columns(inputs: PayloadInputs) -> WirePayload:
    """Builds duplicate V1 column identifiers for strict request validation."""
    return _request(
        inputs.table_name,
        [
            _column("duplicate", _integer("LONG"), nullable=False),
            _column("duplicate", _simple("STRING"), nullable=True),
        ],
    )


def _invalid_unknown_layout_field(inputs: PayloadInputs) -> WirePayload:
    """Builds a semantic invalid-field case without relying on open JSON extras."""
    return _request(
        inputs.table_name,
        _standard_columns(),
        partitioning=[_identity("does_not_exist")],
    )


def _auto_increment_without_key(inputs: PayloadInputs) -> WirePayload:
    """Builds the documented MySQL/OceanBase invalid auto-increment combination."""
    return _request(
        inputs.table_name,
        [_column("id", _integer("LONG"), nullable=False, auto_increment=True)],
    )


def _unsupported_auto_increment(provider: str, inputs: PayloadInputs) -> WirePayload:
    """Builds a concrete auto-increment probe for a provider that rejects it."""
    if provider == "lance":
        return _request(
            inputs.table_name,
            [_column("id", _integer("LONG"), nullable=False, auto_increment=True)],
            properties=_lance_properties(inputs),
        )
    if provider == "starrocks":
        return _request(
            inputs.table_name,
            [_column("id", _integer("LONG"), nullable=False, auto_increment=True)],
            distribution=_hash_distribution("id", 8),
        )
    if provider == "clickhouse":
        return _request(
            inputs.table_name,
            [_column("id", _integer("LONG"), nullable=False, auto_increment=True)],
            properties={"engine": "MergeTree"},
            distribution=_none_distribution(),
            sort_orders=[_sort("id")],
        )
    return _request(
        inputs.table_name,
        [_column("id", _integer("LONG"), nullable=False, auto_increment=True)],
    )


def _unsupported_layout(provider: str, inputs: PayloadInputs) -> WirePayload:
    """Builds a V1-valid shape that the selected provider documents as unsupported."""
    if provider == "lance":
        return _request(
            inputs.table_name,
            _standard_columns(),
            properties=_lance_properties(inputs),
            partitioning=[_identity("id")],
        )
    if provider == "delta":
        payload = _delta_registration(inputs)
        payload["sortOrders"] = [_sort("id")]
        return payload
    return _request(
        inputs.table_name,
        _standard_columns(),
        distribution=_hash_distribution("id", 8),
        sort_orders=[_sort("id")],
    )


_CASE_DEFINITIONS: dict[CaseKey, _CaseDefinition] = {}


def _add_case(
    provider: str,
    use_case: str,
    builder: PayloadBuilder,
    *,
    outcome: CaseOutcome = CaseOutcome.SUCCESS,
    expected_error: PublicErrorExpectation | None = None,
    preconditions: Sequence[str] = (),
    required_bindings: Sequence[str] = (),
    requires_existing_table: bool = False,
    sources: Sequence[str] = (),
    profile_use_case: str | None = None,
) -> None:
    """Registers one feature-facing provider/use-case payload definition."""
    key = (provider, use_case)
    if key in _CASE_DEFINITIONS:
        raise ValueError(f"Duplicate create-table payload definition for {key!r}.")
    if outcome is CaseOutcome.ASSERT_ERROR and expected_error is None:
        raise ValueError(f"Strict error case {key!r} must define an expected error.")
    _CASE_DEFINITIONS[key] = _CaseDefinition(
        builder=builder,
        outcome=outcome,
        expected_error=expected_error,
        preconditions=tuple(preconditions),
        required_bindings=tuple(required_bindings),
        requires_existing_table=requires_existing_table,
        sources=tuple(sources),
        profile_use_case=profile_use_case,
    )


def _source(provider_document: str, *additional: str) -> tuple[str, ...]:
    """Returns the V1 wire references plus the connector document for one case."""
    return (
        V1_TABLE_SCHEMA,
        V1_TYPE_SCHEMA,
        V1_EXPRESSION_SCHEMA,
        V1_PARTITION_SCHEMA,
        V1_INDEX_SCHEMA,
        provider_document,
        *additional,
    )


HIVE = "docs/apache-hive-catalog.md"
ICEBERG = "docs/lakehouse-iceberg-catalog.md"
PAIMON = "docs/lakehouse-paimon-catalog.md"
HUDI = "docs/lakehouse-hudi-catalog.md"
LANCE = "docs/lakehouse-generic-lance-table.md"
DELTA = "docs/lakehouse-generic-delta-table.md"
GLUE = "docs/aws-glue-catalog.md"
MYSQL = "docs/jdbc-mysql-catalog.md"
POSTGRESQL = "docs/jdbc-postgresql-catalog.md"
DORIS = "docs/jdbc-doris-catalog.md"
STARROCKS = "docs/jdbc-starrocks-catalog.md"
OCEANBASE = "docs/jdbc-oceanbase-catalog.md"
CLICKHOUSE = "docs/jdbc-clickhouse-catalog.md"
HOLOGRES = "docs/jdbc-hologres-catalog.md"


def _register_feature_cases() -> None:
    """Registers every positive and negative provider/use-case named by Gherkin."""
    minimal_documents = {
        "lance": LANCE,
        "iceberg": ICEBERG,
        "hive": HIVE,
        "glue": GLUE,
        "paimon": PAIMON,
        "mysql": MYSQL,
        "postgresql": POSTGRESQL,
        "doris": DORIS,
        "starrocks": STARROCKS,
        "oceanbase": OCEANBASE,
        "clickhouse": CLICKHOUSE,
        "hologres": HOLOGRES,
    }
    for provider, document in minimal_documents.items():
        requirements: tuple[str, ...] = ()
        bindings: tuple[str, ...] = ()
        if provider == "lance":
            requirements = ("A fixture-owned writable Lance location is supplied.",)
            bindings = ("table_location",)
        _add_case(
            provider,
            "minimal",
            partial(_minimal, provider),
            preconditions=requirements,
            required_bindings=bindings,
            sources=_source(document, RELATIONAL_GUIDE),
        )

    _add_case(
        "hudi",
        "minimal",
        partial(_minimal, "hudi"),
        outcome=CaseOutcome.NON_BLOCKING_ERROR,
        expected_error=UNSUPPORTED,
        preconditions=("The Hudi catalog is configured but remains read-only.",),
        sources=_source(HUDI, V1_ERROR_SCHEMA),
    )

    comment_documents = {
        "lance": LANCE,
        "iceberg": ICEBERG,
        "hive": HIVE,
        "glue": GLUE,
        "paimon": PAIMON,
        "mysql": MYSQL,
        "doris": DORIS,
        "starrocks": STARROCKS,
        "clickhouse": CLICKHOUSE,
        "hologres": HOLOGRES,
    }
    for provider, document in comment_documents.items():
        bindings = ("table_location",) if provider == "lance" else ()
        _add_case(
            provider,
            "comment_and_properties",
            partial(_comment_and_properties, provider),
            required_bindings=bindings,
            sources=_source(document),
        )

    complex_documents = {
        "lance": LANCE,
        "iceberg": ICEBERG,
        "hive": HIVE,
        "glue": GLUE,
        "paimon": PAIMON,
    }
    for provider, document in complex_documents.items():
        _add_case(
            provider,
            "complex_schema",
            partial(_complex_schema, provider),
            required_bindings=("table_location",) if provider == "lance" else (),
            sources=_source(document),
        )

    parameterized_documents = {
        "mysql": MYSQL,
        "postgresql": POSTGRESQL,
        "clickhouse": CLICKHOUSE,
        "hologres": HOLOGRES,
    }
    for provider, document in parameterized_documents.items():
        _add_case(
            provider,
            "parameterized_schema",
            partial(_parameterized_schema, provider),
            sources=_source(document),
        )

    for provider, document in {
        "mysql": MYSQL,
        "postgresql": POSTGRESQL,
        "oceanbase": OCEANBASE,
    }.items():
        _add_case(
            provider,
            "defaults_and_auto_increment",
            partial(_defaults_and_auto_increment, provider),
            sources=_source(document),
        )

    for provider, document in {
        "mysql": MYSQL,
        "postgresql": POSTGRESQL,
        "doris": DORIS,
        "starrocks": STARROCKS,
        "oceanbase": OCEANBASE,
        "clickhouse": CLICKHOUSE,
        "hologres": HOLOGRES,
    }.items():
        _add_case(
            provider,
            "default_values",
            partial(_default_values, provider),
            sources=_source(document),
        )

    _add_case(
        "paimon",
        "property_default_values",
        _property_default_values,
        sources=_source(PAIMON),
        profile_use_case="default_values_via_properties",
    )

    _add_case(
        "hive",
        "identity_partition_hash_distribution_sort",
        _hive_layout,
        sources=_source(HIVE, LAYOUT_GUIDE),
    )
    _add_case(
        "glue",
        "hive_identity_partition_hash_distribution_sort",
        _glue_hive_layout,
        sources=_source(GLUE, LAYOUT_GUIDE),
    )
    _add_case(
        "glue",
        "iceberg_partition_transforms",
        _glue_iceberg_partitions,
        preconditions=("The Glue catalog warehouse is configured before table creation.",),
        sources=_source(GLUE),
    )
    _add_case(
        "iceberg",
        "partition_sort_and_distribution",
        _iceberg_layout,
        sources=_source(ICEBERG, LAYOUT_GUIDE),
    )
    _add_case(
        "paimon",
        "identity_partition_and_primary_key",
        _paimon_partition_and_primary_key,
        sources=_source(PAIMON),
    )
    for provider, document in {"doris": DORIS, "starrocks": STARROCKS}.items():
        _add_case(
            provider,
            "partition_and_required_distribution",
            partial(_doris_or_starrocks_layout, provider),
            sources=_source(document, LAYOUT_GUIDE),
        )
    _add_case(
        "clickhouse",
        "engine_partition_sort_and_primary_key",
        partial(_clickhouse_layout, include_partition=True),
        sources=_source(CLICKHOUSE),
    )
    _add_case(
        "hologres",
        "distribution_and_partitioning",
        _hologres_layout,
        sources=_source(HOLOGRES),
    )

    for provider, document in {
        "mysql": MYSQL,
        "postgresql": POSTGRESQL,
        "oceanbase": OCEANBASE,
    }.items():
        _add_case(
            provider,
            "primary_and_unique_indexes",
            partial(_primary_and_unique_indexes, provider),
            sources=_source(document),
            profile_use_case="indexes",
        )
    for provider, document in {
        "paimon": PAIMON,
        "doris": DORIS,
        "hologres": HOLOGRES,
    }.items():
        _add_case(
            provider,
            "primary_key_index",
            partial(_primary_key_index, provider),
            sources=_source(document),
            profile_use_case="indexes",
        )
    _add_case(
        "clickhouse",
        "mergetree_order_and_primary_key",
        partial(_clickhouse_layout, include_partition=False),
        sources=_source(CLICKHOUSE),
        profile_use_case="engine_partition_sort_and_primary_key",
    )
    _add_case(
        "clickhouse",
        "data_skipping_index",
        _clickhouse_data_skipping_index,
        sources=_source(CLICKHOUSE),
        profile_use_case="indexes",
    )

    _add_case(
        "lance",
        "managed_table",
        _lance_managed,
        preconditions=("The fixture owns the target data directory.",),
        required_bindings=("table_location",),
        sources=_source(LANCE),
    )
    _add_case(
        "lance",
        "external_table",
        _lance_external,
        preconditions=("The fixture owns the external data directory before cleanup.",),
        required_bindings=("table_location",),
        sources=_source(LANCE),
    )
    _add_case(
        "lance",
        "declared_table",
        _lance_declared,
        outcome=CaseOutcome.NON_BLOCKING_OBSERVATION,
        preconditions=(
            "The V1 mapper must explicitly accept lance.declared before this conditional scenario is enabled.",
        ),
        required_bindings=("table_location",),
        sources=_source(LANCE),
    )
    for use_case in ("registered_table", "registered_table_schema_discovery"):
        _add_case(
            "lance",
            use_case,
            _lance_registered,
            outcome=(
                CaseOutcome.NON_BLOCKING_OBSERVATION
                if use_case == "registered_table_schema_discovery"
                else CaseOutcome.SUCCESS
            ),
            preconditions=(
                "A valid pre-existing Lance dataset exists at existing_dataset_location.",
                "Registration owns metadata only and must not create the data directory.",
            ),
            required_bindings=("existing_dataset_location",),
            sources=_source(LANCE),
        )
    for use_case, mode in {
        "creation_mode_exist_ok": "EXIST_OK",
        "creation_mode_overwrite": "OVERWRITE",
    }.items():
        _add_case(
            "lance",
            use_case,
            partial(_lance_creation_mode, mode=mode),
            preconditions=(
                "The target table already exists and is owned by the fixture.",
                *(
                    ("The fixture owns the target data directory because OVERWRITE deletes it.",)
                    if mode == "OVERWRITE"
                    else ()
                ),
            ),
            required_bindings=("table_location",),
            requires_existing_table=True,
            sources=_source(LANCE),
        )
    _add_case(
        "delta",
        "register_existing_delta",
        _delta_registration,
        preconditions=(
            "A valid pre-existing Delta table and its _delta_log exist at existing_dataset_location.",
            "The supplied columns are caller-owned metadata and are not inferred from Delta.",
        ),
        required_bindings=("existing_dataset_location",),
        sources=_source(DELTA),
    )
    _add_case(
        "glue",
        "register_existing_iceberg",
        _glue_iceberg_registration,
        preconditions=(
            "metadata_location points to a pre-existing reachable Iceberg metadata JSON file.",
        ),
        required_bindings=("metadata_location",),
        sources=_source(GLUE),
    )

    for provider, document in {
        "lance": LANCE,
        "delta": DELTA,
        "starrocks": STARROCKS,
    }.items():
        _add_case(
            provider,
            "unsupported_layout",
            partial(_unsupported_layout, provider),
            outcome=CaseOutcome.NON_BLOCKING_ERROR,
            expected_error=UNSUPPORTED,
            required_bindings=("table_location",) if provider == "lance" else (
                "existing_dataset_location",
            ) if provider == "delta" else (),
            sources=_source(document, V1_ERROR_SCHEMA),
        )

    _add_case(
        "lance",
        "blank_table_name",
        _invalid_blank_name,
        outcome=CaseOutcome.ASSERT_ERROR,
        expected_error=FIELD_INVALID,
        sources=_source(LANCE, V1_ERROR_SCHEMA),
    )
    _add_case(
        "lance",
        "missing_columns",
        _invalid_missing_columns,
        outcome=CaseOutcome.ASSERT_ERROR,
        expected_error=FIELD_INVALID,
        sources=_source(LANCE, V1_ERROR_SCHEMA),
    )
    _add_case(
        "lance",
        "duplicate_column_names",
        _invalid_duplicate_columns,
        outcome=CaseOutcome.ASSERT_ERROR,
        expected_error=FIELD_INVALID,
        sources=_source(LANCE, V1_ERROR_SCHEMA),
    )
    _add_case(
        "lance",
        "unknown_layout_field",
        _invalid_unknown_layout_field,
        outcome=CaseOutcome.ASSERT_ERROR,
        expected_error=FIELD_INVALID,
        sources=_source(LANCE, V1_ERROR_SCHEMA, LAYOUT_GUIDE),
    )
    for provider, document in {"mysql": MYSQL, "oceanbase": OCEANBASE}.items():
        _add_case(
            provider,
            "auto_increment_without_key",
            _auto_increment_without_key,
            outcome=CaseOutcome.ASSERT_ERROR,
            expected_error=FIELD_INVALID,
            sources=_source(document, V1_ERROR_SCHEMA),
        )

    for use_case, error in {
        "missing_metalake": RESOURCE_METALAKE,
        "missing_catalog": RESOURCE_CATALOG,
        "missing_schema": RESOURCE_SCHEMA,
        "unauthenticated": AUTHENTICATION_REQUIRED,
        "permission_denied": AUTHORIZATION_REQUIRED,
    }.items():
        _add_case(
            "lance",
            use_case,
            partial(_minimal, "lance"),
            outcome=CaseOutcome.ASSERT_ERROR,
            expected_error=error,
            preconditions=(
                "The fixture mutates the V1 route context or request credentials, not this body.",
            ),
            required_bindings=("table_location",),
            sources=_source(LANCE, V1_ERROR_SCHEMA),
        )
    _add_case(
        "lance",
        "duplicate_table",
        partial(_minimal, "lance"),
        outcome=CaseOutcome.ASSERT_ERROR,
        expected_error=RESOURCE_TABLE_ALREADY_EXISTS,
        preconditions=("A fixture-owned table with this exact name already exists.",),
        required_bindings=("table_location",),
        requires_existing_table=True,
        sources=_source(LANCE, V1_ERROR_SCHEMA),
    )

    unsupported_defaults = {
        ("hive", "default_values"): HIVE,
        ("iceberg", "default_values"): ICEBERG,
        ("glue", "default_values"): GLUE,
        ("paimon", "column_field_default_values"): PAIMON,
        ("paimon", "auto_increment"): PAIMON,
        ("lance", "auto_increment"): LANCE,
        ("hologres", "auto_increment"): HOLOGRES,
    }
    for (provider, use_case), document in unsupported_defaults.items():
        if use_case in {"default_values", "column_field_default_values"}:
            builder = partial(_default_values, provider)
        else:
            builder = partial(_unsupported_auto_increment, provider)
        error = (
            PublicErrorExpectation(
                INVALID_ARGUMENT,
                ErrorDetailKind.FIELD_VIOLATION,
                non_blocking=True,
                reason="Hologres rejects auto-increment in CREATE TABLE.",
            )
            if provider == "hologres"
            else UNSUPPORTED
        )
        _add_case(
            provider,
            use_case,
            builder,
            outcome=CaseOutcome.NON_BLOCKING_ERROR,
            expected_error=error,
            required_bindings=("table_location",) if provider == "lance" else (),
            sources=_source(document, V1_ERROR_SCHEMA),
            profile_use_case=(
                "default_values"
                if use_case == "column_field_default_values"
                else "defaults_and_auto_increment"
                if use_case == "auto_increment"
                else None
            ),
        )
    _add_case(
        "starrocks",
        "indexes",
        partial(_primary_key_index, "starrocks"),
        outcome=CaseOutcome.NON_BLOCKING_ERROR,
        expected_error=UNSUPPORTED,
        sources=_source(STARROCKS, V1_ERROR_SCHEMA),
    )


_register_feature_cases()
CASES: Mapping[CaseKey, _CaseDefinition] = MappingProxyType(dict(_CASE_DEFINITIONS))


# This inventory intentionally mirrors every provider/use-case that the current
# create-table feature files exercise.  It turns a newly added Gherkin case into
# a unit-test failure until a target V1 payload is designed.
FEATURE_CASE_KEYS: frozenset[CaseKey] = frozenset(
    {
        *( (provider, "minimal") for provider in (
            "lance", "iceberg", "hive", "glue", "paimon", "mysql", "postgresql",
            "doris", "starrocks", "oceanbase", "clickhouse", "hologres", "hudi",
        )),
        *( (provider, "comment_and_properties") for provider in (
            "lance", "iceberg", "hive", "glue", "paimon", "mysql", "doris",
            "starrocks", "clickhouse", "hologres",
        )),
        *( (provider, "complex_schema") for provider in (
            "lance", "iceberg", "hive", "glue", "paimon",
        )),
        *( (provider, "parameterized_schema") for provider in (
            "mysql", "postgresql", "clickhouse", "hologres",
        )),
        *( (provider, "defaults_and_auto_increment") for provider in (
            "mysql", "postgresql", "oceanbase",
        )),
        *( (provider, "default_values") for provider in (
            "mysql", "postgresql", "doris", "starrocks", "oceanbase", "clickhouse",
            "hologres", "hive", "iceberg", "glue",
        )),
        ("paimon", "property_default_values"),
        ("hive", "identity_partition_hash_distribution_sort"),
        ("glue", "hive_identity_partition_hash_distribution_sort"),
        ("glue", "iceberg_partition_transforms"),
        ("iceberg", "partition_sort_and_distribution"),
        ("paimon", "identity_partition_and_primary_key"),
        ("doris", "partition_and_required_distribution"),
        ("starrocks", "partition_and_required_distribution"),
        ("clickhouse", "engine_partition_sort_and_primary_key"),
        ("hologres", "distribution_and_partitioning"),
        *( (provider, "primary_and_unique_indexes") for provider in (
            "mysql", "postgresql", "oceanbase",
        )),
        *( (provider, "primary_key_index") for provider in ("paimon", "doris", "hologres")),
        ("clickhouse", "mergetree_order_and_primary_key"),
        ("clickhouse", "data_skipping_index"),
        *( (provider, "unsupported_layout") for provider in ("lance", "delta", "starrocks")),
        ("lance", "managed_table"),
        ("lance", "external_table"),
        ("lance", "declared_table"),
        ("lance", "registered_table"),
        ("lance", "registered_table_schema_discovery"),
        ("lance", "creation_mode_exist_ok"),
        ("lance", "creation_mode_overwrite"),
        ("delta", "register_existing_delta"),
        ("glue", "register_existing_iceberg"),
        ("lance", "blank_table_name"),
        ("lance", "missing_columns"),
        ("lance", "duplicate_column_names"),
        ("lance", "unknown_layout_field"),
        ("mysql", "auto_increment_without_key"),
        ("oceanbase", "auto_increment_without_key"),
        ("lance", "missing_metalake"),
        ("lance", "missing_catalog"),
        ("lance", "missing_schema"),
        ("lance", "duplicate_table"),
        ("lance", "unauthenticated"),
        ("lance", "permission_denied"),
        ("paimon", "column_field_default_values"),
        ("paimon", "auto_increment"),
        ("lance", "auto_increment"),
        ("hologres", "auto_increment"),
        ("starrocks", "indexes"),
    }
)

_MISSING_FEATURE_CASES = FEATURE_CASE_KEYS.difference(CASES)
if _MISSING_FEATURE_CASES:
    raise RuntimeError(
        "The V1 create-table payload matrix is missing feature cases: "
        f"{sorted(_MISSING_FEATURE_CASES)!r}"
    )


def available_create_table_cases(provider: str | None = None) -> tuple[CaseKey, ...]:
    """Lists registered feature-facing payload keys, optionally for one provider."""
    return tuple(
        sorted(key for key in CASES if provider is None or key[0] == provider)
    )


def build_create_table_plan(
    provider: str, use_case: str, inputs: PayloadInputs
) -> CreateTablePayloadPlan:
    """Builds one intended V1 create request and its verification metadata.

    This function is safe to call in no-server unit tests.  It does not inspect
    a catalog, create files, or issue HTTP requests.
    """
    key = (provider, use_case)
    try:
        definition = CASES[key]
    except KeyError as error:
        available = ", ".join(f"{item[0]}/{item[1]}" for item in sorted(CASES))
        raise KeyError(
            f"Unknown create-table payload case {provider}/{use_case}. "
            f"Available cases: {available}."
        ) from error

    unmet = [
        binding
        for binding in definition.required_bindings
        if getattr(inputs, binding) is None
    ]
    if definition.requires_existing_table and not inputs.existing_table:
        unmet.append("existing_table")
    return CreateTablePayloadPlan(
        provider=provider,
        use_case=use_case,
        payload=definition.builder(inputs),
        outcome=definition.outcome,
        expected_error=definition.expected_error,
        preconditions=definition.preconditions,
        required_bindings=definition.required_bindings,
        unmet_requirements=tuple(unmet),
        sources=definition.sources,
        profile_use_case=definition.profile_use_case,
    )


def build_create_table_payload(
    provider: str,
    use_case: str,
    table_name: str,
    *,
    table_location: str | None = None,
    existing_dataset_location: str | None = None,
    metadata_location: str | None = None,
    existing_table: bool = False,
) -> CreateTablePayloadPlan:
    """Convenience entry point for BDD steps keyed by provider/use case/name."""
    return build_create_table_plan(
        provider,
        use_case,
        PayloadInputs(
            table_name=table_name,
            table_location=table_location,
            existing_dataset_location=existing_dataset_location,
            metadata_location=metadata_location,
            existing_table=existing_table,
        ),
    )
