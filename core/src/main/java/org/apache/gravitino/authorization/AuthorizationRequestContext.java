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

import java.security.Principal;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.apache.gravitino.MetadataObject;
import org.apache.gravitino.UserPrincipal;
import org.apache.gravitino.auth.ActiveRoles;
import org.apache.gravitino.storage.relational.po.auth.GroupUpdatedAt;
import org.apache.gravitino.storage.relational.po.auth.OwnerInfo;
import org.apache.gravitino.storage.relational.po.auth.RoleUpdatedAt;
import org.apache.gravitino.storage.relational.po.auth.UserUpdatedAt;
import org.apache.gravitino.utils.PrincipalUtils;

/**
 * Per-HTTP-request scratchpad shared by {@link GravitinoAuthorizer} calls. A fresh instance is
 * created for each request by the authorization filter and threaded through {@code authorize},
 * {@code isOwner}, {@code isMetalakeUser} etc., so that:
 *
 * <ul>
 *   <li>repeated authorization decisions for the same {@code (principal, metalake, object,
 *       privilege)} short-circuit via {@link #allowAuthorizerCache} / {@link #denyAuthorizerCache};
 *   <li>user identity, name→id and metadataId→owner lookups are de-duplicated within the request
 *       (see the {@code computeXxxIfAbsent} helpers) so each underlying DB query runs at most once;
 *   <li>per-request role loading happens at most once via {@link #loadRole(Runnable)}.
 * </ul>
 *
 * <p>Instances are not intended to outlive a request and are not reusable across threads beyond the
 * request handling thread; the internal maps are {@link ConcurrentHashMap} purely to tolerate any
 * incidental fan-out (e.g. async listeners) within the same request scope.
 */
public class AuthorizationRequestContext {

  /** Used to cache the results of metadata authorization. */
  private final Map<AuthorizationKey, Boolean> allowAuthorizerCache = new ConcurrentHashMap<>();

  /** Used to cache the results of metadata authorization. */
  private final Map<AuthorizationKey, Boolean> denyAuthorizerCache = new ConcurrentHashMap<>();

  /** Used to determine whether the role has already been loaded. */
  private final AtomicBoolean hasLoadRole = new AtomicBoolean();

  /** Per-request user identity cache. Key: {@code metalake::userName}. */
  private final Map<String, Optional<UserUpdatedAt>> userInfoCache = new ConcurrentHashMap<>();

  /** Per-request group identity cache. Key: {@code metalake::groupName}. */
  private final Map<String, Optional<GroupUpdatedAt>> groupInfoCache = new ConcurrentHashMap<>();

  /** Per-request name→id cache. Deduplicates resolveMetadataId within a single request. */
  private final Map<String, Long> metadataIdCache = new ConcurrentHashMap<>();

  /** Per-request metadataId→owner cache. Deduplicates isOwner within a single request. */
  private final Map<Long, Optional<OwnerInfo>> ownerCache = new ConcurrentHashMap<>();

  /** Exact name→id bindings supplied for metadata generations hidden from normal lookup. */
  private final Map<ExactMetadataKey, Long> exactMetadataIds = new ConcurrentHashMap<>();

  /** Exact metadata generation→owner bindings supplied for hidden metadata generations. */
  private final Map<ExactOwnerKey, Optional<OwnerInfo>> exactOwners = new ConcurrentHashMap<>();

  /**
   * Per-request roleId → {@link RoleUpdatedAt} map populated by the fat-JOIN prefetch on the
   * authorize hot path. When present, {@code versionCheckAndLoadRoles} can skip its dedicated
   * {@code batchGetRoleUpdatedAt} round trip.
   */
  private volatile Map<Long, RoleUpdatedAt> prefetchedRoleVersions;

  private volatile String originalAuthorizationExpression;

  /**
   * The roles the caller has declared active for this request (role assumption). Read from the
   * current {@link UserPrincipal}; defaults to {@link ActiveRoles#all()} (no narrowing) when the
   * caller declared none.
   */
  private volatile ActiveRoles activeRoles = currentPrincipalActiveRoles();

  private static ActiveRoles currentPrincipalActiveRoles() {
    Principal principal = PrincipalUtils.getCurrentPrincipal();
    return principal instanceof UserPrincipal
        ? ((UserPrincipal) principal).getActiveRoles()
        : ActiveRoles.all();
  }

  /**
   * check allow
   *
   * @param principal principal
   * @param metalake metalake
   * @param metadataObject metadata object
   * @param privilege privilege
   * @param authorizer authorizer
   * @return authorization result
   */
  public boolean authorizeAllow(
      Principal principal,
      String metalake,
      MetadataObject metadataObject,
      Privilege.Name privilege,
      Function<AuthorizationKey, Boolean> authorizer) {
    AuthorizationKey context = new AuthorizationKey(principal, metalake, metadataObject, privilege);
    return allowAuthorizerCache.computeIfAbsent(context, authorizer);
  }

