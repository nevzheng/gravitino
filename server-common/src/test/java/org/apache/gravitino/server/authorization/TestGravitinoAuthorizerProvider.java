/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.gravitino.server.authorization;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import org.apache.gravitino.authorization.GravitinoAuthorizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

/** Test of {@link GravitinoAuthorizerProvider} */
public class TestGravitinoAuthorizerProvider {

  @AfterEach
  public void tearDown() {
    GravitinoAuthorizerProvider.getInstance().close();
  }

  @Test
  public void testInstallHandsOutTheAuthorizer() {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    GravitinoAuthorizerProvider provider = GravitinoAuthorizerProvider.getInstance();
    Assertions.assertNull(provider.getGravitinoAuthorizer());

    provider.install(authorizer);
    // Installing the same authorizer again changes nothing.
    provider.install(authorizer);

    Assertions.assertSame(authorizer, provider.getGravitinoAuthorizer());
  }

  @Test
  public void testInstallRejectsASecondAuthorizer() {
    GravitinoAuthorizerProvider provider = GravitinoAuthorizerProvider.getInstance();
    provider.install(mock(GravitinoAuthorizer.class));

    Assertions.assertThrows(
        IllegalStateException.class, () -> provider.install(mock(GravitinoAuthorizer.class)));
    Assertions.assertThrows(NullPointerException.class, () -> provider.install(null));
  }

  @Test
  public void testCloseTakesTheAuthorizerBackWithoutClosingIt() {
    GravitinoAuthorizer authorizer = mock(GravitinoAuthorizer.class);
    GravitinoAuthorizerProvider provider = GravitinoAuthorizerProvider.getInstance();
    provider.install(authorizer);

    provider.close();

    Assertions.assertNull(provider.getGravitinoAuthorizer());
    verifyNoInteractions(authorizer);
    // Another authorizer can be installed once the first is taken back.
    GravitinoAuthorizer next = mock(GravitinoAuthorizer.class);
    provider.install(next);
    Assertions.assertSame(next, provider.getGravitinoAuthorizer());
  }
}
