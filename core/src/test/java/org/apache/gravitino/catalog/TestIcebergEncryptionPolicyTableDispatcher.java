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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.gravitino.Catalog;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.exceptions.EncryptionKeyIdImmutableException;
import org.apache.gravitino.exceptions.EncryptionPolicyViolationException;
import org.apache.gravitino.meta.AuditInfo;
import org.apache.gravitino.meta.PolicyEntity;
import org.apache.gravitino.policy.IcebergEncryptionContent;
import org.apache.gravitino.policy.Policy;
import org.apache.gravitino.policy.PolicyContents;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.rel.Column;
import org.apache.gravitino.rel.Table;
import org.apache.gravitino.rel.TableChange;
import org.apache.gravitino.rel.expressions.distributions.Distribution;
import org.apache.gravitino.rel.expressions.distributions.Distributions;
import org.apache.gravitino.rel.expressions.sorts.SortOrder;
import org.apache.gravitino.rel.expressions.sorts.SortOrders;
import org.apache.gravitino.rel.expressions.transforms.Transform;
import org.apache.gravitino.rel.expressions.transforms.Transforms;
import org.apache.gravitino.rel.indexes.Index;
import org.apache.gravitino.rel.indexes.Indexes;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.NamespaceUtil;
import org.apache.gravitino.utils.PrincipalUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.AbstractConfiguration;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

public class TestIcebergEncryptionPolicyTableDispatcher {

  private static final String METALAKE = "metalake";
  private static final String TAG = "PII";
  private static final String ACTOR = "test-actor";
  private static final String DECISION_ID = "decision-123";
  private static final String ENCRYPTION_KEY_ID = "encryption.key-id";
  private static final NameIdentifier TABLE_IDENT =
      NameIdentifier.of(METALAKE, "catalog", "schema", "table");
  private static final String LOGGER_NAME = IcebergEncryptionPolicyTableDispatcher.class.getName();

  private TableDispatcher downstream;
  private SupportsCatalogs catalogs;
  private TagDispatcher tagDispatcher;
  private PolicyDispatcher policyDispatcher;
  private Table createdTable;
  private IcebergEncryptionPolicyTableDispatcher dispatcher;
  private CaptureAppender captureAppender;
  private LoggerContext loggerContext;
  private long nextPolicyId;

  @BeforeEach
  public void setUp() {
    downstream = mock(TableDispatcher.class);
    catalogs = mock(SupportsCatalogs.class);
    tagDispatcher = mock(TagDispatcher.class);
    policyDispatcher = mock(PolicyDispatcher.class);
    createdTable = mock(Table.class);
    nextPolicyId = 1L;

    Catalog catalog = mock(Catalog.class);
    when(catalog.provider()).thenReturn("lakehouse-iceberg");
    when(catalogs.loadCatalog(NameIdentifier.of(METALAKE, "catalog"))).thenReturn(catalog);

    when(tagDispatcher.listTagsForMetadataObject(eq(METALAKE), any(MetadataObject.class)))
        .thenReturn(new String[] {TAG});
    when(policyDispatcher.listPolicyInfos(METALAKE)).thenReturn(new PolicyEntity[0]);
    when(downstream.createTable(
            any(NameIdentifier.class),
            any(Column[].class),
            any(String.class),
            anyMap(),
            any(Transform[].class),
            any(Distribution.class),
            any(SortOrder[].class),
            any(Index[].class)))
        .thenReturn(createdTable);

    dispatcher =
        new IcebergEncryptionPolicyTableDispatcher(
            downstream, catalogs, () -> tagDispatcher, () -> policyDispatcher, () -> DECISION_ID);

    loggerContext =
        (LoggerContext)
            LogManager.getContext(
                IcebergEncryptionPolicyTableDispatcher.class.getClassLoader(), false);
    Configuration configuration = loggerContext.getConfiguration();
    captureAppender = new CaptureAppender("encryptionPolicyCapture");
    captureAppender.start();
    configuration.addAppender(captureAppender);
    LoggerConfig loggerConfig = new LoggerConfig(LOGGER_NAME, Level.WARN, false);
    loggerConfig.addAppender(captureAppender, Level.WARN, null);
    configuration.addLogger(LOGGER_NAME, loggerConfig);
    loggerContext.updateLoggers();
  }

  @AfterEach
  public void tearDown() {
    AbstractConfiguration configuration = (AbstractConfiguration) loggerContext.getConfiguration();
    configuration.removeLogger(LOGGER_NAME);
    captureAppender.stop();
    configuration.removeAppender(captureAppender.getName());
    loggerContext.updateLoggers();
  }

