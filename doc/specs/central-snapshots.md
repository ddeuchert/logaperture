# Publishing PR snapshots to Maven Central (issue #248)

Status: **agreed 2026-10-20** (D1–D8, as recommended). Implementation follows.
Parent spec: [`maven-central-publishing.md`](maven-central-publishing.md) (the release flow this
sits beside; it is not changed). Related: [`distribution-bundle.md`](distribution-bundle.md).

## Functional summary

After this feature, the user will be able to:

- (Maintainer) Add the label `deploy snapshot` to a pull request and have its build published as a
  `-SNAPSHOT` to Maven Central's snapshot repository, with no push-by-push publishing.
- (Tester) Fetch the agent, `logctl` or the zip of that unreleased build from the snapshot
  repository, for example to try a fix before the next beta.
- (Maintainer) See in the pull request's checks which commit was published and where to fetch it.

Guide pages changed: [Download](../../guide/get-started/download.md) (a short "Try an unreleased
build" section, D8). For the maintainer, [`DEVELOPMENT.md`](../../DEVELOPMENT.md) gets a
"Publishing a snapshot" paragraph next to "Cutting a release".

## Why

A tester can only get a fix after a beta is cut. Central's snapshot repository takes `-SNAPSHOT`
versions with no validation, no signing and no review, replaces the same version on each upload,
and removes them after 90 days. That fits "a build for people I've asked to test", and publishing
only a labeled PR keeps `develop`'s every push from becoming public.

## How it works

A new workflow, `.github/workflows/snapshot.yml`, in the style of `central.yml`:

1. **Trigger (D1).** `pull_request` of type `labeled`, run only for the label `deploy snapshot`,
   and only when the PR's head is in this repository (D5). The job checks out the PR's head commit
   (not the merge with the base), so what is published is the commit CI tested.
2. **Guards (D5).** The POM version must end in `-SNAPSHOT` (a release version is refused, so this
   path can never publish a release), and the PR's `build` check must have succeeded for the
   commit being built.
3. **Build (D3).** `./mvnw -B -Prelease -pl logaperture-dist -am -DskipTests -Dgpg.skip=true deploy`
   with `-DaltDeploymentRepository=central-snapshots::https://central.sonatype.com/repository/maven-snapshots/`
   and the Central Portal user token as the server credentials. The same four artifacts as a
   release go up (parent POM, agent, `logctl`, the zip), with sources and javadoc jars, unsigned.
   The build uses `-Dproject.build.outputTimestamp` set to the built commit's time, as the release
   build does (issue #207).
4. **Report (D6).** The job summary names the PR, the commit, the version and the repository
   paths, and the workflow comments nothing on the PR.

The workflow shares the `central` concurrency group with the dry run and the release, so two of
them never run at once.

Central's `maven-snapshots` repository stores each upload under a timestamped file name and keeps
`maven-metadata.xml` pointing at the newest, so a client asking for `1.0.0-beta.3-SNAPSHOT` gets
the latest labeled build.

**Prerequisites outside the repo**, which only the first run can confirm: **Enable SNAPSHOTs** on
the `org.logaperture` namespace in the Central Portal (Namespaces → the namespace's menu), and the
existing `CENTRAL_TOKEN_USERNAME` / `CENTRAL_TOKEN_PASSWORD` token must be allowed to publish to it.
No GPG secret is needed.

## What it does not do

- It doesn't publish on a push to `develop`, on merge, or when a labeled PR gets new commits (D1).
- It doesn't touch `release.yml`, `central.yml`, the release profile or the POMs.
- It doesn't create a GitHub Release, tag, or change any version in the repository.
- It doesn't delete anything: Central removes snapshots after 90 days (D7).

## Decisions

| # | Decision | Recommended | Status |
|---|---|---|---|
| D1 | Trigger | The label `deploy snapshot` on a pull request. Only adding the label publishes; to publish again, remove it and add it back (as with `central dry run`). Pushing to the PR or to `develop` never does. | Agreed (David, 2026-10-20) |
| D2 | What version is published | **The POM version as it is** (`1.0.0-beta.3-SNAPSHOT`), so a tester always asks for the same coordinates and gets the newest labeled build. The cost is that two labeled PRs overwrite each other and the files don't say which PR they came from (the job summary does). The alternative is a per-PR version (`1.0.0-beta.3-PR247-SNAPSHOT`): no overwriting, and the PR is in the name, but the tester has to be told the coordinates each time and there is no "latest". | Agreed (recommended option, David, 2026-10-20) |
| D3 | What is published | The same four artifacts as a release, with sources and javadoc jars, **unsigned** (Central doesn't check snapshots), so the workflow needs the Central token but not the GPG key. | Agreed (David, 2026-10-20) |
| D4 | How it is deployed | The Maven Deploy Plugin with the repository given on the command line, and the token supplied as server credentials by `actions/setup-java`. No change to the POMs, so the release flow is untouched. The Central publishing plugin (0.7.0+) is the alternative; it would mean a POM change in the release path. | Agreed (David, 2026-10-20) |
| D5 | Guards | Same-repository PRs only (a fork gets no secrets anyway); refuse a version that doesn't end in `-SNAPSHOT`; require the PR's `build` check to have passed for the commit being built, so a broken build is never published. Tests aren't rerun, as in the dry run. | Agreed (David, 2026-10-20) |
| D6 | Reporting | A job summary only: PR, commit, version, the repository URL and the path to the files. No PR comment. | Agreed (David, 2026-10-20) |
| D7 | Retention | Central's: 90 days, and a new upload replaces the same version. Nothing of ours cleans up. | Agreed (David, 2026-10-20) |
| D8 | Documentation | `DEVELOPMENT.md` gets the maintainer steps; the Download page gets a short, plain "Try an unreleased build" section (the repository URL, the artifact coordinates, a `curl` for the zip, "not for production"). Leave the Download section out if you'd rather not point users at snapshots yet. | Agreed (David, 2026-10-20) |

## Tests

- The workflow can't run before it is on `main`, the default branch, for a manual start; the label
  trigger runs it from the PR's own branch, so the first real test is labeling its own PR. That run
  also confirms the prerequisites above.
- A local check of step 3 against a `file:` repository (the build, the artifact set, unsigned, the
  zip attached), as `central.sh bundle` is checked today.
- After the first run, fetch the zip back from the snapshot repository and compare it with the
  built one.
