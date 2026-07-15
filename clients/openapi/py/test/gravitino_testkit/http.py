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

"""Small raw-HTTP helpers for endpoint contract tests."""

from dataclasses import dataclass
import json
from typing import Any

import urllib3


@dataclass(frozen=True)
class HttpResponse:
    """Captures an HTTP response without generated-client deserialization."""

    status: int
    headers: dict[str, str]
    body: bytes


@dataclass(frozen=True)
class JsonResponse:
    """Captures a JSON HTTP response without generated-client deserialization."""

    status: int
    headers: dict[str, str]
    body: Any


def request(
    base_url: str,
    method: str,
    path: str,
    *,
    headers: dict[str, str] | None = None,
) -> HttpResponse:
    """Makes an HTTP request with bounded connection and read timeouts."""
    http = urllib3.PoolManager()
    response = http.request(
        method,
        f"{base_url.rstrip('/')}/{path.lstrip('/')}",
        headers=headers,
        timeout=urllib3.Timeout(connect=5.0, read=15.0),
    )
    try:
        return HttpResponse(
            status=response.status,
            headers=dict(response.headers.items()),
            body=response.data,
        )
    finally:
        response.release_conn()
        http.clear()


def request_json(
    base_url: str,
    method: str,
    path: str,
    *,
    headers: dict[str, str] | None = None,
) -> JsonResponse:
    """Makes an HTTP request and decodes its JSON response body."""
    response = request(base_url, method, path, headers=headers)
    return JsonResponse(
        status=response.status,
        headers=response.headers,
        body=json.loads(response.body.decode("utf-8")),
    )
