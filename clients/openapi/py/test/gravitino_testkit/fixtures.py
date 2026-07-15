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

"""Reusable endpoint-test fixtures for generated Gravitino OpenAPI clients."""

from collections.abc import Iterator
import os

import pytest

from gravitino_client.api.tables_api import TablesApi
from gravitino_client.api_client import ApiClient
from gravitino_client.configuration import Configuration


@pytest.fixture(scope="session")
def gravitino_openapi_base_url() -> str:
    """Returns the Gravitino base URL used by endpoint contract probes."""
    return os.environ.get("GRAVITINO_OPENAPI_BASE_URL", "http://localhost:8090")


@pytest.fixture
def tables_api(gravitino_openapi_base_url: str) -> Iterator[TablesApi]:
    """Creates a generated V1 tables API client for one endpoint test."""
    configuration = Configuration(host=gravitino_openapi_base_url)
    with ApiClient(configuration) as api_client:
        yield TablesApi(api_client)
