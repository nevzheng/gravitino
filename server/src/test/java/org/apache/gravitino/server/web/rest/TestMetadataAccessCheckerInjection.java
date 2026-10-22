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
package org.apache.gravitino.server.web.rest;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import javax.inject.Inject;
import org.apache.gravitino.server.authorization.MetadataAccessChecker;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestMetadataAccessCheckerInjection {

  private static final List<Class<?>> RESOURCES =
      Arrays.asList(
          BulkOperations.class,
          CatalogOperations.class,
          FilesetOperations.class,
          FunctionOperations.class,
          GroupOperations.class,
          JobOperations.class,
          MetadataObjectCredentialOperations.class,
          MetadataObjectPolicyOperations.class,
          MetadataObjectRoleOperations.class,
          MetadataObjectSecretOperations.class,
          MetadataObjectTagOperations.class,
          MetalakeOperations.class,
          ModelOperations.class,
          OwnerOperations.class,
          PermissionOperations.class,
          PolicyOperations.class,
          RoleOperations.class,
          SchemaOperations.class,
          SecretsProviderOperations.class,
          SemanticModelOperations.class,
          TableOperations.class,
          TagOperations.class,
          TopicOperations.class,
          UserOperations.class,
          ViewOperations.class);

  @Test
  public void testInjectConstructorTakesTheChecker() {
    for (Class<?> resource : RESOURCES) {
      List<Constructor<?>> injected =
          Arrays.stream(resource.getConstructors())
              .filter(c -> c.isAnnotationPresent(Inject.class))
              .collect(Collectors.toList());
      Assertions.assertEquals(1, injected.size(), resource.getSimpleName());
      Class<?>[] parameters = injected.get(0).getParameterTypes();
      Assertions.assertEquals(
          MetadataAccessChecker.class,
          parameters[parameters.length - 1],
          resource.getSimpleName() + " must take the checker from HK2");
    }
  }
}
