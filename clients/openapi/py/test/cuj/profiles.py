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

"""Provider profiles and environment resolution for create-table journeys.

A profile is the source of truth for which provider a scenario targets and how
its live environment is discovered. A scenario whose profile has no configured
environment is skipped rather than failed, so the same feature runs against a
local Lance profile or a CI-provisioned JDBC/HMS profile without edits.

Environment configuration is read from process environment variables, one group
per provider, prefixed ``GRAVITINO_CUJ_<PROVIDER>_``:

    GRAVITINO_CUJ_LANCE_BASE_URL   (required to enable the profile)
    GRAVITINO_CUJ_LANCE_METALAKE
    GRAVITINO_CUJ_LANCE_CATALOG
    GRAVITINO_CUJ_LANCE_SCHEMA
    GRAVITINO_CUJ_LANCE_TOKEN      (optional bearer credential)

A profile is considered configured when its ``BASE_URL`` is present.
"""

from collections.abc import Mapping
from dataclasses import dataclass

# Providers referenced by the create-table feature files. Keeping the roster
# here lets a step assert against a known provider instead of a free-form tag.
PROVIDERS: frozenset[str] = frozenset(
    {
        "lance",
        "delta",
        "iceberg",
        "hive",
        "glue",
        "paimon",
        "hudi",
        "mysql",
        "postgresql",
        "doris",
        "starrocks",
        "oceanbase",
        "clickhouse",
        "hologres",
    }
)


@dataclass(frozen=True)
class ProviderEnvironment:
    """A configured live environment for one provider's create-table journey.

    ``metalake``/``catalog``/``schema`` name the writable parent the journey
    provisions tables under; ``token`` is an optional bearer credential.
    """

    provider: str
    base_url: str
    metalake: str
    catalog: str
    var_schema: str
    token: str | None = None


def _env_prefix(provider: str) -> str:
    return f"GRAVITINO_CUJ_{provider.upper()}"


def resolve_environment(
    provider: str, env: Mapping[str, str]
) -> ProviderEnvironment | None:
    """Resolves a provider's live environment, or ``None`` when not configured.

    Returning ``None`` is the signal a step uses to skip a scenario; it is not an
    error. Defaults keep a minimally configured environment (only ``BASE_URL``)
    usable against a conventional local metalake/catalog/schema layout.
    """
    prefix = _env_prefix(provider)
    base_url = env.get(f"{prefix}_BASE_URL")
    if not base_url:
        return None
    return ProviderEnvironment(
        provider=provider,
        base_url=base_url,
        metalake=env.get(f"{prefix}_METALAKE", "gravitino_cuj"),
        catalog=env.get(f"{prefix}_CATALOG", f"cuj_{provider}"),
        var_schema=env.get(f"{prefix}_SCHEMA", "cuj"),
        token=env.get(f"{prefix}_TOKEN"),
    )


def require_known_provider(provider: str) -> str:
    """Returns ``provider`` after asserting it is a recognized profile name."""
    if provider not in PROVIDERS:
        raise ValueError(
            f"unknown create-table provider '{provider}'; "
            f"known providers: {', '.join(sorted(PROVIDERS))}"
        )
    return provider
