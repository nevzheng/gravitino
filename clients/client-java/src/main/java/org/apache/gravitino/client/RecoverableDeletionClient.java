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
package org.apache.gravitino.client;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.annotation.Nullable;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.DeletedEntity;
import org.apache.gravitino.dto.requests.EntityRestoreRequest;
import org.apache.gravitino.dto.responses.DeletedEntityListResponse;
import org.apache.gravitino.dto.responses.DeletedEntityResponse;
import org.apache.gravitino.dto.responses.ErrorResponse;
import org.apache.gravitino.rest.RESTResponse;
import org.apache.hc.core5.http.HttpHeaders;

/** Shared REST plumbing for typed recoverable-deletion client operations. */
final class RecoverableDeletionClient {

  static final String MERGE_PATCH_CONTENT_TYPE = "application/merge-patch+json";

  private static final String INCLUDE = "include";
  private static final String INCLUDE_DELETED = "deleted";
  private static final String NAME = "name";
  private static final String ID = "id";

  private final RESTClient restClient;

  RecoverableDeletionClient(RESTClient restClient) {
    this.restClient = Objects.requireNonNull(restClient, "restClient must not be null");
  }

  DeletedEntity[] listDeleted(
      String collectionPath,
      @Nullable String name,
      @Nullable String id,
      Consumer<ErrorResponse> errorHandler) {
    checkPath(collectionPath);
    Preconditions.checkArgument(
        name == null || StringUtils.isNotBlank(name), "name must not be blank");
    Preconditions.checkArgument(id == null || StringUtils.isNotBlank(id), "id must not be blank");

    Map<String, String> queryParams = deletedQueryParams(id);
    if (name != null) {
      queryParams.put(NAME, name);
    }

    DeletedEntityListResponse response =
        restClient.get(
            collectionPath,
            queryParams,
            DeletedEntityListResponse.class,
            Collections.emptyMap(),
            ErrorHandlers.recoveryErrorHandler(errorHandler));
    response.validate();
    return response.getDeletedEntities();
  }

  DeletedEntity loadDeleted(String itemPath, String id, Consumer<ErrorResponse> errorHandler) {
    checkPath(itemPath);
    checkId(id);

    DeletedEntityResponse response =
        restClient.get(
            itemPath,
            deletedQueryParams(id),
            DeletedEntityResponse.class,
            Collections.emptyMap(),
            ErrorHandlers.recoveryErrorHandler(errorHandler));
    response.validate();
    return response.getDeletedEntity();
  }

  <T extends RESTResponse> T restoreDeleted(
      String itemPath,
      DeletedEntity deletedEntity,
      Class<T> responseType,
      Consumer<ErrorResponse> errorHandler) {
    checkPath(itemPath);
    Objects.requireNonNull(deletedEntity, "deletedEntity must not be null");
    Objects.requireNonNull(responseType, "responseType must not be null");
    Preconditions.checkArgument(deletedEntity.deleted(), "deletedEntity must be deleted");
    checkId(deletedEntity.id());

    String etag = deletedEntity.etag();
    Preconditions.checkArgument(
        StringUtils.isNotBlank(etag), "deletedEntity etag must not be blank");
    Preconditions.checkArgument(
        !etag.startsWith("W/") && isValidOpaqueEtag(etag),
        "deletedEntity etag must be an unquoted strong ETag token");

    EntityRestoreRequest request = new EntityRestoreRequest(false);
    request.validate();
    Map<String, String> headers =
        ImmutableMap.of(
            HttpHeaders.CONTENT_TYPE,
            MERGE_PATCH_CONTENT_TYPE,
            HttpHeaders.IF_MATCH,
            quoteStrongEtag(etag));

    T response =
        restClient.patch(
            itemPath,
            deletedQueryParams(deletedEntity.id()),
            request,
            responseType,
            headers,
            ErrorHandlers.recoveryErrorHandler(errorHandler));
    response.validate();
    return response;
  }

  private static Map<String, String> deletedQueryParams(@Nullable String id) {
    Map<String, String> queryParams = new LinkedHashMap<>();
    queryParams.put(INCLUDE, INCLUDE_DELETED);
    if (id != null) {
      queryParams.put(ID, id);
    }
    return queryParams;
  }

  private static String quoteStrongEtag(String etag) {
    return '"' + etag + '"';
  }

  private static boolean isValidOpaqueEtag(String etag) {
    for (int index = 0; index < etag.length(); index++) {
      char character = etag.charAt(index);
      if (character != 0x21
          && !(character >= 0x23 && character <= 0x7e)
          && !(character >= 0x80 && character <= 0xff)) {
        return false;
      }
    }
    return true;
  }

  private static void checkPath(String path) {
    Preconditions.checkArgument(StringUtils.isNotBlank(path), "path must not be blank");
  }

  private static void checkId(String id) {
    Preconditions.checkArgument(StringUtils.isNotBlank(id), "id must not be blank");
  }
}
