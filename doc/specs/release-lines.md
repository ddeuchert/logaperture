# Release lines: `release/1.0` and a 1.1.0 `develop` (issue #257)

Status: **signed off 2026-10-24** (R-a to R-e agreed as proposed; R-f added and agreed the same day).
Parent spec: [`logaperture-spec.md`](../logaperture-spec.md) §11.1 (versioning policy) and
[`DEVELOPMENT.md`](../../DEVELOPMENT.md) "Branches and releases" (the process this extends).

## Functional summary

This is a process change, not a product change. After it:

- Work on 1.1.0 (Quarkus, dynamic attach, …) can start on `develop` while 1.0.0 is still in its
  beta and release cycle.
- A fix for 1.0.0 or a later 1.0.x patch goes to a `release/1.0` branch, from which 1.0.0 and
  every 1.0.x release is cut.
- Every fix made on `release/1.0` reaches 1.1.0 by merging the branch into `develop`.
- Release an alpha or beta of 1.1.0 from `develop` while 1.0 is still shipping, without moving
  `main` or the guide's default ("latest") page off the 1.0 line.
- A contributor knows which branch a change belongs on, and which one a pull request targets,
  from the issue's milestone.
- The held Quarkus pull request (#225) can merge to `develop` once the line is cut.

No guide page changes: no command, option, output, default or file format changes.

## Why

`develop` is both the integration trunk and, until 1.0.0 ships, the 1.0 line. That is why the
Quarkus PR is held (`hold for 1.1.0`, #225): merging it would put 1.1.0 work into the next 1.0
release. The cost of splitting is lowest now — `develop` is two commits ahead of `main` (the
`beta.4-SNAPSHOT` bump and a roadmap note) — and grows with every commit that lands first.

## Design

### R1. Two long-lived lines

| Branch | Carries | Version while open |
|---|---|---|
| `release/1.0` | 1.0.0 GA and 1.0.x patches. Fixes only; no enhancements. | `1.0.0-beta.4-SNAPSHOT` until 1.0.0 is cut, then `1.0.1-SNAPSHOT` |
| `develop` | 1.1.0 and later. The default target for new work. | `1.1.0-SNAPSHOT` |

A release line is named `release/<major>.<minor>`. When 1.1.0 ships, `release/1.1` is cut from
`develop` the same way, if a 1.1.x patch line is needed; this spec does not require it.

`release/1.0` is cut from the current `develop` tip, then `develop` is bumped to
`1.1.0-SNAPSHOT` in its own commit. The `beta.4` in the interim version is a placeholder (the
next release the branch *would* produce), not a commitment to a beta.4: if beta.3 is final, the
1.0.0 release commit changes the version to `1.0.0`, as the earlier release commits dropped
`-SNAPSHOT`.

### R2. Which branch a change targets

- A change that must ship in 1.0.0 or a 1.0.x patch (milestone `1.0.0`, `1.0.1`, …) branches from
  `release/1.0` and its pull request uses `--base release/1.0`.
- Everything else (milestones `1.1.0`, `1.2.0`, `1.x`) branches from `develop` with
  `--base develop`, as today.

Branch names are unchanged: `feature/<issue>-<slug>`. The issue's milestone decides the line.

### R3. Fixes reach `develop` by merging the release line forward

Agreed 2026-10-24: `release/1.0` is merged into `develop` with a **merge commit** (a pull request
`release/1.0` → `develop`, merged with `gh pr merge --merge`), not cherry-picked. A missed fix is
worse than a repeated merge, and a merge keeps one history for the one change.

- Open the forward-merge pull request whenever `release/1.0` has commits `develop` lacks, and
  always before cutting a release from either line.
- **`pom.xml` versions.** The two lines differ in every module's version. Git merges from the
  branch point, so while `release/1.0` leaves the version lines alone, an ordinary fix merges
  without conflict. A conflict arises only when `release/1.0` itself changes the version — the
  release commit (`1.0.0`) and the bump to `1.0.1-SNAPSHOT` after it. Resolve those by keeping
  `develop`'s version (`1.1.0-SNAPSHOT`) in every `pom.xml`.
- The same applies to other release-time edits (`CHANGELOG.md` release heading, README, guide
  examples at the release version): keep `develop`'s side for anything version-stamped, keep both
  sides' content for the changelog entries.

### R4. Releases and `main`

`main` still advances only by a pull request merged with a merge commit, one per release, with
the same tree as the tagged release commit. The head of that pull request is now **`develop` or a
`release/*` branch** of this repository (never a fork):

- 1.0.0: `gh pr create --base main --head release/1.0`, merge with a merge commit, tag
  `v1.0.0` on the release commit on `release/1.0`. 1.0.x patches the same way.
- 1.1.0: from `develop`, as today.

**Pre-releases of a later minor.** `main` moves only for releases of the current line (above).
A pre-release of a later minor — `1.1.0-alpha.1` while 1.0 is still shipping — is tagged on
`develop` and does **not** get a pull request into `main`: set the version in a release commit on
`develop`, run the Central dry run on `develop`, push the tag, then bump back to `1.1.0-SNAPSHOT`.
`release.yml` needs no change (it checks only the tag against the POM, and marks `-alpha`, `-beta`
and `-rc` as GitHub pre-releases). Merge `release/1.0` into `develop` first, so the pre-release
carries the latest 1.0 fixes. When 1.1.0 itself ships it goes `develop` → `main` like any other
release, and `main` then shows 1.1.0. So `main` matches the most recent release **of the current
line**; the tag and the GitHub Release remain the record of a pre-release that never visited it.

After a release, bump the *releasing branch* to its next `-SNAPSHOT` (`release/1.0` →
`1.0.1-SNAPSHOT`), then forward-merge (R3). Merge `release/1.0` into `develop` before the 1.1.0
cut, so `main`'s release merges share history.

`.github/workflows/release-pr-source.yml` (`from-develop`) changes to accept those heads; the
ruleset on `main` needs no change, since it requires the check by name. The check keeps its name
so the ruleset still matches it.

### R5. CI, docs, snapshots

- **CI** (`ci.yml`) also runs on pushes to `release/**`. Pull requests already run for any base.
- **Docs** (`docs.yml`): `develop` keeps publishing the guide as `dev` (now the 1.1 docs). A tag
  publishes its minor (`1.0`) as today, and moves the `latest` alias and the site's default page
  only if the tagged commit is on `main` **and** its minor is not older than the current
  `latest`. A pre-release tagged on `develop` (`1.1.0-alpha.1`) therefore deploys as `1.1`, with
  the pre-release banner, and leaves `latest` on 1.0; a late 1.0.x patch after 1.1.0 shipped
  leaves `latest` on 1.1. A guide-only fix on `release/1.0` after
  GA is published with the existing `workflow_dispatch` (version `1.0`); no new automatic trigger.
- **Snapshots** (`snapshot.yml`) and the **Central dry run** (`central.yml`) work from any base
  and publish the POM's own `-SNAPSHOT` version, so a `release/1.0` pull request publishes
  `1.0.0-beta.4-SNAPSHOT` and a `develop` one `1.1.0-SNAPSHOT`. No change.
- **Release** (`release.yml`) builds the tag and checks the POM matches it, so it is unaffected;
  its header comment ("from `main`") is corrected.

### R6. Protecting the release lines

A repository ruleset on `release/**` blocks deletion and force-pushes, so a release line can't be
lost or rewritten. It does not require pull requests or checks (matching `develop`, which has no
ruleset today).

### R7. Tracking: Status and the `on develop` label

With two lines, "On develop" is wrong for a fix merged to `release/1.0`. The Project's **Status**
value **On develop** is renamed **Merged**: code complete and merged to its line, shipping in the
release its milestone names. The `on develop` label is retired at the cut; Status plus milestone
carry the meaning. The release-cut steps are unchanged: close the issues the release shipped and
set Status to **Released**. `CLAUDE.md` is updated to say so.

### R8. Docs this touches

`DEVELOPMENT.md` "Branches and releases" (two lines, which branch to target, forward merge,
release from either), `CLAUDE.md` (the tracking paragraph above), the PR template's checklist
(which base the PR targets), and the comment in `release.yml`. The release-cut steps in
`DEVELOPMENT.md` say "from `develop`" in several places; they become "from the line being
released".

## Cutting the line (the one-time steps)

1. Merge this PR (spec, workflow changes, docs) to `develop`.
2. Create `release/1.0` from that `develop` commit and push it; add the `release/**` ruleset.
3. On `develop`: bump all modules to `1.1.0-SNAPSHOT`.
4. Rebase #225 (Quarkus) on `develop`, drop the hold, retire the `hold for 1.1.0` label.
5. Rename the Project Status value; retire the `on develop` label.

## Decisions

All agreed 2026-10-24.

| # | Decision | Agreed |
|---|---|---|
| R-a | Version on `release/1.0` at the cut | `1.0.0-beta.4-SNAPSHOT` (placeholder; see R1). Alternative: `1.0.0-SNAPSHOT` |
| R-b | Rename Status "On develop" → "Merged" and retire the `on develop` label (R7) | Yes, as written |
| R-c | A CI check that fails when `release/1.0` has commits `develop` lacks | **Deferred**: a checklist item for now; revisit if a fix is missed. File an issue |
| R-d | A `release/**` ruleset (R6) | Yes: deletion and force-push only |
| R-e | Docs from `release/1.0` (R5) | Manual `workflow_dispatch` only |
| R-f | Pre-releases of a later minor (R4) | Tagged on `develop`, no pull request into `main`; `latest` docs move only for a tagged commit on `main` whose minor is not older than the current `latest` |