  @Test
  public void testNonIcebergCatalogDelegatesWithoutEvaluatingPolicy() throws Exception {
    Catalog catalog = mock(Catalog.class);
    when(catalog.provider()).thenReturn("jdbc-mysql");
    when(catalogs.loadCatalog(NameIdentifier.of(METALAKE, "catalog"))).thenReturn(catalog);

    Assertions.assertSame(createdTable, createTable(Collections.emptyMap()));

    verify(tagDispatcher, never())
        .listTagsForMetadataObject(any(String.class), any(MetadataObject.class));
    verify(policyDispatcher, never()).listPolicyInfos(any(String.class));
    verifyCreateDelegated();
  }

  @Test
  public void testNonIcebergCatalogDelegatesKeyIdAlter() {
    Catalog catalog = mock(Catalog.class);
    when(catalog.provider()).thenReturn("jdbc-mysql");
    when(catalogs.loadCatalog(NameIdentifier.of(METALAKE, "catalog"))).thenReturn(catalog);
    Table alteredTable = mock(Table.class);
    TableChange change = TableChange.setProperty(ENCRYPTION_KEY_ID, "key-a");
    when(downstream.alterTable(TABLE_IDENT, change)).thenReturn(alteredTable);

    Assertions.assertSame(alteredTable, dispatcher.alterTable(TABLE_IDENT, change));

    verify(downstream, never()).loadTable(TABLE_IDENT);
    verify(downstream).alterTable(TABLE_IDENT, change);
  }

  @Test
  public void testNoDirectSchemaTagDelegatesWithoutLoadingPolicies() throws Exception {
    when(tagDispatcher.listTagsForMetadataObject(eq(METALAKE), any(MetadataObject.class)))
        .thenReturn(new String[0]);

    Assertions.assertSame(createdTable, createTable(Collections.emptyMap()));

    ArgumentCaptor<MetadataObject> objectCaptor = ArgumentCaptor.forClass(MetadataObject.class);
    verify(tagDispatcher).listTagsForMetadataObject(eq(METALAKE), objectCaptor.capture());
    Assertions.assertEquals(
        MetadataObjects.of("catalog", "schema", MetadataObject.Type.SCHEMA),
        objectCaptor.getValue());
    verify(policyDispatcher, never()).listPolicyInfos(any(String.class));
    verifyCreateDelegated();
    Assertions.assertTrue(captureAppender.events().isEmpty());
  }

  @Test
  public void testDisabledMatchingPolicyDelegates() throws Exception {
    when(policyDispatcher.listPolicyInfos(METALAKE))
        .thenReturn(
            new PolicyEntity[] {
              encryptionPolicy(
                  "disabled-policy",
                  false,
                  true,
                  List.of("key-a"),
                  IcebergEncryptionContent.Enforcement.DENY_CREATE)
            });

    Assertions.assertSame(createdTable, createTable(Collections.emptyMap()));

    verifyCreateDelegated();
    Assertions.assertTrue(captureAppender.events().isEmpty());
  }

  @Test
  public void testExactAllowedKeyDelegates() throws Exception {
    when(policyDispatcher.listPolicyInfos(METALAKE))
        .thenReturn(
            new PolicyEntity[] {
              encryptionPolicy(
                  "encryption-policy",
                  true,
                  true,
                  List.of("kms://demo/Key-A"),
                  IcebergEncryptionContent.Enforcement.DENY_CREATE)
            });

    Assertions.assertSame(createdTable, createTable(Map.of(ENCRYPTION_KEY_ID, "kms://demo/Key-A")));

    verifyCreateDelegated();
    Assertions.assertTrue(captureAppender.events().isEmpty());
  }

  @Test
  public void testOptionalMissingKeyDelegates() throws Exception {
    when(policyDispatcher.listPolicyInfos(METALAKE))
        .thenReturn(
            new PolicyEntity[] {
              encryptionPolicy(
                  "optional-policy",
                  true,
                  false,
                  Collections.emptyList(),
                  IcebergEncryptionContent.Enforcement.DENY_CREATE)
            });

    Assertions.assertSame(createdTable, createTable(Collections.emptyMap()));

    verifyCreateDelegated();
    Assertions.assertTrue(captureAppender.events().isEmpty());
  }

  @Test
  public void testReportMissingKeyLogsAndDelegates() throws Exception {
    when(policyDispatcher.listPolicyInfos(METALAKE))
        .thenReturn(
            new PolicyEntity[] {
              encryptionPolicy(
                  "report-policy",
                  true,
                  true,
                  List.of("key-a"),
                  IcebergEncryptionContent.Enforcement.REPORT)
            });

    Assertions.assertSame(createdTable, createTable(Collections.emptyMap()));

    verifyCreateDelegated();
    Assertions.assertEquals(1, captureAppender.events().size());
    String message = captureAppender.events().get(0).getMessage().getFormattedMessage();
    Assertions.assertAll(
        () -> Assertions.assertTrue(message.contains("decisionId=" + DECISION_ID)),
        () -> Assertions.assertTrue(message.contains("actor=" + ACTOR)),
        () -> Assertions.assertTrue(message.contains("normalizedTable=" + TABLE_IDENT)),
        () -> Assertions.assertTrue(message.contains("policy=report-policy")),
        () -> Assertions.assertTrue(message.contains("suppliedKeyId=<missing>")),
        () -> Assertions.assertTrue(message.contains("reason=KEY_REQUIRED")),
        () -> Assertions.assertTrue(message.contains("enforcement=report")),
        () -> Assertions.assertTrue(message.contains("outcome=ALLOWED")));
  }

