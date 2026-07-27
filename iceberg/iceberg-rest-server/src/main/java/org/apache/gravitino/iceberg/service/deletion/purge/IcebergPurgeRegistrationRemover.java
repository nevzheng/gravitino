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

import org.apache.gravitino.iceberg.service.deletion.po.IcebergDeletionContextPO;

/** Removes only the exact saved Iceberg catalog registration before physical cleanup. */
@FunctionalInterface
public interface IcebergPurgeRegistrationRemover {

  /**
   * Removes the saved table registration, or verifies that it is already absent.
   *
   * @param context immutable saved UUID, metadata location, and identifier
   * @throws IcebergPurgeException when the registration is a different generation or removal fails
   */
  void remove(IcebergDeletionContextPO context) throws IcebergPurgeException;
}
