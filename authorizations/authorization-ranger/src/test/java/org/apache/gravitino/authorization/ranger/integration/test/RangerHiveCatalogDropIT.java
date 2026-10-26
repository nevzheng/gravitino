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
package org.apache.gravitino.authorization.ranger.integration.test;

import static org.apache.gravitino.Catalog.AUTHORIZATION_PROVIDER;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Configs;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.auth.AuthenticatorType;
import org.apache.gravitino.authorization.Privileges;
import org.apache.gravitino.authorization.SecurableObject;
import org.apache.gravitino.authorization.SecurableObjects;
import org.apache.gravitino.authorization.common.RangerAuthorizationProperties;
import org.apache.gravitino.authorization.ranger.RangerHelper;
import org.apache.gravitino.catalog.hive.HiveConstants;
import org.apache.gravitino.client.GravitinoMetalake;
import org.apache.gravitino.exceptions.CatalogInUseException;
import org.apache.gravitino.exceptions.NonEmptyCatalogException;
import org.apache.gravitino.integration.test.container.HiveContainer;
import org.apache.gravitino.integration.test.container.RangerContainer;
import org.apache.gravitino.integration.test.util.BaseIT;
import org.apache.gravitino.integration.test.util.GravitinoITUtils;
import org.apache.ranger.RangerServiceException;
import org.apache.ranger.plugin.model.RangerPolicy;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Checks against a real Ranger and Hive that a catalog drop which Gravitino refuses leaves the
 * catalog's Ranger policies in place.
 *
 * <p>The Ranger HadoopSQL plugin handles the removal of a catalog by stripping every
 * Gravitino-managed policy item from every policy of its Ranger service, so removing the privileges
 * before the drop is refused leaves the catalog in place without any of them.
 */
@Tag("gravitino-docker-test")
public class RangerHiveCatalogDropIT extends BaseIT {
  private static final Logger LOG = LoggerFactory.getLogger(RangerHiveCatalogDropIT.class);

  private static final String PROVIDER = "hive";
  private static final String SELECT_ACCESS = "select";

  private final String metalakeName =
      GravitinoITUtils.genRandomName("drop_catalog_metalake").toLowerCase();
  private final String catalogName =
      GravitinoITUtils.genRandomName("drop_catalog_catalog").toLowerCase();

  private GravitinoMetalake metalake;
  private Catalog catalog;

  @BeforeAll
  @Override
  public void startIntegrationTest() throws Exception {
    Map<String, String> configs = Maps.newHashMap();
    configs.put(Configs.ENABLE_AUTHORIZATION.getKey(), String.valueOf(true));
    configs.put(Configs.SERVICE_ADMINS.getKey(), RangerITEnv.HADOOP_USER_NAME);
    configs.put(Configs.AUTHENTICATORS.getKey(), AuthenticatorType.SIMPLE.name().toLowerCase());
    configs.put("SimpleAuthUserName", AuthConstants.ANONYMOUS_USER);
    configs.put(
        Configs.AUTHORIZATION_IMPL.getKey(),
        "org.apache.gravitino.server.authorization.PassThroughAuthorizer");
    registerCustomConfigs(configs);
    super.startIntegrationTest();

    RangerITEnv.init(metalakeName, true);
    RangerITEnv.startHiveRangerContainer();
    RangerITEnv.cleanup();

    String metastoreUris =
        String.format(
            "thrift://%s:%d",
            containerSuite.getHiveRangerContainer().getContainerIpAddress(),
            HiveContainer.HIVE_METASTORE_PORT);

    client.createMetalake(metalakeName, "comment", Collections.emptyMap());
    metalake = client.loadMetalake(metalakeName);

    Map<String, String> properties =
        ImmutableMap.<String, String>builder()
            .put(HiveConstants.METASTORE_URIS, metastoreUris)
            .put(AUTHORIZATION_PROVIDER, "ranger")
            .put(RangerAuthorizationProperties.RANGER_SERVICE_TYPE, "HadoopSQL")
            .put(
                RangerAuthorizationProperties.RANGER_SERVICE_NAME,
                RangerITEnv.RANGER_HIVE_REPO_NAME)
            .put(RangerAuthorizationProperties.RANGER_ADMIN_URL, RangerITEnv.RANGER_ADMIN_URL)
            .put(RangerAuthorizationProperties.RANGER_AUTH_TYPE, RangerContainer.authType)
            .put(RangerAuthorizationProperties.RANGER_USERNAME, RangerContainer.rangerUserName)
            .put(RangerAuthorizationProperties.RANGER_PASSWORD, RangerContainer.rangerPassword)
            .build();
    catalog =
        metalake.createCatalog(
            catalogName, Catalog.Type.RELATIONAL, PROVIDER, "comment", properties);
    LOG.info("Catalog created: {}", catalog);
  }

  @AfterAll
  public void stop() {
    if (client != null) {
      try {
        metalake.enableCatalog(catalogName);
        Arrays.stream(catalog.asSchemas().listSchemas())
            .filter(schema -> !schema.equals("default"))
            .forEach(schema -> catalog.asSchemas().dropSchema(schema, true));
        metalake.dropCatalog(catalogName, true);
        client.disableMetalake(metalakeName);
        client.dropMetalake(metalakeName);
      } catch (Exception e) {
        LOG.error("Failed to clean up the metalake {}", metalakeName, e);
      }
    }
    RangerITEnv.cleanup();
  }

