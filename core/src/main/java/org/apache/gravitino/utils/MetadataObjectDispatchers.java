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

import javax.annotation.Nullable;
import org.apache.gravitino.annotation.Evolving;
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
 * The internal dispatchers that {@code MetadataObjectUtil.checkMetadataObject} asks whether a
 * metadata object exists.
 *
 * <p>Each dispatcher is resolved when it is asked for, so services can hold these dispatchers
 * before the chains they reach are built. The interface is plain Java and carries no
 * dependency-injection types.
 */
@Evolving
public interface MetadataObjectDispatchers {

  /**
   * Returns the internal metalake dispatcher.
   *
   * @return the dispatcher
   */
  MetalakeDispatcher internalMetalakeDispatcher();

  /**
   * Returns the internal catalog dispatcher.
   *
   * @return the dispatcher
   */
  CatalogDispatcher internalCatalogDispatcher();

  /**
   * Returns the internal schema dispatcher.
   *
   * @return the dispatcher
   */
  SchemaDispatcher internalSchemaDispatcher();

  /**
   * Returns the internal table dispatcher.
   *
   * @return the dispatcher
   */
  TableDispatcher internalTableDispatcher();

  /**
   * Returns the internal view dispatcher.
   *
   * @return the dispatcher
   */
  ViewDispatcher internalViewDispatcher();

  /**
   * Returns the internal fileset dispatcher.
   *
   * @return the dispatcher
   */
  FilesetDispatcher internalFilesetDispatcher();

  /**
   * Returns the internal topic dispatcher.
   *
   * @return the dispatcher
   */
  TopicDispatcher internalTopicDispatcher();

  /**
   * Returns the internal model dispatcher.
   *
   * @return the dispatcher
   */
  ModelDispatcher internalModelDispatcher();

  /**
   * Returns the internal function dispatcher.
   *
   * @return the dispatcher
   */
  FunctionDispatcher internalFunctionDispatcher();

  /**
   * Returns the internal semantic model dispatcher.
   *
   * @return the dispatcher
   */
  SemanticModelDispatcher internalSemanticModelDispatcher();

  /**
   * Returns the internal tag dispatcher.
   *
   * @return the dispatcher
   */
  TagDispatcher internalTagDispatcher();

  /**
   * Returns the internal policy dispatcher.
   *
   * @return the dispatcher
   */
  PolicyDispatcher internalPolicyDispatcher();

  /**
   * Returns the internal job dispatcher.
   *
   * @return the dispatcher
   */
  JobOperationDispatcher internalJobOperationDispatcher();

  /**
   * Returns the internal access control dispatcher, which looks up roles.
   *
   * @return the dispatcher, or null while authorization is disabled
   */
  @Nullable
  AccessControlDispatcher internalAccessControlDispatcher();
}