  @Test
  public void testDenyMissingKeyRejectsBeforeDelegation() {
    when(policyDispatcher.listPolicyInfos(METALAKE))
        .thenReturn(
            new PolicyEntity[] {
              encryptionPolicy(
                  "deny-policy",
                  true,
                  true,
                  List.of("key-a"),
                  IcebergEncryptionContent.Enforcement.DENY_CREATE)
            });

    EncryptionPolicyViolationException exception =
        Assertions.assertThrows(
            EncryptionPolicyViolationException.class, () -> createTable(Collections.emptyMap()));

    Assertions.assertTrue(exception.getMessage().contains("decisionId=" + DECISION_ID));
    Assertions.assertTrue(exception.getMessage().contains("reason=KEY_REQUIRED"));
    verifyCreateNotDelegated();
    Assertions.assertEquals(1, captureAppender.events().size());
    String message = captureAppender.events().get(0).getMessage().getFormattedMessage();
    Assertions.assertTrue(message.contains("enforcement=deny-create"));
    Assertions.assertTrue(message.contains("outcome=DENIED"));
  }

  @Test
  public void testAllowedKeyIdsAreCaseSensitive() {
    when(policyDispatcher.listPolicyInfos(METALAKE))
        .thenReturn(
            new PolicyEntity[] {
              encryptionPolicy(
                  "deny-policy",
                  true,
                  true,
                  List.of("key-A"),
                  IcebergEncryptionContent.Enforcement.DENY_CREATE)
            });

    EncryptionPolicyViolationException exception =
        Assertions.assertThrows(
            EncryptionPolicyViolationException.class,
            () -> createTable(Map.of(ENCRYPTION_KEY_ID, "key-a")));

    Assertions.assertTrue(exception.getMessage().contains("reason=KEY_NOT_ALLOWED"));
    verifyCreateNotDelegated();
    String message = captureAppender.events().get(0).getMessage().getFormattedMessage();
    Assertions.assertTrue(message.contains("suppliedKeyId=key-a"));
    Assertions.assertTrue(message.contains("reason=KEY_NOT_ALLOWED"));
  }

  @Test
  public void testMultipleMatchingPoliciesRejectDeterministicallyBeforeDelegation() {
    when(policyDispatcher.listPolicyInfos(METALAKE))
        .thenReturn(
            new PolicyEntity[] {
              encryptionPolicy(
                  "z-policy",
                  true,
                  true,
                  List.of("key-a"),
                  IcebergEncryptionContent.Enforcement.REPORT),
              encryptionPolicy(
                  "a-policy",
                  true,
                  true,
                  List.of("key-a"),
                  IcebergEncryptionContent.Enforcement.DENY_CREATE)
            });

    EncryptionPolicyViolationException exception =
        Assertions.assertThrows(
            EncryptionPolicyViolationException.class,
            () -> createTable(Map.of(ENCRYPTION_KEY_ID, "key-a")));

    Assertions.assertTrue(exception.getMessage().contains("reason=AMBIGUOUS_POLICY"));
    Assertions.assertTrue(exception.getMessage().contains("policy=a-policy,z-policy"));
    verifyCreateNotDelegated();
    String message = captureAppender.events().get(0).getMessage().getFormattedMessage();
    Assertions.assertTrue(message.contains("policy=a-policy,z-policy"));
    Assertions.assertTrue(message.contains("enforcement=<ambiguous>"));
    Assertions.assertTrue(message.contains("outcome=DENIED"));
  }

