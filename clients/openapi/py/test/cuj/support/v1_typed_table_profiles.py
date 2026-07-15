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

"""Typed V1 table-create profiles for the connector round-trip CUJs.

The existing create-table matrix intentionally mirrors current public
``properties`` behavior.  This module is different: it is the target V1
contract matrix.  It has one minimal profile per table-capable connector and
uses only shared storage fields plus zero or one strongly typed provider
options block.  A profile is not evidence that an external environment was
provisioned; live scenarios explicitly opt in and skip without configuration.
"""

from __future__ import annotations

from collections.abc import Mapping
from copy import deepcopy
from dataclasses import dataclass
from types import MappingProxyType
from typing import Any

from cuj.support.transport import ExpectedApiError
from gravitino_testkit.v1_typed_tables import typed_option_keys, typed_table_create_body


_UNSUPPORTED_OPERATION = ExpectedApiError(501, "UNSUPPORTED_OPERATION", False)


@dataclass(frozen=True)
class TypedTableCreatePlan:
    """A target typed V1 create body and its external-fixture requirements."""

    provider: str
    body: dict[str, Any]
    unmet_requirements: tuple[str, ...]
    create_expected_error: ExpectedApiError | None
    update_expected_error: ExpectedApiError | None
    sources: tuple[str, ...]

    def request_body(self) -> dict[str, Any]:
        """Returns an isolated payload copy suitable for a raw V1 POST."""
        return deepcopy(self.body)

    @property
    def is_ready_to_send(self) -> bool:
        """Returns whether caller-owned external fixture bindings were supplied."""
        return not self.unmet_requirements


@dataclass(frozen=True)
class TypedTableCreateProfile:
    """The minimum clean V1 create shape for one table-capable provider."""

    provider: str
    display_name: str
    storage: Mapping[str, str] | None
    option_blocks: Mapping[str, Mapping[str, Any]]
    layout: Mapping[str, Any]
    location_binding: str | None
    create_expected_error: ExpectedApiError | None
    update_expected_error: ExpectedApiError | None
    sources: tuple[str, ...]
    notes: tuple[str, ...] = ()

    def __post_init__(self) -> None:
        """Freezes profile maps and rejects an ambiguous provider-options shape."""
        if not self.provider:
            raise ValueError("Typed table profiles require a provider name.")
        if not self.display_name:
            raise ValueError("Typed table profiles require a display name.")
        storage = None if self.storage is None else MappingProxyType(dict(self.storage))
        option_blocks = {
            key: MappingProxyType(dict(value)) for key, value in self.option_blocks.items()
        }
        layout = MappingProxyType(deepcopy(dict(self.layout)))
        if set(option_blocks).difference(typed_option_keys()):
            raise ValueError(f"{self.provider} declares an unknown typed options block.")
        if len(option_blocks) > 1:
            raise ValueError(
                f"{self.provider} must use zero or one typed provider-options block."
            )
        if self.location_binding is not None and storage is None:
            raise ValueError("A location binding requires a shared storage object.")
        unknown_layout = set(layout).difference(
            {"partitioning", "distribution", "sortOrders", "indexes"}
        )
        if unknown_layout:
            raise ValueError(
                f"{self.provider} declares unknown typed layout fields: {sorted(unknown_layout)!r}."
            )
        object.__setattr__(self, "storage", storage)
        object.__setattr__(self, "option_blocks", MappingProxyType(option_blocks))
        object.__setattr__(self, "layout", layout)
        object.__setattr__(self, "sources", tuple(self.sources))
        object.__setattr__(self, "notes", tuple(self.notes))

    def build_plan(
        self,
        table_name: str,
        *,
        table_location: str | None = None,
        existing_dataset_location: str | None = None,
    ) -> TypedTableCreatePlan:
        """Builds a minimal strict V1 payload from fixture-owned location bindings."""
        bindings = {
            "table_location": table_location,
            "existing_dataset_location": existing_dataset_location,
        }
        storage = None if self.storage is None else dict(self.storage)
        unmet: list[str] = []
        if self.location_binding is not None:
            bound_location = bindings[self.location_binding]
            if bound_location is None:
                storage = dict(storage or {})
                storage["location"] = f"<fixture:{self.location_binding}>"
                unmet.append(self.location_binding)
            else:
                storage = dict(storage or {})
                storage["location"] = bound_location
        return TypedTableCreatePlan(
            provider=self.provider,
            body=typed_table_create_body(
                table_name,
                _minimal_columns(),
                comment="created by the typed V1 table CUJ",
                storage=storage,
                option_blocks=self.option_blocks,
                partitioning=self.layout.get("partitioning", []),
                distribution=self.layout.get("distribution"),
                sort_orders=self.layout.get("sortOrders", []),
                indexes=self.layout.get("indexes", []),
            ),
            unmet_requirements=tuple(unmet),
            create_expected_error=self.create_expected_error,
            update_expected_error=self.update_expected_error,
            sources=self.sources,
        )


