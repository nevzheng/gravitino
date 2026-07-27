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

package org.apache.gravitino.iceberg.service.deletion.purge;

import java.util.Objects;
import org.apache.gravitino.iceberg.common.ops.IcebergCatalogWrapper.RegistrationRemoval;
import org.apache.gravitino.iceberg.service.CatalogWrapperForREST;
import org.apache.gravitino.iceberg.service.deletion.IcebergDeletionNamespaceCodec;
import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;
import org.apache.iceberg.catalog.Namespace;
import org.apache.iceberg.catalog.TableIdentifier;

/** Fences registration cleanup by the deletion generation's saved UUID and metadata location. */
public class IcebergExactRegistrationRemover implements IcebergPurgeRegistrationRemover {

  private final IcebergPurgeResources resources;

  /**
   * Creates an exact registration remover.
   *
   * @param resources current catalog wrapper resolver
   */
  public IcebergExactRegistrationRemover(IcebergPurgeResources resources) {
    this.resources = Objects.requireNonNull(resources, "resources must not be null");
  }

  @Override
  public void remove(IcebergDeletionContextPO context) throws IcebergPurgeException {
    CatalogWrapperForREST wrapper = resources.wrapper(context);
    TableIdentifier identifier;
    try {
      identifier =
          TableIdentifier.of(
              Namespace.of(IcebergDeletionNamespaceCodec.decode(context.getIcebergNamespace())),
              context.getIcebergTableName());
    } catch (IllegalArgumentException e) {
      throw new IcebergPurgeException(
          false, "INVALID_NAMESPACE_SNAPSHOT", "Saved Iceberg namespace snapshot is invalid");
    }
    RegistrationRemoval result;
    try {
      result =
          wrapper.removeRegistrationIfMatches(
              identifier, context.getIcebergTableUuid(), context.getMetadataLocation());
    } catch (RuntimeException e) {
      throw new IcebergPurgeException(
          true, "REGISTRATION_REMOVE_FAILED", "Iceberg registration removal failed");
    }
    if (result == RegistrationRemoval.GENERATION_MISMATCH) {
      throw new IcebergPurgeException(
          false,
          "REGISTRATION_GENERATION_MISMATCH",
          "A different Iceberg generation occupies the saved identifier");
    }
  }
}
