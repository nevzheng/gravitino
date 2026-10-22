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

package org.apache.gravitino.catalog;

import com.google.common.base.Preconditions;
import java.util.Map;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.di.DispatcherDecorator;
import org.apache.gravitino.messaging.DataLayout;
import org.apache.gravitino.messaging.Topic;
import org.apache.gravitino.messaging.TopicChange;

/**
 * A {@link TopicDispatcher} that forwards every call to another one. Extend it to write a {@link
 * DispatcherDecorator decorator} that overrides only the calls it changes.
 *
 * <p>It overrides every method of {@link TopicDispatcher}, default methods included, so a subclass
 * never falls back to an interface default where the dispatcher it wraps does something else.
 */
public abstract class ForwardingTopicDispatcher implements TopicDispatcher {

  private final TopicDispatcher delegate;

  /**
   * Creates a dispatcher that forwards every call to {@code delegate}.
   *
   * @param delegate the dispatcher to forward to
   */
  protected ForwardingTopicDispatcher(TopicDispatcher delegate) {
    this.delegate = Preconditions.checkNotNull(delegate, "delegate");
  }

  /**
   * Returns the dispatcher this one forwards to.
   *
   * @return the delegate
   */
  protected TopicDispatcher delegate() {
    return delegate;
  }

  @Override
  public NameIdentifier[] listTopics(Namespace namespace) {
    return delegate.listTopics(namespace);
  }

  @Override
  public Topic loadTopic(NameIdentifier ident) {
    return delegate.loadTopic(ident);
  }

  @Override
  public boolean topicExists(NameIdentifier ident) {
    return delegate.topicExists(ident);
  }

  @Override
  public Topic createTopic(
      NameIdentifier ident, String comment, DataLayout dataLayout, Map<String, String> properties) {
    return delegate.createTopic(ident, comment, dataLayout, properties);
  }

  @Override
  public Topic alterTopic(NameIdentifier ident, TopicChange... changes) {
    return delegate.alterTopic(ident, changes);
  }

  @Override
  public boolean dropTopic(NameIdentifier ident) {
    return delegate.dropTopic(ident);
  }
}
