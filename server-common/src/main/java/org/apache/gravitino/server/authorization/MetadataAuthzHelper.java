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
package org.apache.gravitino.server.authorization;

import java.security.Principal;
import java.util.function.Function;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.Entity;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.Metalake;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.annotation.LegacyEnvAccess;
import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.apache.gravitino.dto.tag.MetadataObjectDTO;
import org.apache.gravitino.server.authorization.expression.AuthorizationExpressionEvaluator;

/**
 * Static access checks over the process environment.
 *
 * <p>Each method delegates to a {@link MetadataAccessChecker} that reads {@code
 * GravitinoEnv.getInstance()} and {@link GravitinoAuthorizerProvider#getInstance()} when it checks.
 *
 * @deprecated Take a {@link MetadataAccessChecker} through the constructor instead; the server
 *     binds one into HK2. This class is deleted with {@code GravitinoEnv}.
 */
@Deprecated
public class MetadataAuthzHelper {

  // Reads the process environment and the authorizer provider on every check, as this class did
  // before checkers were instances: the environment may be initialized after this class is loaded.
  private static final MetadataAccessChecker PROCESS_CHECKER =
      new MetadataAccessChecker(
          () -> {
            Config config = processEnv().config();
            return config != null && config.get(Configs.ENABLE_AUTHORIZATION);
          },
          () ->
              processEnv().cacheEnabled() && processEnv().internalAccessControlDispatcher() != null,
          () -> processEnv().config().get(Configs.GRAVITINO_AUTHORIZATION_THREAD_POOL_SIZE),
          () -> GravitinoAuthorizerProvider.getInstance().getGravitinoAuthorizer(),
          () -> processEnv().entityStore(),
          () -> processEnv().internalSchemaDispatcher());

  private MetadataAuthzHelper() {}

  /**
   * Filters metalakes based on an authorization expression.
   *
   * @param metalakes the metalakes to filter
   * @param expression the authorization expression
   * @return the metalakes the current user has access to
   * @deprecated Use {@link MetadataAccessChecker#filterMetalakes(Metalake[], String)}.
   */
  @Deprecated
  public static Metalake[] filterMetalakes(Metalake[] metalakes, String expression) {
    return processChecker().filterMetalakes(metalakes, expression);
  }

  /**
   * Filters MetadataObjectDTO array based on access permissions.
   *
   * @param metalake The metalake name
   * @param metadataObjects The array of metadata object DTOs to filter
   * @return Filtered array of metadata object DTOs that the current user has access to
   * @deprecated Use {@link MetadataAccessChecker#filterMetadataObject(String,
   *     MetadataObjectDTO[])}.
   */
  @Deprecated
  public static MetadataObjectDTO[] filterMetadataObject(
      String metalake, MetadataObjectDTO[] metadataObjects) {
    return processChecker().filterMetadataObject(metalake, metadataObjects);
  }

  /**
   * Filters MetadataObject array based on access permissions.
   *
   * @param metalake The metalake name
   * @param metadataObjects The array of metadata objects to filter
   * @return Filtered array of metadata objects that the current user has access to
   * @deprecated Use {@link MetadataAccessChecker#filterMetadataObject(String, MetadataObject[])}.
   */
  @Deprecated
  public static MetadataObject[] filterMetadataObject(
      String metalake, MetadataObject[] metadataObjects) {
    return processChecker().filterMetadataObject(metalake, metadataObjects);
  }

  /**
   * Call {@link AuthorizationExpressionEvaluator} to filter the metadata list
   *
   * @param metalake metalake
   * @param expression authorization expression
   * @param entityType for example, CATALOG, SCHEMA,TABLE, etc.
   * @param nameIdentifiers metaData list.
   * @return metadata List that the user has permission to access.
   * @deprecated Use {@link MetadataAccessChecker#filterByExpression(String, String,
   *     Entity.EntityType, NameIdentifier[])}.
   */
  @Deprecated
  public static NameIdentifier[] filterByExpression(
      String metalake,
      String expression,
      Entity.EntityType entityType,
      NameIdentifier[] nameIdentifiers) {
    return processChecker().filterByExpression(metalake, expression, entityType, nameIdentifiers);
  }

  /**
   * Call {@link AuthorizationExpressionEvaluator} to check access
   *
   * @param identifier metadata identifier
   * @param entityType for example, CATALOG, SCHEMA,TABLE, etc.
   * @param expression authorization expression
   * @return whether it has access to the metadata
   * @deprecated Use {@link MetadataAccessChecker#checkAccess(NameIdentifier, Entity.EntityType,
   *     String)}.
   */
  @Deprecated
  public static boolean checkAccess(
      NameIdentifier identifier, Entity.EntityType entityType, String expression) {
    return processChecker().checkAccess(identifier, entityType, expression);
  }

  /**
   * Call {@link AuthorizationExpressionEvaluator} to filter the metadata list
   *
   * @param metalake metalake
   * @param expression expression
   * @param entityType entity type
   * @param entities entities
   * @param toNameIdentifier convert to NameIdentifier
   * @return Filtered Metadata Entity
   * @param <E> Entity class
   * @deprecated Use {@link MetadataAccessChecker#filterByExpression(String, String,
   *     Entity.EntityType, Object[], Function)}.
   */
  @Deprecated
  public static <E> E[] filterByExpression(
      String metalake,
      String expression,
      Entity.EntityType entityType,
      E[] entities,
      Function<E, NameIdentifier> toNameIdentifier) {
    return processChecker()
        .filterByExpression(metalake, expression, entityType, entities, toNameIdentifier);
  }

  /**
   * Call {@link AuthorizationExpressionEvaluator} and use specified Principal and
   * GravitinoAuthorizer to filter the metadata list
   *
   * @param metalake metalake name
   * @param expression authorization expression
   * @param entityType entity type
   * @param entities metadata entities
   * @param toNameIdentifier function to convert entity to NameIdentifier
   * @param currentPrincipal The principal to perform the authorization check as.
   * @param authorizer The authorizer to use for the authorization check.
   * @return Filtered Metadata Entity
   * @param <E> Entity class
   * @deprecated Use {@link MetadataAccessChecker#filterByExpression(String, String,
   *     Entity.EntityType, Object[], Function, Principal, GravitinoAuthorizer)}.
   */
  @Deprecated
  public static <E> E[] filterByExpression(
      String metalake,
      String expression,
      Entity.EntityType entityType,
      E[] entities,
      Function<E, NameIdentifier> toNameIdentifier,
      Principal currentPrincipal,
      GravitinoAuthorizer authorizer) {
    return processChecker()
        .filterByExpression(
            metalake,
            expression,
            entityType,
            entities,
            toNameIdentifier,
            currentPrincipal,
            authorizer);
  }

  /**
   * Returns the checker that reads the process environment on every check.
   *
   * <p>For a caller that cannot take a {@link MetadataAccessChecker} from its host, such as a
   * deprecated constructor.
   *
   * @return the process-wide checker.
   * @deprecated Take a {@link MetadataAccessChecker} through the constructor instead.
   */
  @Deprecated
  public static MetadataAccessChecker processChecker() {
    return PROCESS_CHECKER;
  }

  @LegacyEnvAccess
  private static GravitinoEnv processEnv() {
    return GravitinoEnv.getInstance();
  }
}