  /**
   * check deny
   *
   * @param principal principal
   * @param metalake metalake
   * @param metadataObject metadata object
   * @param privilege privilege
   * @param authorizer authorizer
   * @return authorization result
   */
  public boolean authorizeDeny(
      Principal principal,
      String metalake,
      MetadataObject metadataObject,
      Privilege.Name privilege,
      Function<AuthorizationKey, Boolean> authorizer) {
    AuthorizationKey context = new AuthorizationKey(principal, metalake, metadataObject, privilege);
    return denyAuthorizerCache.computeIfAbsent(context, authorizer);
  }

  /**
   * Runs {@code runnable} at most once per request. The double-checked guard plus {@code
   * synchronized(this)} prevents two concurrent authorize calls in the same request from both
   * triggering the (potentially expensive) role load.
   */
  public void loadRole(Runnable runnable) {
    if (hasLoadRole.get()) {
      return;
    }
    synchronized (this) {
      if (hasLoadRole.get()) {
        return;
      }
      try {
        runnable.run();
        hasLoadRole.set(true);
      } catch (Exception e) {
        throw new RuntimeException("Failed to load role: ", e);
      }
    }
  }

  /**
   * Per-request {@link UserUpdatedAt} dedup. Loader may return {@link Optional#empty()} to cache
   * the "user not found" outcome and avoid repeated DB lookups within a single request.
   */
  public Optional<UserUpdatedAt> computeUserInfoIfAbsent(
      String key, Function<String, Optional<UserUpdatedAt>> loader) {
    return userInfoCache.computeIfAbsent(
        key, k -> Objects.requireNonNull(loader.apply(k), "User info loader must not return null"));
  }

  /**
   * Per-request {@link GroupUpdatedAt} dedup. Loader may return {@link Optional#empty()} to cache
   * the "group not found" outcome and avoid repeated DB lookups within a single request.
   */
  public Optional<GroupUpdatedAt> computeGroupInfoIfAbsent(
      String key, Function<String, Optional<GroupUpdatedAt>> loader) {
    return groupInfoCache.computeIfAbsent(
        key,
        k -> Objects.requireNonNull(loader.apply(k), "Group info loader must not return null"));
  }

  /** Per-request name→id dedup. Loader must return a non-null id or throw. */
  public Long computeMetadataIdIfAbsent(String key, Function<String, Long> loader) {
    return metadataIdCache.computeIfAbsent(
        key,
        k -> Objects.requireNonNull(loader.apply(k), "Metadata id loader must not return null"));
  }

  /**
   * Per-request metadataId→owner dedup. Loader returns {@link Optional#empty()} when the object has
   * no owner; the absent result is cached as well.
   */
  public Optional<OwnerInfo> computeOwnerIfAbsent(
      Long metadataId, Function<Long, Optional<OwnerInfo>> loader) {
    return ownerCache.computeIfAbsent(
        metadataId,
        id -> Objects.requireNonNull(loader.apply(id), "Owner loader must not return null"));
  }

  /**
   * Binds one metadata name to an immutable entity id for this authorization request.
   *
   * <p>This is used when an exact metadata generation still exists relationally but is deliberately
   * hidden from ordinary name lookup, such as a retained table deletion. A conflicting binding is
   * rejected instead of silently authorizing a different generation under the same name.
   *
   * @param metalake metalake containing the metadata object
   * @param metadataObject metadata object used by the authorization expression
   * @param metadataId immutable entity id of the exact generation
   */
  public void bindExactMetadataId(String metalake, MetadataObject metadataObject, long metadataId) {
    ExactMetadataKey key = exactMetadataKey(metalake, metadataObject);
    Long existing = exactMetadataIds.putIfAbsent(key, metadataId);
    if (existing != null && existing != metadataId) {
      throw new IllegalStateException(
          String.format(
              "Metadata object %s is already bound to entity id %d",
              metadataObject.fullName(), existing));
    }
  }

  /**
   * Returns an exact entity-id binding supplied for this request.
   *
   * @param metalake metalake containing the metadata object
   * @param metadataObject metadata object used by the authorization expression
   * @return exact entity id, or empty when normal lookup should be used
   */
  public Optional<Long> findExactMetadataId(String metalake, MetadataObject metadataObject) {
    return Optional.ofNullable(exactMetadataIds.get(exactMetadataKey(metalake, metadataObject)));
  }

