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
package org.apache.gravitino.catalog;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.Entity;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.exceptions.EncryptionKeyIdImmutableException;
import org.apache.gravitino.exceptions.EncryptionPolicyViolationException;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NoSuchTableException;
import org.apache.gravitino.exceptions.TableAlreadyExistsException;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NameIdentifierUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Evaluates governed Iceberg encryption policy before delegating table creation to the physical
 * catalog.
 *
 * <p>This dispatcher implements the intentionally narrow proof-of-concept applicability model: only
 * tags directly associated with the exact existing schema are considered, and at most one enabled
 * {@code system_iceberg_encryption} policy may match those tags.
 */
public class IcebergEncryptionPolicyTableDispatcher implements TableDispatcher {

  private static final Logger LOG =
      LoggerFactory.getLogger(IcebergEncryptionPolicyTableDispatcher.class);
  private static final String ICEBERG_PROVIDER = "lakehouse-iceberg";
  private static final String ENCRYPTION_KEY_ID = "encryption.key-id";
  private static final String MISSING_KEY_ID = "<missing>";
  private static final String AMBIGUOUS_ENFORCEMENT = "<ambiguous>";

  private final TableDispatcher dispatcher;
  private final SupportsCatalogs catalogs;
  private final Supplier<TagDispatcher> tagDispatcherSupplier;
  private final Supplier<PolicyDispatcher> policyDispatcherSupplier;
  private final Supplier<String> decisionIdSupplier;

  /**
   * Creates a governed encryption table dispatcher.
   *
   * <p>The tag and policy dispatchers are supplied lazily because the table dispatcher chain is
   * initialized before those managers in the server environment.
   *
   * @param dispatcher downstream table dispatcher
   * @param catalogs catalog loader used to limit this policy to Iceberg catalogs
   * @param tagDispatcherSupplier supplier for the tag dispatcher
   * @param policyDispatcherSupplier supplier for the policy dispatcher
   */
  public IcebergEncryptionPolicyTableDispatcher(
      TableDispatcher dispatcher,
      SupportsCatalogs catalogs,
      Supplier<TagDispatcher> tagDispatcherSupplier,
      Supplier<PolicyDispatcher> policyDispatcherSupplier) {
    this(
        dispatcher,
        catalogs,
        tagDispatcherSupplier,
        policyDispatcherSupplier,
        () -> UUID.randomUUID().toString());
  }

  IcebergEncryptionPolicyTableDispatcher(
      TableDispatcher dispatcher,
      SupportsCatalogs catalogs,
      Supplier<TagDispatcher> tagDispatcherSupplier,
      Supplier<PolicyDispatcher> policyDispatcherSupplier,
      Supplier<String> decisionIdSupplier) {
    this.dispatcher = Preconditions.checkNotNull(dispatcher, "dispatcher cannot be null");
    this.catalogs = Preconditions.checkNotNull(catalogs, "catalogs cannot be null");
    this.tagDispatcherSupplier =
        Preconditions.checkNotNull(tagDispatcherSupplier, "tagDispatcherSupplier cannot be null");
    this.policyDispatcherSupplier =
        Preconditions.checkNotNull(
            policyDispatcherSupplier, "policyDispatcherSupplier cannot be null");
    this.decisionIdSupplier =
        Preconditions.checkNotNull(decisionIdSupplier, "decisionIdSupplier cannot be null");
  }

  @Override
  public NameIdentifier[] listTables(Namespace namespace) throws NoSuchSchemaException {
    return dispatcher.listTables(namespace);
  }

  @Override
  public Table loadTable(NameIdentifier ident) throws NoSuchTableException {
    return dispatcher.loadTable(ident);
  }

