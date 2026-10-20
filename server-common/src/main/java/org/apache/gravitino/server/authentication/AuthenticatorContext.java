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
package org.apache.gravitino.server.authentication;

import com.google.common.base.Preconditions;
import java.util.function.Supplier;
import org.apache.gravitino.annotation.Evolving;
import org.apache.gravitino.storage.IdGenerator;

/**
 * The server services an {@link Authenticator} may use while it initializes.
 *
 * <p>The server builds authenticators reflectively from their no-argument constructors, so this
 * context is how it hands them what it has built, in place of the process-wide {@code
 * GravitinoEnv}. The interface is plain Java and carries no dependency-injection types.
 */
@Evolving
public interface AuthenticatorContext {

  /**
   * Returns the generator of ids for the entities an authenticator creates, such as built-in IdP
   * users and groups.
   *
   * @return the id generator, or null when the server built none.
   */
  IdGenerator idGenerator();

  /**
   * Creates a context that reads its services when an authenticator asks for them.
   *
   * @param idGenerator supplies the id generator.
   * @return the context.
   */
  static AuthenticatorContext of(Supplier<IdGenerator> idGenerator) {
    Preconditions.checkNotNull(idGenerator, "idGenerator");
    return idGenerator::get;
  }
}
