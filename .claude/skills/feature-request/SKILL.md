---
name: feature-request
description: >-
  Capture a new feature idea or roadmap suggestion for LogAperture as a
  properly-placed roadmap entry. Use when someone proposes new functionality,
  says "it would be nice if", "we should add", "feature request", "roadmap
  idea", "consider adding", describes a pain point they hit and what they wish
  the tool did about it, or explicitly invokes /feature-request. Turns the idea
  into an entry in doc/logaperture-spec.md §18 (or a milestone note in §17, or a
  deferred-scope note in an existing feature spec) at roadmap altitude —
  motivation, the concrete commands a user would type, cost and dependencies,
  and open design questions flagged as deferred rather than resolved.
---

# Capturing a feature request on the roadmap

The goal is to record an idea so it isn't lost and so a future implementer can
pick it up — **not** to design or implement it now. Stay at roadmap altitude.

## 1. Orient

Read the current roadmap before proposing where the idea goes:

- `doc/logaperture-spec.md` **§17 Roadmap** — the layer model (Layer 0–3) and
  the milestone table (M0–M6), each with a scope and an exit criterion.
- `doc/logaperture-spec.md` **§18 Future enhancements** — deliberately post-1.0
  items, as numbered subsections (`§18.1` … `§18.7`), plus the `§18.6 Other
  candidates` bullet list for smaller ones.
- Any **feature spec** in `doc/specs/` the idea touches (e.g. `level-control.md`,
  `cli-transport.md`, `persistence.md`, `wildfly-support.md`, `distribution-bundle.md`).
  Each opens with a **Functional summary** and lists explicitly-deferred scope.

## 2. Understand the request

Get these from the requester (ask if not given):

- **Symptom** — what they hit, ideally with a concrete example (a log excerpt, a
  command that didn't work, a manual workaround they're doing).
- **What they wish the tool did** — named as commands/options a user would type
  or see, not as a mechanism.
- **Roughly where they think it belongs** — a near-term refinement, or post-1.0.

## 3. Decide placement

| The idea is… | Record it as… |
|---|---|
| A small post-1.0 item | A bullet in **§18.6 Other candidates** |
| A post-1.0 item worth a paragraph | A new **§18.x** subsection |
| A near-term refinement of an existing feature | A **deferred-scope bullet in that feature's spec** (`doc/specs/…`), cross-referenced from a short §18 line if it's worth roadmap visibility |
| Big enough to be its own feature | A draft **`doc/specs/<feature-name>.md`** with a Functional summary, linked from the top-level spec — then get it to sign-off before any code (see CLAUDE.md) |
| A change to what a milestone ships | A note in the relevant **§17** milestone row |

Most captured ideas are one of the first three. Prefer the lightest option that
keeps the thread findable. If the idea partly overlaps an existing planned
feature (e.g. the squelch engine, §7 / Feature 3), say so and let that feature
own the overlapping part rather than duplicating it.

## 4. Draft the entry

Keep it short. Include:

- **Motivation** — the symptom, concrete, one or two sentences.
- **What the user would do** — the actual command(s)/option(s), e.g.
  `logctl error *.infinispan`.
- **Cost / dependencies** — is it a clarification of an existing contract, new
  surface, or blocked on another feature? Which layer (§17)?
- **Open design questions** — list them and mark them **deferred to when this is
  specced**. Do not resolve them in the capture. If a question is really "which
  of two behaviours", name both and stop there.

## 5. Confirm before writing

Use `AskUserQuestion` for the genuine decisions: **placement** (from the table
above) and **how firmly to frame** any deferred design question. Don't ask about
things with an obvious answer — just state them and proceed.

## 6. Apply

Make the edits: the new §18.x / §18.6 bullet / milestone note, plus any
deferred-scope or clarification notes in affected `doc/specs/*.md` files.
Cross-reference between them (`top-level §18.7`, `see doc/specs/cli-transport.md`).

## 7. Committing

Per CLAUDE.md, a spec change lands with the code it describes — but a pure
roadmap capture has **no code yet**, so this is a spec-only commit and that's
expected. Follow the repo's normal branch rules; **don't commit or push unless
asked**. If asked to commit, use a `Roadmap:` subject line summarising the idea
and a body with the motivation and the placement rationale.
