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
package org.apache.gravitino.server;

import org.glassfish.hk2.api.ActiveDescriptor;
import org.glassfish.hk2.utilities.AbstractActiveDescriptor;
import org.glassfish.hk2.utilities.BuilderHelper;

/**
 * Builds an HK2 binding that serves an object under one contract only.
 *
 * <p>{@code bind(service).to(contract)} also advertises the implementation class of the object, so
 * HK2 would serve a {@code CatalogManager} to a resource that injects one even though only {@code
 * CatalogAccess} was bound. A descriptor built here advertises exactly the given contract.
 */
final class ContractBinding {

  private ContractBinding() {}

  /**
   * Returns a rank 1 constant descriptor that advertises only {@code contract}.
   *
   * @param service the object to serve
   * @param contract the only type HK2 serves it as
   * @param <T> the type of the object
   * @return the descriptor, to pass to {@code addActiveDescriptor}
   */
  static <T> ActiveDescriptor<T> only(T service, Class<? super T> contract) {
    AbstractActiveDescriptor<T> descriptor =
        BuilderHelper.createConstantDescriptor(service, null, contract);
    descriptor.setRanking(1);
    return descriptor;
  }
}
