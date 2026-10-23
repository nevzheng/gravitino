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
package org.apache.gravitino.di.downstream;

import java.util.List;
import org.apache.gravitino.Entity;
import org.apache.gravitino.HasIdentifier;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.RelationalEntity;
import org.apache.gravitino.SupportsRelationOperations;
import org.apache.gravitino.storage.memory.TestMemoryEntityStore.InMemoryEntityStore;

/**
 * An in-memory entity store that the owner and policy managers accept. The tests only build the
 * graph, so no relation operation is ever called.
 */
public class RelationalInMemoryEntityStore extends InMemoryEntityStore
    implements SupportsRelationOperations {

  @Override
  public List<RelationalEntity<?>> batchListEntitiesByRelation(
      Type relType, List<NameIdentifier> nameIdentifiers, Entity.EntityType identType) {
    throw new UnsupportedOperationException();
  }

  @Override
  public <E extends Entity & HasIdentifier> E getEntityByRelation(
      Type relType,
      NameIdentifier srcIdentifier,
      Entity.EntityType srcType,
      NameIdentifier destEntityIdent) {
    throw new UnsupportedOperationException();
  }

  @Override
  public void insertRelation(
      Type relType,
      NameIdentifier srcIdentifier,
      Entity.EntityType srcType,
      NameIdentifier dstIdentifier,
      Entity.EntityType dstType,
      boolean override) {
    throw new UnsupportedOperationException();
  }
}
