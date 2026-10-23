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

import org.apache.gravitino.di.Internal;
import org.glassfish.hk2.api.AnnotationLiteral;

/**
 * The {@link Internal} qualifier as an annotation instance, for binding the internal chain of a
 * dispatcher type into HK2 next to its public chain.
 *
 * <p>A REST resource injects it with the same annotation the object graph uses, for example
 * {@code @Internal OwnerDispatcher}.
 */
public final class InternalQualifier extends AnnotationLiteral<Internal> implements Internal {

  /** The only instance. */
  public static final Internal INSTANCE = new InternalQualifier();

  /** The rank of an internal chain: below the rank 1 of the public chain of the same type. */
  public static final int RANK = 0;

  private static final long serialVersionUID = 1L;

  private InternalQualifier() {}
}
