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
package org.apache.gravitino.metadata;

import java.util.Objects;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.authorization.AuthorizationUtils;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.exceptions.IllegalMetadataObjectException;
import org.apache.gravitino.exceptions.NoSuchJobException;
import org.apache.gravitino.exceptions.NoSuchJobTemplateException;
import org.apache.gravitino.exceptions.NoSuchMetadataObjectException;
import org.apache.gravitino.exceptions.NoSuchPolicyException;
import org.apache.gravitino.exceptions.NoSuchRoleException;
import org.apache.gravitino.exceptions.NoSuchTagException;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.tag.TagDispatcher;
import org.apache.gravitino.utils.MetadataObjectUtil;
import org.apache.gravitino.utils.NameIdentifierUtil;

/**
 * Checks metadata object existence by routing each object type to its owning dispatcher.
 *
 * <p>Tag, policy, and job dispatchers are supplied lazily because their graphs can depend on this
 * checker and the job service is initialized later than tag and policy services. The suppliers are
 * typed graph edges, not a general-purpose service locator.
 */
public final class DispatcherMetadataObjectExistenceChecker
    implements MetadataObjectExistenceChecker {

  private final MetalakeDispatcher metalakeDispatcher;
  private final CatalogDispatcher catalogDispatcher;
  private final SchemaDispatcher schemaDispatcher;
  private final TableDispatcher tableDispatcher;
  private final FilesetDispatcher filesetDispatcher;
  private final TopicDispatcher topicDispatcher;
  private final ModelDispatcher modelDispatcher;
  private final FunctionDispatcher functionDispatcher;
  private final ViewDispatcher viewDispatcher;
  @Nullable private final AccessControlDispatcher accessControlDispatcher;
  private final Supplier<TagDispatcher> tagDispatcher;
  private final Supplier<PolicyDispatcher> policyDispatcher;
  private final Supplier<JobOperationDispatcher> jobOperationDispatcher;

  /**
   * Creates an existence checker with explicit dispatcher dependencies.
   *
   * @param metalakeDispatcher dispatcher for metalakes
   * @param catalogDispatcher dispatcher for catalogs
   * @param schemaDispatcher dispatcher for schemas
   * @param tableDispatcher dispatcher for tables and columns
   * @param filesetDispatcher dispatcher for filesets
   * @param topicDispatcher dispatcher for topics
   * @param modelDispatcher dispatcher for models
   * @param functionDispatcher dispatcher for functions
   * @param viewDispatcher dispatcher for views
   * @param accessControlDispatcher dispatcher for roles, or null when authorization is disabled
   * @param tagDispatcher lazy dispatcher for tags
   * @param policyDispatcher lazy dispatcher for policies
   * @param jobOperationDispatcher lazy dispatcher for jobs and job templates
   */
  public DispatcherMetadataObjectExistenceChecker(
      MetalakeDispatcher metalakeDispatcher,
      CatalogDispatcher catalogDispatcher,
      SchemaDispatcher schemaDispatcher,
      TableDispatcher tableDispatcher,
      FilesetDispatcher filesetDispatcher,
      TopicDispatcher topicDispatcher,
      ModelDispatcher modelDispatcher,
      FunctionDispatcher functionDispatcher,
      ViewDispatcher viewDispatcher,
      @Nullable AccessControlDispatcher accessControlDispatcher,
      Supplier<TagDispatcher> tagDispatcher,
      Supplier<PolicyDispatcher> policyDispatcher,
      Supplier<JobOperationDispatcher> jobOperationDispatcher) {
    this.metalakeDispatcher = Objects.requireNonNull(metalakeDispatcher, "metalakeDispatcher");
    this.catalogDispatcher = Objects.requireNonNull(catalogDispatcher, "catalogDispatcher");
    this.schemaDispatcher = Objects.requireNonNull(schemaDispatcher, "schemaDispatcher");
    this.tableDispatcher = Objects.requireNonNull(tableDispatcher, "tableDispatcher");
    this.filesetDispatcher = Objects.requireNonNull(filesetDispatcher, "filesetDispatcher");
    this.topicDispatcher = Objects.requireNonNull(topicDispatcher, "topicDispatcher");
    this.modelDispatcher = Objects.requireNonNull(modelDispatcher, "modelDispatcher");
    this.functionDispatcher = Objects.requireNonNull(functionDispatcher, "functionDispatcher");
    this.viewDispatcher = Objects.requireNonNull(viewDispatcher, "viewDispatcher");
    this.accessControlDispatcher = accessControlDispatcher;
    this.tagDispatcher = Objects.requireNonNull(tagDispatcher, "tagDispatcher");
    this.policyDispatcher = Objects.requireNonNull(policyDispatcher, "policyDispatcher");
    this.jobOperationDispatcher =
        Objects.requireNonNull(jobOperationDispatcher, "jobOperationDispatcher");
  }

  @Override
  public void check(String metalake, MetadataObject metadataObject) {
    NameIdentifier identifier = MetadataObjectUtil.toEntityIdent(metalake, metadataObject);
    Supplier<NoSuchMetadataObjectException> notFound =
        () ->
            new NoSuchMetadataObjectException(
                "Metadata object %s type %s doesn't exist",
                metadataObject.fullName(), metadataObject.type());

    switch (metadataObject.type()) {
      case METALAKE:
        if (!metalake.equals(metadataObject.name())) {
          throw new IllegalMetadataObjectException("The metalake object name must be %s", metalake);
        }
        NameIdentifierUtil.checkMetalake(identifier);
        check(metalakeDispatcher.metalakeExists(identifier), notFound);
        break;
      case CATALOG:
        NameIdentifierUtil.checkCatalog(identifier);
        check(catalogDispatcher.catalogExists(identifier), notFound);
        break;
      case SCHEMA:
        NameIdentifierUtil.checkSchema(identifier);
        check(schemaDispatcher.schemaExists(identifier), notFound);
        break;
      case FILESET:
        NameIdentifierUtil.checkFileset(identifier);
        check(filesetDispatcher.filesetExists(identifier), notFound);
        break;
      case TABLE:
        NameIdentifierUtil.checkTable(identifier);
        check(tableDispatcher.tableExists(identifier), notFound);
        break;
      case COLUMN:
        NameIdentifierUtil.checkColumn(identifier);
        NameIdentifier tableIdentifier = NameIdentifier.of(identifier.namespace().levels());
        check(tableDispatcher.tableExists(tableIdentifier), notFound);
        break;
      case TOPIC:
        NameIdentifierUtil.checkTopic(identifier);
        check(topicDispatcher.topicExists(identifier), notFound);
        break;
      case MODEL:
        NameIdentifierUtil.checkModel(identifier);
        check(modelDispatcher.modelExists(identifier), notFound);
        break;
      case FUNCTION:
        NameIdentifierUtil.checkFunction(identifier);
        check(functionDispatcher.functionExists(identifier), notFound);
        break;
      case VIEW:
        NameIdentifierUtil.checkView(identifier);
        check(viewDispatcher.viewExists(identifier), notFound);
        break;
      case ROLE:
        AuthorizationUtils.checkRole(identifier);
        try {
          Objects.requireNonNull(accessControlDispatcher, "accessControlDispatcher")
              .getRole(metalake, metadataObject.fullName());
        } catch (NoSuchRoleException e) {
          throw notFound.get();
        }
        break;
      case TAG:
        NameIdentifierUtil.checkTag(identifier);
        try {
          tagDispatcher.get().getTag(metalake, metadataObject.fullName());
        } catch (NoSuchTagException e) {
          throw notFound.get();
        }
        break;
      case POLICY:
        NameIdentifierUtil.checkPolicy(identifier);
        try {
          policyDispatcher.get().getPolicy(metalake, metadataObject.fullName());
        } catch (NoSuchPolicyException e) {
          throw notFound.get();
        }
        break;
      case JOB:
        NameIdentifierUtil.checkJob(identifier);
        try {
          jobOperationDispatcher.get().getJob(metalake, metadataObject.fullName());
        } catch (NoSuchJobException e) {
          throw notFound.get();
        }
        break;
      case JOB_TEMPLATE:
        NameIdentifierUtil.checkJobTemplate(identifier);
        try {
          jobOperationDispatcher.get().getJobTemplate(metalake, metadataObject.fullName());
        } catch (NoSuchJobTemplateException e) {
          throw notFound.get();
        }
        break;
      default:
        throw new IllegalArgumentException(
            String.format("Doesn't support the type %s", metadataObject.type()));
    }
  }

  private static void check(
      boolean expression, Supplier<? extends RuntimeException> exceptionSupplier) {
    if (!expression) {
      throw exceptionSupplier.get();
    }
  }
}
