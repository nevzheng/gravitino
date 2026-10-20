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
package org.apache.gravitino.utils;

import com.google.common.base.Preconditions;
import java.util.function.Supplier;
import org.apache.gravitino.GravitinoEnv;
import org.apache.gravitino.annotation.LegacyEnvAccess;
import org.apache.gravitino.authorization.AccessControlDispatcher;
import org.apache.gravitino.catalog.CatalogDispatcher;
import org.apache.gravitino.catalog.FilesetDispatcher;
import org.apache.gravitino.catalog.FunctionDispatcher;
import org.apache.gravitino.catalog.ModelDispatcher;
import org.apache.gravitino.catalog.SchemaDispatcher;
import org.apache.gravitino.catalog.SemanticModelDispatcher;
import org.apache.gravitino.catalog.TableDispatcher;
import org.apache.gravitino.catalog.TopicDispatcher;
import org.apache.gravitino.catalog.ViewDispatcher;
import org.apache.gravitino.job.JobOperationDispatcher;
import org.apache.gravitino.metalake.MetalakeDispatcher;
import org.apache.gravitino.policy.PolicyDispatcher;
import org.apache.gravitino.tag.TagDispatcher;

/**
 * {@link MetadataObjectDispatchers} that read each dispatcher from {@link GravitinoEnv} when they
 * are asked for it.
 *
 * <p>It keeps the entry points that predate {@link MetadataObjectDispatchers} working: the
 * deprecated two-argument {@code MetadataObjectUtil.checkMetadataObject} and the services built
 * with their deprecated constructors. It is deleted with {@code GravitinoEnv}.
 */
public final class EnvironmentMetadataObjectDispatchers implements MetadataObjectDispatchers {

  /** The dispatchers of the process environment. */
  public static final EnvironmentMetadataObjectDispatchers INSTANCE =
      new EnvironmentMetadataObjectDispatchers(EnvironmentMetadataObjectDispatchers::processEnv);

  private final Supplier<GravitinoEnv> env;

  private EnvironmentMetadataObjectDispatchers(Supplier<GravitinoEnv> env) {
    this.env = env;
  }

  /**
   * Returns the dispatchers of the given environment.
   *
   * @param env the environment
   * @return dispatchers that read the environment's getters when asked
   */
  public static MetadataObjectDispatchers of(GravitinoEnv env) {
    Preconditions.checkNotNull(env, "env");
    return new EnvironmentMetadataObjectDispatchers(() -> env);
  }

  @Override
  public MetalakeDispatcher internalMetalakeDispatcher() {
    return env.get().internalMetalakeDispatcher();
  }

  @Override
  public CatalogDispatcher internalCatalogDispatcher() {
    return env.get().internalCatalogDispatcher();
  }

  @Override
  public SchemaDispatcher internalSchemaDispatcher() {
    return env.get().internalSchemaDispatcher();
  }

  @Override
  public TableDispatcher internalTableDispatcher() {
    return env.get().internalTableDispatcher();
  }

  @Override
  public ViewDispatcher internalViewDispatcher() {
    return env.get().internalViewDispatcher();
  }

  @Override
  public FilesetDispatcher internalFilesetDispatcher() {
    return env.get().internalFilesetDispatcher();
  }

  @Override
  public TopicDispatcher internalTopicDispatcher() {
    return env.get().internalTopicDispatcher();
  }

  @Override
  public ModelDispatcher internalModelDispatcher() {
    return env.get().internalModelDispatcher();
  }

  @Override
  public FunctionDispatcher internalFunctionDispatcher() {
    return env.get().internalFunctionDispatcher();
  }

  @Override
  public SemanticModelDispatcher internalSemanticModelDispatcher() {
    return env.get().internalSemanticModelDispatcher();
  }

  @Override
  public TagDispatcher internalTagDispatcher() {
    return env.get().internalTagDispatcher();
  }

  @Override
  public PolicyDispatcher internalPolicyDispatcher() {
    return env.get().internalPolicyDispatcher();
  }

  @Override
  public JobOperationDispatcher internalJobOperationDispatcher() {
    return env.get().internalJobOperationDispatcher();
  }

  @Override
  public AccessControlDispatcher internalAccessControlDispatcher() {
    return env.get().internalAccessControlDispatcher();
  }

  @LegacyEnvAccess
  private static GravitinoEnv processEnv() {
    return GravitinoEnv.getInstance();
  }
}
