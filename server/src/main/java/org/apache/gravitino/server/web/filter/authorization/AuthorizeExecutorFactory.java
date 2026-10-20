/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.gravitino.server.web.filter.authorization;

import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.Optional;
import org.apache.gravitino.Entity;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.server.authorization.annotations.AuthorizationRequest;
import org.apache.gravitino.server.authorization.annotations.ExpressionCondition;

public class AuthorizeExecutorFactory {

  /**
   * Creates the executor for a request, probing table existence through the process environment.
   *
   * @param expression the authorization expression.
   * @param requestType the kind of authorization request.
   * @param metadataContext the metadata the call names.
   * @param pathParams the path parameters of the call.
   * @param entityType the metadata object type of the call, if any.
   * @param parameters the parameters of the REST method.
   * @param args the arguments of the REST call.
   * @param secondaryExpression the stricter expression for a modify request.
   * @param secondaryExpressionCondition when the secondary expression applies.
   * @param allowCheckExistenceExpression the expression that allows probing table existence.
   * @return the executor.
   * @deprecated Use the overload that also takes the internal table dispatcher.
   */
  @Deprecated
  public static AuthorizationExecutor create(
      String expression,
      AuthorizationRequest.RequestType requestType,
      Map<Entity.EntityType, NameIdentifier> metadataContext,
      Map<String, Object> pathParams,
      Optional<String> entityType,
      Parameter[] parameters,
      Object[] args,
      String secondaryExpression,
      ExpressionCondition secondaryExpressionCondition,
      String allowCheckExistenceExpression) {
    return create(
        expression,
        requestType,
        metadataContext,
        pathParams,
        entityType,
        parameters,
        args,
        secondaryExpression,
        secondaryExpressionCondition,
        allowCheckExistenceExpression,
        LoadTableAuthorizationExecutor.processInternalTableDispatcher());
  }

  /**
   * Creates the executor for a request.
   *
   * @param expression the authorization expression.
   * @param requestType the kind of authorization request.
   * @param metadataContext the metadata the call names.
   * @param pathParams the path parameters of the call.
   * @param entityType the metadata object type of the call, if any.
   * @param parameters the parameters of the REST method.
   * @param args the arguments of the REST call.
   * @param secondaryExpression the stricter expression for a modify request.
   * @param secondaryExpressionCondition when the secondary expression applies.
   * @param allowCheckExistenceExpression the expression that allows probing table existence.
   * @param internalTableDispatcher the internal table dispatcher that probes table existence.
   * @return the executor.
   */
  public static AuthorizationExecutor create(
      String expression,
      AuthorizationRequest.RequestType requestType,
      Map<Entity.EntityType, NameIdentifier> metadataContext,
      Map<String, Object> pathParams,
      Optional<String> entityType,
      Parameter[] parameters,
      Object[] args,
      String secondaryExpression,
      ExpressionCondition secondaryExpressionCondition,
      String allowCheckExistenceExpression,
      TableDispatcher internalTableDispatcher) {
    return switch (requestType) {
      case COMMON -> new CommonAuthorizerExecutor(
          expression, metadataContext, pathParams, entityType);
      case ASSOCIATE_TAG -> new AssociateTagAuthorizationExecutor(
          expression, parameters, args, metadataContext, pathParams, entityType);
      case RUN_JOB -> new RunJobAuthorizationExecutor(
          parameters, args, expression, metadataContext, pathParams, entityType);
      case LINEAGE -> new LineageAuthorizationExecutor(parameters, args, expression);
      case LOAD_TABLE -> new LoadTableAuthorizationExecutor(
          parameters,
          args,
          expression,
          metadataContext,
          pathParams,
          entityType,
          secondaryExpression,
          secondaryExpressionCondition,
          allowCheckExistenceExpression,
          internalTableDispatcher);
      case LOAD_VIEW -> new LoadViewAuthorizationExecutor(
          expression, metadataContext, pathParams, entityType, allowCheckExistenceExpression);
      case CREATE_SCHEMA -> new CreateSchemaAuthorizationExecutor(
          parameters, args, expression, metadataContext, pathParams, entityType);
      case TEST_CATALOG_CONNECTION -> new CatalogConnectionTestAuthorizationExecutor(
          parameters,
          args,
          expression,
          metadataContext,
          pathParams,
          entityType,
          secondaryExpression,
          secondaryExpressionCondition);
    };
  }
}
