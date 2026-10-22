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
import java.io.IOException;
import java.util.Map;
import org.apache.gravitino.NameIdentifier;
import org.apache.gravitino.Namespace;
import org.apache.gravitino.di.DispatcherDecorator;
import org.apache.gravitino.file.FileInfo;
import org.apache.gravitino.file.Fileset;
import org.apache.gravitino.file.FilesetChange;
import org.apache.gravitino.secret.SecretBinding;
import org.apache.gravitino.secret.SecretReference;

/**
 * A {@link FilesetDispatcher} that forwards every call to another one. Extend it to write a {@link
 * DispatcherDecorator decorator} that overrides only the calls it changes.
 *
 * <p>It overrides every method of {@link FilesetDispatcher}, default methods included, so a
 * subclass never falls back to an interface default where the dispatcher it wraps does something
 * else.
 */
public abstract class ForwardingFilesetDispatcher implements FilesetDispatcher {

  private final FilesetDispatcher delegate;

  /**
   * Creates a dispatcher that forwards every call to {@code delegate}.
   *
   * @param delegate the dispatcher to forward to
   */
  protected ForwardingFilesetDispatcher(FilesetDispatcher delegate) {
    this.delegate = Preconditions.checkNotNull(delegate, "delegate");
  }

  /**
   * Returns the dispatcher this one forwards to.
   *
   * @return the delegate
   */
  protected FilesetDispatcher delegate() {
    return delegate;
  }

  @Override
  public NameIdentifier[] listFilesets(Namespace namespace) {
    return delegate.listFilesets(namespace);
  }

  @Override
  public Fileset loadFileset(NameIdentifier ident) {
    return delegate.loadFileset(ident);
  }

  @Override
  public boolean filesetExists(NameIdentifier ident) {
    return delegate.filesetExists(ident);
  }

  @Override
  public Fileset createFileset(
      NameIdentifier ident,
      String comment,
      Fileset.Type type,
      String storageLocation,
      Map<String, String> properties) {
    return delegate.createFileset(ident, comment, type, storageLocation, properties);
  }

  @Override
  public Fileset createFileset(
      NameIdentifier ident,
      String comment,
      Fileset.Type type,
      String storageLocation,
      Map<String, String> properties,
      Map<String, SecretBinding> secretBindings,
      Map<String, SecretReference> secretReferences) {
    return delegate.createFileset(
        ident, comment, type, storageLocation, properties, secretBindings, secretReferences);
  }

  @Override
  public Fileset createMultipleLocationFileset(
      NameIdentifier ident,
      String comment,
      Fileset.Type type,
      Map<String, String> storageLocations,
      Map<String, String> properties,
      Map<String, SecretBinding> secretBindings,
      Map<String, SecretReference> secretReferences) {
    return delegate.createMultipleLocationFileset(
        ident, comment, type, storageLocations, properties, secretBindings, secretReferences);
  }

  @Override
  public Fileset createMultipleLocationFileset(
      NameIdentifier ident,
      String comment,
      Fileset.Type type,
      Map<String, String> storageLocations,
      Map<String, String> properties) {
    return delegate.createMultipleLocationFileset(
        ident, comment, type, storageLocations, properties);
  }

  @Override
  public Fileset alterFileset(NameIdentifier ident, FilesetChange... changes) {
    return delegate.alterFileset(ident, changes);
  }

  @Override
  public boolean dropFileset(NameIdentifier ident) {
    return delegate.dropFileset(ident);
  }

  @Override
  public String getFileLocation(NameIdentifier ident, String subPath) {
    return delegate.getFileLocation(ident, subPath);
  }

  @Override
  public String getFileLocation(NameIdentifier ident, String subPath, String locationName) {
    return delegate.getFileLocation(ident, subPath, locationName);
  }

  @Override
  public FileInfo[] listFiles(NameIdentifier ident, String locationName, String subPath)
      throws IOException {
    return delegate.listFiles(ident, locationName, subPath);
  }
}