  /**
   * Binds the owner of one exact metadata generation for this authorization request.
   *
   * <p>An empty owner is meaningful and prevents a stale shared owner-cache entry from being used
   * for the exact generation.
   *
   * @param metadataId immutable entity id of the exact generation
   * @param metadataType metadata object type
   * @param owner exact owner, or empty when the generation has no owner
   */
  public void bindExactOwner(
      long metadataId, MetadataObject.Type metadataType, Optional<OwnerInfo> owner) {
    Objects.requireNonNull(metadataType, "metadataType must not be null");
    Objects.requireNonNull(owner, "owner must not be null");
    ExactOwnerKey key = new ExactOwnerKey(metadataId, metadataType);
    Optional<OwnerInfo> existing = exactOwners.putIfAbsent(key, owner);
    if (existing != null && !existing.equals(owner)) {
      throw new IllegalStateException(
          String.format(
              "Metadata entity %d of type %s already has an exact owner binding",
              metadataId, metadataType));
    }
  }

  /**
   * Returns an exact owner binding supplied for this request.
   *
   * <p>The outer optional distinguishes "no exact binding" from an exact binding whose owner is
   * absent.
   *
   * @param metadataId immutable entity id of the exact generation
   * @param metadataType metadata object type
   * @return exact owner binding, or empty when normal lookup should be used
   */
  public Optional<Optional<OwnerInfo>> findExactOwner(
      long metadataId, MetadataObject.Type metadataType) {
    Objects.requireNonNull(metadataType, "metadataType must not be null");
    return Optional.ofNullable(exactOwners.get(new ExactOwnerKey(metadataId, metadataType)));
  }

  public String getOriginalAuthorizationExpression() {
    return originalAuthorizationExpression;
  }

  public void setOriginalAuthorizationExpression(String originalAuthorizationExpression) {
    this.originalAuthorizationExpression = originalAuthorizationExpression;
  }

  /**
   * Returns the prefetched roleId → {@link RoleUpdatedAt} map, or {@code null} when the fat-JOIN
   * prefetch has not run for this request.
   *
   * @return the prefetched role-versions map or {@code null}
   */
  public Map<Long, RoleUpdatedAt> getPrefetchedRoleVersions() {
    return prefetchedRoleVersions;
  }

  /**
   * Sets the prefetched roleId → {@link RoleUpdatedAt} map; called once per request by the
   * authorize hot path after the fat-JOIN prefetch.
   *
   * @param prefetchedRoleVersions roleId → {@link RoleUpdatedAt} map
   */
  public void setPrefetchedRoleVersions(Map<Long, RoleUpdatedAt> prefetchedRoleVersions) {
    this.prefetchedRoleVersions = prefetchedRoleVersions;
  }

  /**
   * Returns the roles declared active for this request. Never {@code null}; defaults to {@link
   * ActiveRoles#all()}.
   *
   * @return the active-role declaration
   */
  public ActiveRoles getActiveRoles() {
    return activeRoles;
  }

  /**
   * Sets the roles declared active for this request. Narrowing is subtractive: only roles the
   * caller actually holds are ever consulted, regardless of what is declared here.
   *
   * @param activeRoles the active-role declaration; must not be {@code null}
   */
  public void setActiveRoles(ActiveRoles activeRoles) {
    this.activeRoles = Objects.requireNonNull(activeRoles, "activeRoles must not be null");
  }

  private static ExactMetadataKey exactMetadataKey(String metalake, MetadataObject metadataObject) {
    Objects.requireNonNull(metalake, "metalake must not be null");
    Objects.requireNonNull(metadataObject, "metadataObject must not be null");
    return new ExactMetadataKey(metalake, metadataObject.type(), metadataObject.fullName());
  }

  @AllArgsConstructor
  @EqualsAndHashCode
  private static class ExactMetadataKey {
    private final String metalake;
    private final MetadataObject.Type metadataType;
    private final String fullName;
  }

  @AllArgsConstructor
  @EqualsAndHashCode
  private static class ExactOwnerKey {
    private final long metadataId;
    private final MetadataObject.Type metadataType;
  }

  /**
   * Composite key for {@link #allowAuthorizerCache} / {@link #denyAuthorizerCache}. Immutable —
   * mutating any field after construction would silently corrupt the {@link
   * java.util.Objects#hashCode} used by the backing {@link ConcurrentHashMap}.
   */
  @Getter
  @AllArgsConstructor
  @EqualsAndHashCode
  public static class AuthorizationKey {
    private final Principal principal;
    private final String metalake;
    private final MetadataObject metadataObject;
    private final Privilege.Name privilege;
  }
}