def typed_table_profile(provider: str) -> TypedTableCreateProfile:
    """Returns a named target V1 profile or lists the supported provider names."""
    try:
        return TYPED_TABLE_PROFILES[provider]
    except KeyError as error:
        available = ", ".join(sorted(TYPED_TABLE_PROFILES))
        raise KeyError(
            f"Unknown typed V1 table provider {provider!r}. Available providers: {available}."
        ) from error


def build_typed_table_create_plan(
    provider: str,
    table_name: str,
    *,
    table_location: str | None = None,
    existing_dataset_location: str | None = None,
) -> TypedTableCreatePlan:
    """Builds the minimal typed V1 POST request for one connector profile."""
    return typed_table_profile(provider).build_plan(
        table_name,
        table_location=table_location,
        existing_dataset_location=existing_dataset_location,
    )


def typed_table_provider_names() -> tuple[str, ...]:
    """Returns every provider with a V1 typed minimal-create profile."""
    return tuple(sorted(TYPED_TABLE_PROFILES))


def _minimal_columns() -> list[dict[str, Any]]:
    """Builds the smallest cross-provider table schema used by profile creates."""
    return [
        {
            "name": "id",
            "type": {"kind": "LONG", "signed": True},
            "nullable": False,
            "autoIncrement": False,
        }
    ]


def _profile(
    provider: str,
    display_name: str,
    *,
    storage: Mapping[str, str] | None = None,
    option_blocks: Mapping[str, Mapping[str, Any]] | None = None,
    layout: Mapping[str, Any] | None = None,
    location_binding: str | None = None,
    create_expected_error: ExpectedApiError | None = None,
    update_expected_error: ExpectedApiError | None = None,
    sources: tuple[str, ...],
    notes: tuple[str, ...] = (),
) -> TypedTableCreateProfile:
    """Creates one immutable profile while keeping the declaration table concise."""
    return TypedTableCreateProfile(
        provider=provider,
        display_name=display_name,
        storage=storage,
        option_blocks={} if option_blocks is None else option_blocks,
        layout={} if layout is None else layout,
        location_binding=location_binding,
        create_expected_error=create_expected_error,
        update_expected_error=update_expected_error,
        sources=sources,
        notes=notes,
    )


