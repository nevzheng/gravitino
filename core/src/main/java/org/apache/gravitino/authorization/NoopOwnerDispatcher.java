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

package org.apache.gravitino.authorization;

import java.util.Optional;
import org.apache.gravitino.MetadataObject;

/**
 * The owner dispatcher in effect while authorization is disabled. Owners are not tracked then, so
 * setting one does nothing and no object has one.
 *
 * <p>It lets the component bind its owner chains when authorization is disabled without building
 * the owner manager, which needs a relational entity store.
 */
public final class NoopOwnerDispatcher implements OwnerDispatcher {

  /** The only instance. */
  public static final NoopOwnerDispatcher INSTANCE = new NoopOwnerDispatcher();

  private NoopOwnerDispatcher() {}

  @Override
  public void setOwner(
      String metalake, MetadataObject metadataObject, String ownerName, Owner.Type ownerType) {}

  @Override
  public Optional<Owner> getOwner(String metalake, MetadataObject metadataObject) {
    return Optional.empty();
  }
}
