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

import java.util.List;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.MetadataObjects;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

public class TestNoopOwnerDispatcher {

  @Test
  void testSettingAnOwnerLeavesNoOwner() {
    MetadataObject catalog = MetadataObjects.of(null, "catalog", MetadataObject.Type.CATALOG);
    OwnerDispatcher owners = NoopOwnerDispatcher.INSTANCE;

    owners.setOwner("metalake", catalog, "user", Owner.Type.USER);
    owners.setOwners("metalake", List.of(catalog), "group", Owner.Type.GROUP);

    Assertions.assertTrue(owners.getOwner("metalake", catalog).isEmpty());
  }
}