  @Test
  public void testEncryptedTableKeyIdCannotBeChangedOrRemoved() throws Exception {
    when(createdTable.properties()).thenReturn(Map.of(ENCRYPTION_KEY_ID, "key-a"));
    when(downstream.loadTable(TABLE_IDENT)).thenReturn(createdTable);

    for (TableChange change :
        List.of(
            TableChange.setProperty(ENCRYPTION_KEY_ID, "key-b"),
            TableChange.removeProperty(ENCRYPTION_KEY_ID))) {
      EncryptionKeyIdImmutableException exception =
          Assertions.assertThrows(
              EncryptionKeyIdImmutableException.class,
              () ->
                  PrincipalUtils.doAs(
                      new UserPrincipal(ACTOR), () -> dispatcher.alterTable(TABLE_IDENT, change)));

      Assertions.assertTrue(exception.getMessage().contains("decisionId=" + DECISION_ID));
    }

    verify(downstream, never()).alterTable(eq(TABLE_IDENT), any(TableChange[].class));
    Assertions.assertEquals(2, captureAppender.events().size());
    for (LogEvent event : captureAppender.events()) {
      String message = event.getMessage().getFormattedMessage();
      Assertions.assertAll(
          () -> Assertions.assertTrue(message.contains("decisionId=" + DECISION_ID)),
          () -> Assertions.assertTrue(message.contains("actor=" + ACTOR)),
          () -> Assertions.assertTrue(message.contains("reason=KEY_ID_IMMUTABLE")),
          () -> Assertions.assertTrue(message.contains("outcome=DENIED")));
    }
  }

  @Test
  public void testOtherEncryptionPropertiesRemainPassThrough() throws Exception {
    TableChange change = TableChange.setProperty("encryption.future-property", "value");
    when(createdTable.properties()).thenReturn(Map.of(ENCRYPTION_KEY_ID, "key-a"));
    when(downstream.loadTable(TABLE_IDENT)).thenReturn(createdTable);
    when(downstream.alterTable(TABLE_IDENT, change)).thenReturn(createdTable);

    Assertions.assertSame(createdTable, dispatcher.alterTable(TABLE_IDENT, change));

    verify(downstream).alterTable(TABLE_IDENT, change);
    Assertions.assertTrue(captureAppender.events().isEmpty());
  }

  @Test
  public void testUnencryptedTableCannotAddKeyIdAfterCreate() {
    TableChange change = TableChange.setProperty(ENCRYPTION_KEY_ID, "key-a");
    when(createdTable.properties()).thenReturn(Collections.emptyMap());
    when(downstream.loadTable(TABLE_IDENT)).thenReturn(createdTable);

    EncryptionKeyIdImmutableException exception =
        Assertions.assertThrows(
            EncryptionKeyIdImmutableException.class,
            () -> dispatcher.alterTable(TABLE_IDENT, change));

    Assertions.assertTrue(exception.getMessage().contains("decisionId=" + DECISION_ID));
    verify(downstream, never()).alterTable(eq(TABLE_IDENT), any(TableChange[].class));
    Assertions.assertTrue(
        captureAppender
            .events()
            .get(0)
            .getMessage()
            .getFormattedMessage()
            .contains("suppliedKeyId=<missing>"));
  }

  private Table createTable(Map<String, String> properties) throws Exception {
    return PrincipalUtils.doAs(
        new UserPrincipal(ACTOR),
        () ->
            dispatcher.createTable(
                TABLE_IDENT,
                new Column[0],
                "comment",
                properties,
                Transforms.EMPTY_TRANSFORM,
                Distributions.NONE,
                SortOrders.NONE,
                Indexes.EMPTY_INDEXES));
  }

  private PolicyEntity encryptionPolicy(
      String name,
      boolean enabled,
      boolean required,
      List<String> allowedKeyIds,
      IcebergEncryptionContent.Enforcement enforcement) {
    return PolicyEntity.builder()
        .withId(nextPolicyId++)
        .withName(name)
        .withNamespace(NamespaceUtil.ofPolicy(METALAKE))
        .withPolicyType(Policy.BuiltInType.ICEBERG_ENCRYPTION)
        .withEnabled(enabled)
        .withContent(
            PolicyContents.icebergEncryption(
                IcebergEncryptionContent.CURRENT_SCHEMA_VERSION,
                TAG,
                required,
                allowedKeyIds,
                enforcement))
        .withAuditInfo(
            AuditInfo.builder().withCreator("test").withCreateTime(Instant.EPOCH).build())
        .build();
  }

  private void verifyCreateDelegated() {
    verify(downstream)
        .createTable(
            eq(TABLE_IDENT),
            any(Column[].class),
            eq("comment"),
            anyMap(),
            any(Transform[].class),
            eq(Distributions.NONE),
            any(SortOrder[].class),
            any(Index[].class));
  }

  private void verifyCreateNotDelegated() {
    verify(downstream, never())
        .createTable(
            any(NameIdentifier.class),
            any(Column[].class),
            any(String.class),
            anyMap(),
            any(Transform[].class),
            any(Distribution.class),
            any(SortOrder[].class),
            any(Index[].class));
  }

  private static class CaptureAppender extends AbstractAppender {
    private final List<LogEvent> events = new ArrayList<>();

    private CaptureAppender(String name) {
      super(name, null, PatternLayout.createDefaultLayout(), true, null);
    }

    @Override
    public void append(LogEvent event) {
      events.add(event.toImmutable());
    }

    private List<LogEvent> events() {
      return events;
    }
  }
}
