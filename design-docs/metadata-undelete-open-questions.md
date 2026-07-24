<!--
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

   http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing,
  software distributed under the License is distributed on an
  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
  KIND, either express or implied.  See the License for the
  specific language governing permissions and limitations
  under the License.
-->

# Metadata Undelete Open Questions

Status: Non-blocking follow-ups after the metadata-only POC. These questions
must not prevent landing the common API, transaction, and client skeleton.

## Fixed POC boundary

- Every supported root uses deleted discovery, exact ID lookup, and conditional
  merge PATCH on its existing collection and item routes.
- Restore changes retained Gravitino relational metadata in one transaction.
- Restore never calls a connector, identity provider, authorization plugin,
  executor, or another downstream system.
- Only the latest eligible same-name generation can be restored. The immutable
  entity ID and strong ETag prevent silent generation changes.
- Cascade-only descendants return with their owning root generation and are not
  independent recovery targets.
- Deleted reads and restore are temporarily service-administrator-only.

## Object semantics to revisit

| Object | Current POC generation | Later semantic review |
| --- | --- | --- |
| Policy | Policy root and the live policy versions captured by its deletion. Standalone deletion does not capture or restore associations, ownership, or grants. | Whether restoring an enabled policy needs an explicit confirmation, and whether a future operation should separately reconcile bindings. |
| Job template | Template root and captured terminal job-run metadata. No run is resubmitted, and executor, staging, and metrics state are excluded. | Whether terminal history belongs to the template generation or only the definition should return; how stale artifacts, configuration, and secrets should be surfaced. |
| User | User root plus captured Gravitino role memberships and ownership rows. No identity-provider operation occurs. | Whether recovery should preserve the prior enabled state, require external-identity verification, or offer a definition-only mode. |
| Group | Group root plus captured Gravitino role memberships and ownership rows. No identity-provider operation occurs. | How external group-membership drift should be diagnosed or reconciled. |
| Role | Role root plus captured memberships, securable grants, and ownership rows. No external authorization replay occurs. | Whether restored grants should require review and how Gravitino state should be reconciled with Ranger or another plugin. |
| Tag | Tag root plus captured Gravitino tag relations. No downstream classification system is contacted. | Whether restoring classifications should require a review when associated objects changed during retention. |

## Cross-cutting questions

1. Should applicable retention be frozen into each deletion record or resolved
   from current scope configuration when garbage collection runs?
2. Which parent-scope privileges should replace the temporary
   service-administrator-only gate?
3. Should deletion records remain auditable after restore or purge, and under
   what retention policy?
4. Should a future reconciliation service only report drift, or also offer
   explicit connector-, identity-, authorization-, and executor-specific repair
   actions?
5. Should metadata restored near expiry receive a short GC safety extension?
6. Should autonomous job-run tombstones have an operator-only diagnostic view
   without becoming independent restore targets?
7. What stable immutable identity should standalone model-version recovery use?
8. Should standalone statistics gain an item resource, or remain recoverable
   only through their owning table generation?
