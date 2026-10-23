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
import org.apache.gravitino.model.Model;
import org.apache.gravitino.model.ModelChange;
import org.apache.gravitino.model.ModelVersion;
import org.apache.gravitino.model.ModelVersionChange;

/**
 * A {@link ModelDispatcher} that forwards every call to another one. Extend it to write a {@link
 * DispatcherDecorator decorator} that overrides only the calls it changes.
 *
 * <p>It overrides every method of {@link ModelDispatcher}, default methods included, so a subclass
 * never falls back to an interface default where the dispatcher it wraps does something else.
 */
public abstract class ForwardingModelDispatcher implements ModelDispatcher {

  private final ModelDispatcher delegate;

  /**
   * Creates a dispatcher that forwards every call to {@code delegate}.
   *
   * @param delegate the dispatcher to forward to
   */
  protected ForwardingModelDispatcher(ModelDispatcher delegate) {
    this.delegate = Preconditions.checkNotNull(delegate, "delegate");
  }

  /**
   * Returns the dispatcher this one forwards to.
   *
   * @return the delegate
   */
  protected ModelDispatcher delegate() {
    return delegate;
  }

  @Override
  public NameIdentifier[] listModels(Namespace namespace) {
    return delegate.listModels(namespace);
  }

  @Override
  public Model getModel(NameIdentifier ident) {
    return delegate.getModel(ident);
  }

  @Override
  public boolean modelExists(NameIdentifier ident) {
    return delegate.modelExists(ident);
  }

  @Override
  public Model registerModel(NameIdentifier ident, String comment, Map<String, String> properties) {
    return delegate.registerModel(ident, comment, properties);
  }

  @Override
  public Model registerModel(
      NameIdentifier ident,
      Map<String, String> uris,
      String[] aliases,
      String comment,
      Map<String, String> properties) {
    return delegate.registerModel(ident, uris, aliases, comment, properties);
  }

  @Override
  public boolean deleteModel(NameIdentifier ident) {
    return delegate.deleteModel(ident);
  }

  @Override
  public int[] listModelVersions(NameIdentifier ident) {
    return delegate.listModelVersions(ident);
  }

  @Override
  public ModelVersion[] listModelVersionInfos(NameIdentifier ident) {
    return delegate.listModelVersionInfos(ident);
  }

  @Override
  public ModelVersion getModelVersion(NameIdentifier ident, int version) {
    return delegate.getModelVersion(ident, version);
  }

  @Override
  public ModelVersion getModelVersion(NameIdentifier ident, String alias) {
    return delegate.getModelVersion(ident, alias);
  }

  @Override
  public boolean modelVersionExists(NameIdentifier ident, int version) {
    return delegate.modelVersionExists(ident, version);
  }

  @Override
  public boolean modelVersionExists(NameIdentifier ident, String alias) {
    return delegate.modelVersionExists(ident, alias);
  }

  @Override
  public void linkModelVersion(
      NameIdentifier ident,
      String uri,
      String[] aliases,
      String comment,
      Map<String, String> properties) {
    delegate.linkModelVersion(ident, uri, aliases, comment, properties);
  }

  @Override
  public void linkModelVersion(
      NameIdentifier ident,
      Map<String, String> uris,
      String[] aliases,
      String comment,
      Map<String, String> properties) {
    delegate.linkModelVersion(ident, uris, aliases, comment, properties);
  }

  @Override
  public String getModelVersionUri(NameIdentifier ident, int version, String uriName) {
    return delegate.getModelVersionUri(ident, version, uriName);
  }

  @Override
  public String getModelVersionUri(NameIdentifier ident, String alias, String uriName) {
    return delegate.getModelVersionUri(ident, alias, uriName);
  }

  @Override
  public boolean deleteModelVersion(NameIdentifier ident, int version) {
    return delegate.deleteModelVersion(ident, version);
  }

  @Override
  public boolean deleteModelVersion(NameIdentifier ident, String alias) {
    return delegate.deleteModelVersion(ident, alias);
  }

  @Override
  public Model alterModel(NameIdentifier ident, ModelChange... changes) {
    return delegate.alterModel(ident, changes);
  }

  @Override
  public ModelVersion alterModelVersion(
      NameIdentifier ident, int version, ModelVersionChange... changes) {
    return delegate.alterModelVersion(ident, version, changes);
  }

  @Override
  public ModelVersion alterModelVersion(
      NameIdentifier ident, String alias, ModelVersionChange... changes) {
    return delegate.alterModelVersion(ident, alias, changes);
  }
}
