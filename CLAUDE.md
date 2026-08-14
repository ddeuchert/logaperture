# CLAUDE.md

Guidance for Claude Code (and other agents) working in this repository.

## Development standards

### Features are spec-driven

No feature is implemented without a spec. Before writing code for a feature:

1. Write (or update) the spec first, in `doc/`. The top-level design is
   `doc/logaperture-spec.md`; a feature large enough to need its own
   document goes in `doc/specs/<feature-name>.md` and is linked from the
   top-level spec.
2. Get the spec to a state the user has agreed to before starting
   implementation.
3. Implement against the spec.
4. **Commit the spec together with the code it describes**, in the same
   commit (or the same PR if the change spans multiple commits). A spec
   change and its implementation should never land separately — the repo
   history should always show *what changed* and *why it changed* as one
   unit.

If a feature's behavior diverges from its spec during implementation,
update the spec before or alongside the code change that caused the
divergence — don't let them drift apart.

### Copyright header

Every source file must carry a copyright header, using the Apache License
2.0 boilerplate, e.g. for Java:

```java
/*
 * Copyright 2026 David Deuchert
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
```

- The year is the year the file was **first created**, and is never edited
  afterward — no `2026-2029`-style ranges to maintain. A file created in a
  later year gets that year instead.
- Copyright holder is `David Deuchert`.
- Package root is `org.logaperture` (project domain: logaperture.org).
