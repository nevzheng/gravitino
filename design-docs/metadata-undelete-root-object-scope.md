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

# Root-object metadata undelete scope

The root-object lane follows the same API contract as tables and policy:
discover a retained generation with `GET ...?include=deleted`, read one exact
generation to obtain its ETag, then restore it with a conditional
`PATCH application/merge-patch+json`, `If-Match`, and `{ "deleted": false }`.

This lane is metadata-only. A restore is an exact relational transaction over
the root row and its deletion receipt; it never calls a connector, identity
provider, job executor, authorization plugin, or other external system.

| Object | Restored | Intentionally not restored |
| --- | --- | --- |
| Policy | Policy row and intrinsic policy versions | Policy associations, owners, grants, external authorization state |
| User | User row | User-role membership and ownership relations |
| Group | Group row | Group-role membership and ownership relations |
| Role | Role row | User/group membership, securable-object grants, ownership relations |
| Job template | Template definition | Job runs, executions, metrics, and history |
| Tag | Tag row | Tag-to-metadata-object relations |

If a root object was deleted as part of a metadata cascade, container recovery
is responsible for restoring the tree. Root-object recovery does not infer or
replay relation state on its own. This keeps the control plane reversible
without silently fabricating downstream or authorization state.

The next implementation commits in this lane are identity roots, job-template
roots, and tag roots. Each must preserve the table/policy failure rules: exact
generation only, latest generation for the name, a live parent scope, no live
name (and where applicable external-ID) collision, unexpired retention, and a
matching ETag. A conflict means clients re-read and retry only when the exact
generation remains restorable; a broken cascade is repaired manually.
