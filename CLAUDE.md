# CLAUDE.md

Guidance for Claude Code (and other agents) working in this repository.

The contribution policy — spec-driven features, guide updates, issue and branch rules, the
tracking-issue workflow, copyright headers, naming non-trivial lambdas, spikes — lives in
[CONTRIBUTING.md](CONTRIBUTING.md) and applies to agents exactly as it does to people. Read it
before changing anything. This file holds only what is specific to agents.

@CONTRIBUTING.md

## Agent-specific standards

### Sign-off reviews happen in an artifact, not the thread

Step 3 of the spec-driven workflow in [CONTRIBUTING.md](CONTRIBUTING.md) — getting a spec to a state the user has agreed to — is a
review, and when the spec is large enough that sign-off means working
through several open decisions, run that review off a published
**Artifact**, not a wall of terminal text. The same applies to any
comparable design doc the user will discuss back and forth.

- Publish the walkthrough as a stable page the reviewer keeps open in the
  side panel while the discussion happens in the thread. Follow-up
  questions then don't scroll the reference out from under them.
- Give the page section anchors and a table of contents, and enumerate
  every open decision with a stable id (`#1`, `#2`, …) the reviewer can
  cite by number.
- The artifact is a review aid, not a deliverable. `doc/specs/<feature>.md`
  stays the source of truth; sign-off still means agreeing the spec text,
  and the spec still lands with the code per the spec-driven rule. Fold the
  agreed decisions back into the spec file before implementation starts.