TYPED_TABLE_PROFILES: Mapping[str, TypedTableCreateProfile] = MappingProxyType(
    {
        "lance": _profile(
            "lance",
            "Generic Lakehouse Lance",
            storage={"ownership": "MANAGED", "tableFormat": "LANCE"},
            location_binding="table_location",
            sources=("docs/lakehouse-generic-lance-table.md#table-management",),
            notes=("A fixture-owned writable Lance location is required.",),
        ),
        "delta": _profile(
            "delta",
            "Generic Lakehouse Delta",
            storage={"ownership": "EXTERNAL", "tableFormat": "DELTA"},
            location_binding="existing_dataset_location",
            update_expected_error=_UNSUPPORTED_OPERATION,
            sources=("docs/lakehouse-generic-delta-table.md#table-management",),
            notes=(
                "POST registers metadata for a pre-existing Delta dataset; it does not create data.",
                "Delta alter remains a visible non-blocking 501 observation in this POC.",
            ),
        ),
        "iceberg": _profile(
            "iceberg",
            "Apache Iceberg",
            storage={
                "ownership": "MANAGED",
                "tableFormat": "ICEBERG",
                "fileFormat": "PARQUET",
            },
            option_blocks={"icebergOptions": {"formatVersion": 2}},
            sources=("docs/lakehouse-iceberg-catalog.md#table-properties",),
        ),
        "hive": _profile(
            "hive",
            "Apache Hive",
            storage={
                "ownership": "MANAGED",
                "tableFormat": "HIVE",
                "fileFormat": "ORC",
            },
            option_blocks={
                "hiveOptions": {
                    "serdeLibrary": "org.apache.hadoop.hive.ql.io.orc.OrcSerde"
                }
            },
            sources=("docs/apache-hive-catalog.md#table-properties",),
        ),
        "glue": _profile(
            "glue",
            "AWS Glue",
            storage={
                "ownership": "EXTERNAL",
                "tableFormat": "HIVE",
                "fileFormat": "PARQUET",
            },
            location_binding="table_location",
            sources=("docs/aws-glue-catalog.md#table-properties",),
            notes=(
                "Glue table creation emits EXTERNAL_TABLE; the fixture owns the supplied location.",
            ),
        ),
        "paimon": _profile(
            "paimon",
            "Apache Paimon",
            create_expected_error=_UNSUPPORTED_OPERATION,
            sources=("docs/lakehouse-paimon-catalog.md#table-properties",),
            notes=(
                "Paimon has no lossless typed V1 read profile yet, so POST returns a visible "
                "non-blocking UNSUPPORTED_OPERATION until its persistent connector state can "
                "round-trip through the public contract.",
            ),
        ),
        "hudi": _profile(
            "hudi",
            "Apache Hudi",
            create_expected_error=_UNSUPPORTED_OPERATION,
            sources=("docs/lakehouse-hudi-catalog.md#table",),
            notes=("Hudi catalogs are read-only; the create POST is a non-blocking error CUJ.",),
        ),
        "mysql": _profile(
            "mysql",
            "MySQL",
            option_blocks={"mysqlOptions": {"engine": "InnoDB"}},
            sources=("docs/jdbc-mysql-catalog.md#table-properties",),
        ),
        "postgresql": _profile(
            "postgresql",
            "PostgreSQL",
            sources=("docs/jdbc-postgresql-catalog.md#table",),
        ),
        "doris": _profile(
            "doris",
            "Apache Doris",
            layout={
                "distribution": {
                    "strategy": "HASH",
                    "bucketCount": 8,
                    "expressions": [{"type": "reference", "name": "id"}],
                }
            },
            sources=("docs/jdbc-doris-catalog.md#table",),
        ),
        "starrocks": _profile(
            "starrocks",
            "StarRocks",
            layout={
                "distribution": {
                    "strategy": "HASH",
                    "bucketCount": 8,
                    "expressions": [{"type": "reference", "name": "id"}],
                }
            },
            sources=("docs/jdbc-starrocks-catalog.md#table",),
        ),
        "oceanbase": _profile(
            "oceanbase",
            "OceanBase",
            sources=("docs/jdbc-oceanbase-catalog.md#table",),
        ),
        "clickhouse": _profile(
            "clickhouse",
            "ClickHouse",
            option_blocks={"clickhouseOptions": {"engine": "MergeTree"}},
            layout={
                "distribution": {"strategy": "NONE", "expressions": []},
                "sortOrders": [
                    {
                        "expression": {"type": "reference", "name": "id"},
                        "direction": "ASC",
                        "nullOrdering": "NULLS_LAST",
                    }
                ],
            },
            sources=("docs/jdbc-clickhouse-catalog.md#table-properties",),
        ),
        "hologres": _profile(
            "hologres",
            "Hologres",
            sources=("docs/jdbc-hologres-catalog.md#table",),
        ),
    }
)
