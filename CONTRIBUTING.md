# Contributing to LogAperture

Thanks for helping. This is the contribution policy for everyone — people and agents alike.
For how to build, test, run and debug, see [DEVELOPMENT.md](DEVELOPMENT.md). For what the
project is and why, see the [README](README.md) and the [design spec](doc/logaperture-spec.md).

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
3. Get the spec to a state the maintainer has agreed to before starting
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

### User-visible changes update the guide in the same PR

The user guide lives in `guide/` and is published at logaperture.org
([`doc/specs/user-documentation.md`](doc/specs/user-documentation.md)). A PR that changes
anything a user can see — a command, an option, output, a default, an agent argument or
`-Dlogaperture.*` property, a file format — updates the guide pages it affects **in the same
PR**, the same way the spec lands with the code. A feature spec's Functional summary names the
guide pages the feature changes, so the reviewer knows what to expect in the diff.

Three pages are checked by tests, so these fail the build rather than drifting:

- `guide/reference/logctl.md` is generated from `logctl`'s help (`HelpTopics.java`). After
  changing the help, regenerate it:
  `mvn -pl logaperture-cli test -Dtest=HelpReferenceTest -Dlogaperture.help.regenerate=true`.
- `guide/reference/agent-options.md` and `guide/reference/doctor-checks.md` are hand-written;
  `GuideReferenceDriftTest` fails when a property, agent argument or `doctor` check id in the
  code is missing from them, or one on the page no longer exists.

Example output in the guide is captured from a real run (the WildFly dev environment, or a plain
JVM), not written from memory.

### Move the tracking issue to "Merged" when the change lands

Work is tracked in the **LogAperture** GitHub Project
(<https://github.com/users/ddeuchert/projects/1>). Its **Status** field runs Backlog → Spec →
In progress → In review → Merged → Released; releases are scoped by the issue's **milestone**
(`1.0.0` / `1.0.1` for the 1.0 line, `1.1.0` committed next minor, `1.2.0` expected to follow,
`1.x` unscheduled). Move the issue's Status as the work moves — at minimum when the spec starts,
when the PR opens, and when it merges.

The milestone also picks the branch ([`doc/specs/release-lines.md`](doc/specs/release-lines.md)):
a change for 1.0.0 or a 1.0.x patch branches from `release/1.0` and its PR targets
`release/1.0`; everything else branches from and targets `develop`. A merge to `release/1.0` is
merged forward into `develop` with a merge commit.

Every change to product code implements an issue — a bug fix of any size included. If there is
none, file a short one first; it gets a milestone like any other issue. The one exception is a
change that touches **only tests or only documentation**, with no product code and nothing a user
can see: it needs no issue, uses a `test/<slug>` or `docs/<slug>` branch, and its PR title says
what changed without an `(issue #N)`. It skips the Status and `PendingRelease` steps below, and its
base branch still follows the line it applies to. If working on such a change turns up a product
bug (a test that only passes by working around one, say), file an issue for the bug and link it
from the PR rather than folding the fix in silently.

For example:

| Change | Branch | PR title |
|---|---|---|
| Fix the holder pid read on Windows (product code, issue #363) | `feature/363-windows-lock-region` | `Keep the holder pid readable under the Windows file lock (issue #363)` |
| Add a Log4j 2 adapter (product code, issue #302) | `feature/302-log4j2-adapter` | `Log4j 2 adapter: appender discovery (issue #302)` |
| Fix a Windows path assertion in a test (tests only) | `test/windows-path-assertions` | `Fix path assertions in tests on Windows` |
| Correct a typo in a guide page (docs only) | `docs/fix-guide-typo` | `Fix a typo in the quick start` |

A product-code branch is named for the issue it implements (`feature/<issue>-<slug>`) and its PR
title/body reference that issue (e.g. `(issue #32)`) — deliberately never GitHub's `Closes #32`
keyword, so that closing, the label and the Status stay one deliberate step. The moment that PR
merges, as part of finishing the merge and not a separate followup step: set the issue's Status to
**Merged**, add the **`PendingRelease`** label, and close the issue.

**`PendingRelease`** means: code complete on its line, will ship in the release its milestone
names. The label carries no version — the milestone already does. A closed issue with this label
is done but not yet shipped; the label is the "not yet released" filter. At the release cut,
remove the label from every issue the release ships and set its Status to **Released**.

An issue delivered across several PRs stays open, at **In progress**, until its final review;
it is closed and labelled `PendingRelease` then, not per slice.

A new issue gets a milestone when it is filed (`1.x` if it is not yet scheduled).

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

### Name non-trivial lambdas before passing them

A lambda passed inline as a method argument hides its resolved type and its
captures behind syntax whose meaning depends on looking up the target
method's parameter type. When a lambda has a block body, or captures
several enclosing variables, assign it to a local variable typed by the
functional interface, named for what it does, before passing it:

```java
Runnable reapplyOnReset = () -> {
    for (String name : adapter.knownLoggerNames()) {
        baselines.captureIfAbsent(name, adapter);
    }
    service.reapplyActiveOverrides(adapter);
};
adapter.onReset(reapplyOnReset);
```

rather than passing the lambda inline. This costs nothing at runtime — the
compiler resolves the same target type and generates the same call site
either way — and it turns "what type does this become, and from where" into
a plain declaration instead of something the reader has to reconstruct.

This doesn't apply to short, single-expression lambdas (`.map(x ->
x.name())`, `Comparator.comparing(...)`) — naming every trivial lambda would
add noise rather than remove it. Reserve the pattern for lambdas with a
block body or non-obvious captures.

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
