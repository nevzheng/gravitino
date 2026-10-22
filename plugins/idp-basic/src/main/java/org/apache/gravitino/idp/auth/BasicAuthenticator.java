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

package org.apache.gravitino.idp.auth;

import com.google.common.base.Preconditions;
import java.nio.charset.StandardCharsets;
import java.security.Principal;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.Config;
import org.apache.gravitino.Configs;
import org.apache.gravitino.UserGroup;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.AuthConstants;
import org.apache.gravitino.exceptions.UnauthorizedException;
import org.apache.gravitino.idp.IdpUserGroupManager;
import org.apache.gravitino.idp.model.IdpUser;
import org.apache.gravitino.idp.web.rest.feature.IdpRESTFeature;
import org.apache.gravitino.server.authentication.Authenticator;
import org.apache.gravitino.server.authentication.AuthenticatorContext;

/** Authenticates HTTP Basic credentials against built-in IdP user metadata. */
public class BasicAuthenticator implements Authenticator {

  private static final String BASIC_CHALLENGE = AuthConstants.AUTHORIZATION_BASIC_HEADER.trim();

  private IdpUserGroupManager userGroupManager;

  /** Creates an authenticator that builds its manager when it is initialized. */
  public BasicAuthenticator() {}

  /**
   * Creates an authenticator backed by the given manager.
   *
   * @param userGroupManager the built-in IdP user and group manager.
   */
  public BasicAuthenticator(IdpUserGroupManager userGroupManager) {
    this.userGroupManager = userGroupManager;
  }

  @Override
  public boolean isDataFromToken() {
    return true;
  }

  @Override
  public Principal authenticateToken(byte[] tokenData) {
    Preconditions.checkState(
        userGroupManager != null, "Basic authenticator has not been initialized");
    String authData = requireBasicAuthHeader(tokenData);
    BasicCredentials credentials = parseBasicCredentials(authData);
    return authenticate(credentials, authData);
  }

  /**
   * Not supported: the authenticator builds its manager with the id generator of an {@link
   * AuthenticatorContext}, which the server passes to {@link #initialize(Config,
   * AuthenticatorContext)}.
   *
   * @param config The server configuration.
   * @throws UnsupportedOperationException always
   */
  @Override
  public void initialize(Config config) {
    throw new UnsupportedOperationException(
        "BasicAuthenticator is initialized with an AuthenticatorContext");
  }

  /**
   * Initializes the authenticator. Unless a manager was given to the constructor, the shared
   * built-in IdP manager is built with the context's id generator.
   *
   * @param config The server configuration.
   * @param context The server services.
   */
  @Override
  public void initialize(Config config, AuthenticatorContext context) {
    validateExtensionPackage(config);
    if (userGroupManager == null) {
      this.userGroupManager = IdpUserGroupManager.getInstance(config, context.idGenerator());
    }
  }

  /**
   * Validates that the built-in IdP REST extension package is enabled when Basic authentication is
   * used.
   *
   * @param config The server configuration.
   */
  static void validateExtensionPackage(Config config) {
    boolean idpExtensionEnabled =
        config.get(Configs.REST_API_EXTENSION_PACKAGES).stream()
            .anyMatch(
                pkg -> IdpRESTFeature.IDP_REST_EXTENSION_PACKAGE.equalsIgnoreCase(pkg.trim()));
    if (!idpExtensionEnabled) {
      throw new IllegalStateException(
          String.format(
              "'basic' in gravitino.authenticators requires gravitino.server.rest.extensionPackages "
                  + "to include %s.",
              IdpRESTFeature.IDP_REST_EXTENSION_PACKAGE));
    }
  }

  @Override
  public boolean supportsToken(byte[] tokenData) {
    return tokenData != null
        && new String(tokenData, StandardCharsets.UTF_8)
            .startsWith(AuthConstants.AUTHORIZATION_BASIC_HEADER);
  }

  private String requireBasicAuthHeader(byte[] tokenData) {
    String authData = tokenData == null ? "" : new String(tokenData, StandardCharsets.UTF_8);
    if (StringUtils.isBlank(authData)) {
      throw new UnauthorizedException("Empty token authorization header", BASIC_CHALLENGE);
    }
    if (!authData.startsWith(AuthConstants.AUTHORIZATION_BASIC_HEADER)) {
      throw new UnauthorizedException("Invalid token authorization header", BASIC_CHALLENGE);
    }
    return authData;
  }

  private BasicCredentials parseBasicCredentials(String authData) {
    String credential = authData.substring(AuthConstants.AUTHORIZATION_BASIC_HEADER.length());
    if (StringUtils.isBlank(credential)) {
      throw new UnauthorizedException(
          "Malformed Basic authorization header: missing credentials", BASIC_CHALLENGE);
    }
    credential = credential.trim();

    final byte[] decodedBytes;
    try {
      decodedBytes = Base64.getDecoder().decode(credential);
    } catch (IllegalArgumentException e) {
      throw new UnauthorizedException(
          "Malformed Basic authorization header: invalid base64", BASIC_CHALLENGE);
    }

    String decodedCredential = new String(decodedBytes, StandardCharsets.UTF_8);
    String[] parts = decodedCredential.split(":", 2);
    if (parts.length != 2) {
      throw new UnauthorizedException(
          "Malformed Basic authorization header: credentials must be in username:password format",
          BASIC_CHALLENGE);
    }

    String username = parts[0];
    if (StringUtils.isBlank(username)) {
      throw new UnauthorizedException(
          "Malformed Basic authorization header: username must not be blank", BASIC_CHALLENGE);
    }

    String password = parts[1];
    if (StringUtils.isBlank(password)) {
      throw new UnauthorizedException("Invalid username or password", BASIC_CHALLENGE);
    }
    return new BasicCredentials(username, password);
  }

  private UserPrincipal authenticate(BasicCredentials credentials, String authData) {
    IdpUser user = userGroupManager.authenticate(credentials.username(), credentials.password());

    List<UserGroup> groups =
        user.groupNames().stream()
            .map(groupName -> new UserGroup(Optional.empty(), groupName))
            .collect(Collectors.toList());
    return new UserPrincipal(user.name(), groups, authData);
  }

  private static final class BasicCredentials {
    private final String username;
    private final String password;

    private BasicCredentials(String username, String password) {
      this.username = username;
      this.password = password;
    }

    private String username() {
      return username;
    }

    private String password() {
      return password;
    }
  }
}
