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

"""Public-error-envelope assertions for raw create-table journey responses.

These mirror the generated-client contract in ``gravitino_testkit.assertions``
but operate on a raw :class:`~cuj.harness.ApiOutcome`, because the create
journey inspects the exact bytes the server returned rather than a deserialized
client object. Error messages are diagnostic only and are never asserted.
"""

from typing import Any

from .harness import ApiOutcome


def assert_public_error(
    outcome: ApiOutcome,
    *,
    status: int,
    error_type: str,
    retryable: bool,
) -> dict[str, Any]:
    """Asserts the documented HTTP and JSON public-error envelope.

    Returns the ``error`` object so a step can additionally assert
    endpoint-specific field or resource detail.
    """
    assert outcome.status == status, (
        f"expected status {status}, got {outcome.status}: {outcome.text}"
    )
    body = outcome.json
    assert isinstance(body, dict), f"expected a JSON error body, got: {outcome.text}"
    error = body.get("error")
    assert isinstance(error, dict), f"missing public error envelope: {body}"
    assert error.get("code") == status, f"error.code mismatch: {error}"
    assert error.get("type") == error_type, f"error.type mismatch: {error}"
    assert error.get("retryable") is retryable, f"error.retryable mismatch: {error}"
    assert error.get("requestId"), f"error.requestId missing: {error}"
    details = error.get("details")
    assert isinstance(details, list), f"error.details must be an array: {error}"
    return error


def assert_non_retryable(error: dict[str, Any]) -> None:
    """Asserts a public error envelope is marked non-retryable."""
    assert error.get("retryable") is False, f"expected non-retryable error: {error}"
