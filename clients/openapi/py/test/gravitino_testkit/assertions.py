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

"""Assertions shared by real-server V1 endpoint contract tests."""

from typing import Any

from gravitino_client.exceptions import ApiException


def assert_public_error(
    exception: ApiException,
    *,
    status: int,
    error_type: str,
    retryable: bool,
    request_id: str,
) -> Any:
    """Asserts the shared HTTP and JSON error-envelope contract.

    Returns the generated public-error object so a test can additionally assert
    endpoint-specific error details.
    """
    assert exception.status == status
    assert exception.headers is not None
    assert exception.headers.get("X-Request-Id") == request_id

    response = exception.data
    assert response is not None
    error = response.error
    assert error.code == status
    assert error.type == error_type
    assert error.retryable is retryable
    assert error.request_id == request_id
    return error
