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

<!--
1. Title: [#<issue>] <type>(<scope>): <subject>
   Treat the Conventional Commit type and scope as classification. Make the
   subject a compact narrative: state the concrete outcome, then add the goal,
   impact, or condition when it helps distinguish this pull request.
   Examples:
     - "[#123] feat(operator): Refresh metadata automatically after failover"
     - "[#233] fix(catalog): Reject invalid namespace names before persistence"
     - "[MINOR] refactor(core): Isolate event dispatch without changing behavior"
     - "[MINOR] docs(auth): Clarify Kerberos setup for first-time deployment"
     - "[#255] test: Cover catalog recovery across restart"
   Reference: https://www.conventionalcommits.org/en/v1.0.0/
2. This is the default template. Use it for code, tests, build, configuration,
   mixed changes, ordinary documentation, and minor design-document
   corrections. When the primary purpose is to propose, materially revise or
   clarify, or record a previously accepted design decision, and substantive
   changes are limited to a document under `design-docs/` and its supporting
   assets, use `.github/PULL_REQUEST_TEMPLATE/design_document.md` by selecting
   `template=design_document.md` instead.
3. Required before drafting or revising: read and apply
   `.github/WRITING_GUIDE.md`. If the guide is unavailable, do not block or
   invent missing rules. Use this fallback: lead with the problem and outcome,
   keep sections concise and non-overlapping, report only verification actually
   performed, and put procedural detail and metadata last.
4. Keep the four required headings below. Keep each section as short as its
   distinct answer permits, and do not repeat adjacent sections.
5. Delete optional sections when they do not add reviewer value.
6. If the pull request is unfinished, mark it as draft.
-->

### Why are the changes needed?

<!--
Lead with the bottom line: the concrete problem, need, or decision and why it
matters now. Include only the prior state, trigger, and evidence needed to
establish that need. Do not describe the implementation here.
-->

### What changes were proposed in this pull request?

<!--
Explain the concrete outcome and scope. Distinguish newly introduced behavior
from reused or inherited behavior when relevant. For stacked work, identify the
predecessor, this layer's responsibility, and work deliberately deferred to a
later layer. Do not repeat the motivation above or lead with a file inventory.
-->

### Does this PR introduce _any_ user-facing change?

<!--
Answer Yes or No. Identify affected users and visible behavior, API,
configuration, or property-key changes. State important user-facing behavior
that remains unchanged.
-->

### How was this patch tested?

<!--
List only verification actually performed, with exact commands or checks,
observed results, and relevant environment details. For material behavior
changes, identify the relevant test levels and behavior paths covered, plus any
material gap and why it remains. Never present planned verification as complete.
-->

<!--
Optional. Add only when focused feedback would help.

### What should reviewers focus on?

Identify a specific decision, risk, tradeoff, uncertainty, boundary, or review
entry point. Do not use this section for a generic request to review everything.
-->

<!--
Optional. Add only when useful procedural detail is not already clear from the
narrative or diff.

### Deep dive

Put useful file, class, component, or step-by-step detail here. Do not repeat the
reviewer story above.
-->

<!--
Optional. Add only when issue or stack relationships help. Keep Metadata last.

### Metadata

Fixes: #...
Part of: #...
Related: #...
Previous: #...
Next: #...
-->