  @Override
  public Table createTable(
      NameIdentifier ident,
      Column[] columns,
      String comment,
      Map<String, String> properties,
      Transform[] partitions,
      Distribution distribution,
      SortOrder[] sortOrders,
      Index[] indexes)
      throws NoSuchSchemaException, TableAlreadyExistsException {
    if (!isIcebergCatalog(ident)) {
      return dispatcher.createTable(
          ident, columns, comment, properties, partitions, distribution, sortOrders, indexes);
    }

    String metalake = ident.namespace().level(0);
    Set<String> directSchemaTags = directSchemaTags(metalake, ident);
    List<PolicyEntity> matchingPolicies = matchingPolicies(metalake, directSchemaTags);
    String suppliedKeyId = properties == null ? null : properties.get(ENCRYPTION_KEY_ID);

    if (matchingPolicies.size() > 1) {
      rejectAmbiguousPolicies(ident, suppliedKeyId, matchingPolicies);
    }

    if (matchingPolicies.size() == 1) {
      evaluatePolicy(ident, suppliedKeyId, matchingPolicies.get(0));
    }

    return dispatcher.createTable(
        ident, columns, comment, properties, partitions, distribution, sortOrders, indexes);
  }

  @Override
  public Table alterTable(NameIdentifier ident, TableChange... changes)
      throws NoSuchTableException, IllegalArgumentException {
    if (!isIcebergCatalog(ident)) {
      return dispatcher.alterTable(ident, changes);
    }

    Table table = dispatcher.loadTable(ident);
    String currentKeyId = table.properties().get(ENCRYPTION_KEY_ID);
    if (touchesEncryptionKeyId(changes)) {
      String decisionId = decisionIdSupplier.get();
      LOG.warn(
          "Governed encryption key immutability decision decisionId={} actor={} "
              + "normalizedTable={} suppliedKeyId={} reason=KEY_ID_IMMUTABLE outcome=DENIED",
          decisionId,
          PrincipalUtils.getCurrentUserName(),
          ident,
          currentKeyId == null ? MISSING_KEY_ID : currentKeyId);
      throw new EncryptionKeyIdImmutableException(
          "Cannot modify or remove encryption.key-id after table creation for table %s: "
              + "decisionId=%s, "
              + "reason=KEY_ID_IMMUTABLE",
          ident, decisionId);
    }
    return dispatcher.alterTable(ident, changes);
  }

  @Override
  public boolean dropTable(NameIdentifier ident) {
    return dispatcher.dropTable(ident);
  }

  @Override
  public boolean purgeTable(NameIdentifier ident) throws UnsupportedOperationException {
    return dispatcher.purgeTable(ident);
  }

  @Override
  public boolean tableExists(NameIdentifier ident) {
    return dispatcher.tableExists(ident);
  }

  private Set<String> directSchemaTags(String metalake, NameIdentifier tableIdent) {
    NameIdentifier schemaIdent = NameIdentifier.of(tableIdent.namespace().levels());
    MetadataObject schemaObject =
        NameIdentifierUtil.toMetadataObject(schemaIdent, Entity.EntityType.SCHEMA);
    TagDispatcher tagDispatcher =
        Preconditions.checkNotNull(
            tagDispatcherSupplier.get(), "tagDispatcherSupplier returned null");
    String[] tags = tagDispatcher.listTagsForMetadataObject(metalake, schemaObject);
    return tags == null ? new HashSet<>() : new HashSet<>(Arrays.asList(tags));
  }

  private boolean isIcebergCatalog(NameIdentifier tableIdent) {
    NameIdentifier catalogIdent =
        NameIdentifier.of(tableIdent.namespace().level(0), tableIdent.namespace().level(1));
    Catalog catalog = catalogs.loadCatalog(catalogIdent);
    return ICEBERG_PROVIDER.equals(catalog.provider());
  }

  private List<PolicyEntity> matchingPolicies(String metalake, Set<String> directSchemaTags) {
    if (directSchemaTags.isEmpty()) {
      return new ArrayList<>();
    }

    PolicyDispatcher policyDispatcher =
        Preconditions.checkNotNull(
            policyDispatcherSupplier.get(), "policyDispatcherSupplier returned null");
    PolicyEntity[] policies = policyDispatcher.listPolicyInfos(metalake);
    if (policies == null || policies.length == 0) {
      return new ArrayList<>();
    }

    List<PolicyEntity> matches = new ArrayList<>();
    for (PolicyEntity policy : policies) {
      if (policy.enabled() && policy.policyType() == Policy.BuiltInType.ICEBERG_ENCRYPTION) {
        IcebergEncryptionContent content = (IcebergEncryptionContent) policy.content();
        if (directSchemaTags.contains(content.tag())) {
          matches.add(policy);
        }
      }
    }
    matches.sort(Comparator.comparing(PolicyEntity::name));
    return matches;
  }

