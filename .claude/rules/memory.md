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

# Claude Code Memory

These instructions apply only to Claude Code when the configured memory tools
are available. If they are unavailable, continue without blocking the task.

- Before starting a task, use `mcp-search` to look for similar work. Search for
  prior context when code or configuration is unfamiliar.
- When a problem occurs, search memory for a known solution before debugging
  from scratch.
- After completing a task, save reusable findings and solutions to
  `claude-mem`.
- Use multiple keyword combinations, such as the module plus issue type or the
  class name plus error.
