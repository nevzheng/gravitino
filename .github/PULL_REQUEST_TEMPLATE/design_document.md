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
1. Use this template only when the primary purpose is to propose, materially
   revise or clarify, or record a previously accepted design decision, and
   substantive changes are limited to a document under `design-docs/` and its
   supporting assets. Use `.github/PULL_REQUEST_TEMPLATE.md` for code, tests,
   build, configuration, ordinary documentation, minor design-document
   corrections, and mixed changes.
2. Required before drafting or revising: read and apply
   `.github/WRITING_GUIDE.md`. If the guide is unavailable, do not block or
   invent missing rules. Use this fallback: lead with the need and proposed
   decision, keep proposals distinct from shipped behavior, link the document
   instead of replaying it, report only evidence and checks actually performed,
   and put formal metadata last.
3. Title: [#<issue>] docs(<affected-area>): <subject>
   Reference the tracking Issue and use the affected component or domain as the
   scope. Treat `docs` and the scope as classification. Make the subject a
   compact narrative that names the proposed decision, contract, or boundary
   and, when useful, its goal or impact. Do not describe the act of editing a
   document or use `!` merely because a future implementation may contain a
   breaking change.
   Choose a verb that reveals the pull request's change kind:
     - New proposal: `Propose` or `Define`
     - Material revision: `Revise`, `Narrow`, or `Expand`
     - Material clarification: `Clarify` or `Align`
     - Previously accepted decision: `Record acceptance of` (only with
       supporting evidence)
   Examples:
     - "[#12298] docs(client): Define TLS boundaries for safe Java configuration"
     - "[#12252] docs(secrets): Propose reference-based secret persistence"
     - "[#12345] docs(cache): Revise invalidation ownership for multi-node failures"
   Reference: https://www.conventionalcommits.org/en/v1.0.0/
4. Treat every direction introduced or revised here as proposed unless a linked
   decision establishes otherwise. A previously accepted design is still
   distinct from implemented, production-tested, or shipped behavior; claim
   each status only with supporting evidence.
5. Keep this description concise. Let the design document carry detailed
   background, goals, non-goals, alternatives, proposal, and task breakdown.
6. If the pull request is unfinished, mark it as draft.
-->

### Context

<!--
Lead with the concrete problem and why it matters now. Include only the current
state, prior decisions, constraints, trigger, and evidence needed to establish
the need. Link the initiating Issue, Discussion, dev@ thread, or predecessor
inline. Do not introduce the proposed solution or recount the drafting process.
-->

### Objective

<!--
State the concrete goal and intended impact if the design is adopted. Include
only the essential scope or non-goal needed to bound that objective.
-->

### Decision summary

<!--
Summarize the decisions, contracts, or boundaries this document recommends. For
a decision record, summarize the previously accepted direction and link the
evidence of that decision. Keep every direction distinct from existing or
shipped behavior. Link the relevant document sections instead of reproducing
their technical detail.
-->

### Review focus

<!--
Name the specific decisions, tradeoffs, risks, assumptions, or open questions
where reviewer input is needed. Prefer one to three focused bullets with links
to the relevant document sections. Do not ask reviewers to review everything.
-->

### Impact if adopted

<!--
State that this documentation-only pull request changes no shipped behavior.
Then use conditional language to explain the expected user, API, configuration,
operational, migration, or compatibility impact if the design is implemented.
Mention important unchanged behavior when it corrects a likely assumption. For
a previously accepted direction, distinguish expected impact from implementation.
-->

### Participants

<!--
Use people's names, not GitHub usernames. List only confirmed participants; do
not infer a role or contact someone solely to populate this section. Primary
reviewers evaluate the core direction. Secondary reviewers advise on affected
domains or specific tradeoffs. Omit an optional role when it has no participants.
-->

- **Author**: Full name
- **Primary reviewers**: Full names
- **Secondary reviewers**: Full names
- **Contributors**: Full names

### Design document

<!--
Link the document that contains the detailed design. For a long document, also
link the section where review should begin. Do not reproduce its outline or
technical detail in this pull request description.
-->

- **Document**: [Design title](link)
- **Document change**: New proposal | Material revision | Material clarification | Decision record

### Related material

<!--
Link only material that helps reviewers understand or evaluate the design, such
as prior designs, Issues, Discussions, dev@ threads, prototypes, benchmarks,
implementations, standards, or external references. Explain briefly why each
link matters. Do not create an unexplained link dump or repeat formal issue and
stack relationships reserved for Metadata.
-->

- [Reference title](link): Why it matters to this design.

<!--
Optional. Add only when prior decisions or chronology materially help reviewers.

### Background and decision history

Summarize only the context not already covered above, and link the source. Do
not duplicate the design document's background or replay the drafting process.
-->

### Document validation

<!--
List only document checks actually performed, with exact commands or methods and
observed results. Cover applicable links, diagrams, current code or API
references, formatting, and license headers. State material checks not run and
why. Link design evidence and evaluation inside the document. Do not describe
proposed behavior as implementation-tested.
-->

<!--
Optional final section. Add only when issue relationships or stack navigation
help. Use `Fixes:` only when accepting this document fully resolves a
design-scoped Issue, not an Issue that also requires implementation.

### Metadata

Part of: #...
Related: #...
Previous: #...
Next: #...
-->
