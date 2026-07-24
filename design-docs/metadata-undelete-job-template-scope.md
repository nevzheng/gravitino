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

# Job-template metadata undelete scope

Job-template recovery is metadata-only. A client discovers a retained
generation with `GET ...?include=deleted`, reads that exact generation for its
ETag, and sends `PATCH application/merge-patch+json` with `If-Match` and
`{ "deleted": false }`.

The transaction restores only the `job_template_meta` definition and its
deletion receipt. It deliberately does not restore `job_run_meta`, execution
state, metrics, output, scheduling history, or any executor-side work. A
recovered definition is therefore a definition a caller may run again, never a
claim that an earlier run has resumed.

The normal recovery rules apply: the request selects one exact retained
generation; it must still be the most-recent deleted generation for the name;
its parent scope must be live; no live name may occupy the target; the
retention window must not have expired; and the ETag must match. Conditional
conflicts are safe to re-read and retry only while that same generation remains
recoverable. A damaged cascade is repaired manually rather than inferred from
job history.