  private void rejectAmbiguousPolicies(
      NameIdentifier ident, String suppliedKeyId, List<PolicyEntity> matchingPolicies) {
    String decisionId = decisionIdSupplier.get();
    String policyNames =
        matchingPolicies.stream().map(PolicyEntity::name).collect(Collectors.joining(","));
    logDecision(
        decisionId,
        ident,
        policyNames,
        suppliedKeyId,
        Reason.AMBIGUOUS_POLICY,
        AMBIGUOUS_ENFORCEMENT,
        Outcome.DENIED);
    throw new EncryptionPolicyViolationException(
        "Governed encryption policy denied table creation: decisionId=%s, reason=%s, "
            + "table=%s, policy=%s",
        decisionId, Reason.AMBIGUOUS_POLICY, ident, policyNames);
  }

  private void evaluatePolicy(
      NameIdentifier ident, String suppliedKeyId, PolicyEntity matchingPolicy) {
    IcebergEncryptionContent content = (IcebergEncryptionContent) matchingPolicy.content();
    Reason reason = violationReason(content, suppliedKeyId);
    if (reason == null) {
      return;
    }

    String decisionId = decisionIdSupplier.get();
    Outcome outcome =
        content.enforcement() == IcebergEncryptionContent.Enforcement.REPORT
            ? Outcome.ALLOWED
            : Outcome.DENIED;
    logDecision(
        decisionId,
        ident,
        matchingPolicy.name(),
        suppliedKeyId,
        reason,
        content.enforcement().value(),
        outcome);

    if (content.enforcement() == IcebergEncryptionContent.Enforcement.DENY_CREATE) {
      throw new EncryptionPolicyViolationException(
          "Governed encryption policy denied table creation: decisionId=%s, reason=%s, "
              + "table=%s, policy=%s",
          decisionId, reason, ident, matchingPolicy.name());
    }
  }

  private Reason violationReason(IcebergEncryptionContent content, String suppliedKeyId) {
    boolean missingKey = suppliedKeyId == null || suppliedKeyId.isEmpty();
    if (missingKey) {
      return content.required() ? Reason.KEY_REQUIRED : null;
    }
    return content.allowedKeyIds().contains(suppliedKeyId) ? null : Reason.KEY_NOT_ALLOWED;
  }

  private boolean touchesEncryptionKeyId(TableChange[] changes) {
    if (changes == null) {
      return false;
    }

    for (TableChange change : changes) {
      if (change instanceof TableChange.SetProperty
          && ENCRYPTION_KEY_ID.equals(((TableChange.SetProperty) change).getProperty())) {
        return true;
      }
      if (change instanceof TableChange.RemoveProperty
          && ENCRYPTION_KEY_ID.equals(((TableChange.RemoveProperty) change).getProperty())) {
        return true;
      }
    }
    return false;
  }

  private void logDecision(
      String decisionId,
      NameIdentifier ident,
      String policy,
      String suppliedKeyId,
      Reason reason,
      String enforcement,
      Outcome outcome) {
    LOG.warn(
        "Governed encryption decision decisionId={} actor={} normalizedTable={} policy={} "
            + "suppliedKeyId={} reason={} enforcement={} outcome={}",
        decisionId,
        PrincipalUtils.getCurrentUserName(),
        ident,
        policy,
        suppliedKeyId == null || suppliedKeyId.isEmpty() ? MISSING_KEY_ID : suppliedKeyId,
        reason,
        enforcement,
        outcome);
  }

  private enum Reason {
    KEY_REQUIRED,
    KEY_NOT_ALLOWED,
    AMBIGUOUS_POLICY
  }

  private enum Outcome {
    ALLOWED,
    DENIED
  }
}