  @Test
  public void testRefusedDropOfCatalogInUseKeepsRangerPolicies() throws RangerServiceException {
    String roleName = grantCatalogRoleToNewUser("in_use");
    Set<String> managedPolicyItemsBeforeDrop = gravitinoManagedPolicyItems();
    Assertions.assertTrue(
        hasRolePolicyItem(roleName), "The role's Ranger policy items must exist before the drop");

    // The catalog is enabled, so a drop without force must be refused.
    Assertions.assertThrows(
        CatalogInUseException.class, () -> metalake.dropCatalog(catalogName, false));
    Assertions.assertNotNull(metalake.loadCatalog(catalogName));

    Assertions.assertTrue(
        hasRolePolicyItem(roleName),
        "A refused catalog drop must keep the role's Ranger policy items");
    Assertions.assertEquals(
        managedPolicyItemsBeforeDrop,
        gravitinoManagedPolicyItems(),
        "A refused catalog drop must not change any Gravitino-managed Ranger policy item");
  }

  @Test
  public void testRefusedDropOfNonEmptyCatalogKeepsRangerPolicies() throws RangerServiceException {
    String schemaName = GravitinoITUtils.genRandomName("drop_catalog_schema").toLowerCase();
    catalog.asSchemas().createSchema(schemaName, "comment", Collections.emptyMap());
    String roleName = grantCatalogRoleToNewUser("non_empty");
    Set<String> managedPolicyItemsBeforeDrop = gravitinoManagedPolicyItems();
    Assertions.assertTrue(
        hasRolePolicyItem(roleName), "The role's Ranger policy items must exist before the drop");

    metalake.disableCatalog(catalogName);
    try {
      // The catalog is disabled but still has a schema, so a drop without force must be refused.
      Assertions.assertThrows(
          NonEmptyCatalogException.class, () -> metalake.dropCatalog(catalogName, false));
    } finally {
      metalake.enableCatalog(catalogName);
    }
    Assertions.assertNotNull(metalake.loadCatalog(catalogName));

    Assertions.assertTrue(
        hasRolePolicyItem(roleName),
        "A refused catalog drop must keep the role's Ranger policy items");
    Assertions.assertEquals(
        managedPolicyItemsBeforeDrop,
        gravitinoManagedPolicyItems(),
        "A refused catalog drop must not change any Gravitino-managed Ranger policy item");

    catalog.asSchemas().dropSchema(schemaName, true);
  }

  private String grantCatalogRoleToNewUser(String suffix) {
    String userName = "user_2796_" + suffix;
    String roleName = "role_2796_" + suffix;
    metalake.addUser(userName);
    SecurableObject catalogObject =
        SecurableObjects.ofCatalog(
            catalogName,
            Lists.newArrayList(
                Privileges.UseCatalog.allow(),
                Privileges.UseSchema.allow(),
                Privileges.SelectTable.allow()));
    metalake.createRole(roleName, Collections.emptyMap(), Lists.newArrayList(catalogObject));
    metalake.grantRolesToUser(Lists.newArrayList(roleName), userName);
    return roleName;
  }

  /** Whether some Ranger policy of the service allows `select` to the Gravitino role. */
  private boolean hasRolePolicyItem(String roleName) throws RangerServiceException {
    String rangerRoleName = RangerHelper.GRAVITINO_ROLE_PREFIX + roleName;
    return policies().stream()
        .flatMap(policy -> policy.getPolicyItems().stream())
        .anyMatch(
            item ->
                item.getRoles().contains(rangerRoleName)
                    && item.getAccesses().stream()
                        .anyMatch(access -> SELECT_ACCESS.equals(access.getType())));
  }

  /**
   * Describes every Gravitino-managed policy item of the service, together with the resources of
   * its policy, so that two snapshots can be compared.
   */
  private Set<String> gravitinoManagedPolicyItems() throws RangerServiceException {
    Set<String> items = new TreeSet<>();
    for (RangerPolicy policy : policies()) {
      Map<String, Set<String>> resources = new TreeMap<>();
      policy
          .getResources()
          .forEach((key, value) -> resources.put(key, new TreeSet<>(value.getValues())));
      addManagedItems(items, resources + "|allow", policy.getPolicyItems());
      addManagedItems(items, resources + "|deny", policy.getDenyPolicyItems());
    }
    LOG.info("Gravitino-managed Ranger policy items: {}", items);
    return items;
  }

  private static void addManagedItems(
      Set<String> items, String prefix, List<RangerPolicy.RangerPolicyItem> policyItems) {
    for (RangerPolicy.RangerPolicyItem item : policyItems) {
      if (!RangerHelper.isGravitinoManagedPolicyItemAccess(item)) {
        continue;
      }
      items.add(
          prefix
              + "|accesses="
              + item.getAccesses().stream()
                  .map(RangerPolicy.RangerPolicyItemAccess::getType)
                  .sorted()
                  .collect(Collectors.toList())
              + "|roles="
              + new TreeSet<>(item.getRoles())
              + "|users="
              + new TreeSet<>(item.getUsers())
              + "|groups="
              + new TreeSet<>(item.getGroups()));
    }
  }

  private static List<RangerPolicy> policies() throws RangerServiceException {
    return RangerITEnv.rangerClient.getPoliciesInService(RangerITEnv.RANGER_HIVE_REPO_NAME);
  }
}
