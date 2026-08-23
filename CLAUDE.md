# CLAUDE.md

Guidance for Claude Code (and other agents) working in this repository.

## Development standards

### Features are spec-driven

No feature is implemented without a spec. Before writing code for a feature:

1. Write (or update) the spec first, in `doc/`. The top-level design is
   `doc/logaperture-spec.md`; a feature large enough to need its own
   document goes in `doc/specs/<feature-name>.md` and is linked from the
   top-level spec.
2. Every feature spec in `doc/specs/` opens with a **Functional summary**
   section, right after the header/parent-spec block and before any scope
   or design detail: a bulleted list in the form "After this feature, the
   user will be able to X." Keep it as low on technical detail as the
   feature's own user-facing vocabulary allows — name the commands/options
   a user would actually type or see, not the mechanism behind them (no
   data models, file formats, module names, or internal types). Less is
   more: a handful of bullets a non-implementer can read to know what
   changed, not a restatement of the scope section that follows it.
3. Get the spec to a state the user has agreed to before starting
   implementation.
4. Implement against the spec.
5. **Commit the spec together with the code it describes**, in the same
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

### Spikes still branch and merge, but the deliverable is a doc

A spike (e.g. the M0 architecture spike in `doc/logaperture-spec.md` §17) is exploratory
work with no committed spec and no shippable code as its goal — it exists to answer an
open question before a spec can be written responsibly. It still uses a normal gitflow
feature branch and still merges to `develop` like anything else; it does not get its own
branch taxonomy.

What differs is the deliverable:

- The thing that lands on `develop` is a **written findings doc** — what was learned, and
  which open question(s) it answers (link back to the spec section that raised them).
- The exploratory code used to reach those findings is **not required to be committed**.
  Discard it once the findings are written up, unless a piece of it is genuinely worth
  keeping — in which case it graduates into a real feature branch, built against a spec,
  same as any other code.
