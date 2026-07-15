/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.gravitino.server.web.rest.v1.error;

import java.util.List;
import java.util.Locale;
import javax.annotation.Nullable;
import javax.ws.rs.core.Response;
import org.apache.gravitino.server.web.RequestContextFilter;

/** Shared header validation for route-versioned V1 API requests. */
final class V1RequestContract {

  private static final String LEGACY_VENDOR_MEDIA_PREFIX = "application/vnd.gravitino.";

  private V1RequestContract() {}

  /**
   * Returns a public error response when V1 request headers violate the public contract.
   *
   * @param requestId caller-provided request ID, if any.
   * @param acceptValues all Accept header field values, if any.
   * @return a public error response, or {@code null} when the headers are valid.
   */
  @Nullable
  static Response validationFailure(
      @Nullable String requestId, @Nullable List<String> acceptValues) {
    if (requestId != null && !RequestContextFilter.isValidRequestId(requestId)) {
      return V1PublicErrorTranslator.toInvalidArgumentResponse(
          RequestContextFilter.REQUEST_ID_HEADER,
          "Must contain 1 to 128 safe ASCII identifier characters.");
    }
    if (hasLegacyVendorMediaType(acceptValues)) {
      return V1PublicErrorTranslator.toResponseForStatus(406);
    }
    return null;
  }

  private static boolean hasLegacyVendorMediaType(@Nullable List<String> acceptValues) {
    if (acceptValues == null) {
      return false;
    }
    for (String acceptValue : acceptValues) {
      if (acceptValue == null) {
        continue;
      }
      for (String mediaRange : acceptValue.split(",")) {
        if (mediaRange.trim().toLowerCase(Locale.ROOT).startsWith(LEGACY_VENDOR_MEDIA_PREFIX)) {
          return true;
        }
      }
    }
    return false;
  }
}
