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

# Tag metadata undelete scope

Tag recovery uses the common metadata API: find retained generations with
`GET ...?include=deleted`, read one exact generation for its ETag, then send a
conditional `PATCH application/merge-patch+json` with `If-Match` and
`{ "deleted": false }`.

The restore transaction flips only the `tag_meta` row and its deletion receipt.
It does not revive tag-to-metadata-object relations, derive a new assignment
set, or replay external state. Leaving those relations deleted is deliberate:
the control plane can safely recover the tag definition without silently
reattaching it to objects that may have changed while the tag was absent.

Recovery therefore requires an exact retained generation that is still the
latest deleted generation for its name, a live parent scope, no live name
collision, an unexpired retention window, and a matching ETag. An ETag conflict
means the client can re-read and retry only if that same generation remains
recoverable. A cascade that cannot be restored cleanly is manually repaired;
tag recovery never guesses relationship state.
