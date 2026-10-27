# `logctl` through JBang — a catalog entry and a guide recipe

Status: **Draft, awaiting sign-off** (decisions #1 to #7 below). Release `1.1.0`.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §8.1 (the CLI), §17.1 (1.1.0 and
the distribution paragraph, which this feature reverses for JBang). Evidence:
[`doc/spikes/dynamic-attach.md`](../spikes/dynamic-attach.md) finding 7 (#219). Builds on:
[`logctl-attach.md`](logctl-attach.md) (#273, the embedded agent jar),
[`maven-central-publishing.md`](maven-central-publishing.md) (#186, the published CLI jar),
[`launcher-conf.md`](launcher-conf.md) (#238, what the shipped launcher adds around `java -jar`).
Tracking issue: [#274](https://github.com/ddeuchert/logaperture/issues/274).

## Functional summary

After this feature, the user will be able to:

- Run `logctl` with nothing installed but JBang: `jbang logctl@ddeuchert/logaperture status`.
- Try LogAperture on a running JVM in two commands, with no download and no restart:
  `jbang logctl@ddeuchert/logaperture attach`, then any other `logctl` command.
- Run a specific release instead of the one the catalog names, with
  `jbang org.logaperture:logaperture-cli:<version> status`.
- Read in the guide how to set this up, and what a JBang-run `logctl` does differently from the
  installed one.

Guide pages this changes: `how-to/run-with-jbang.md` (new, added to the `mkdocs.yml` nav);
`get-started/download.md` and `how-to/try-without-restart.md` (a pointer to it); the changelog.
`reference/logctl.md` does not change: no command or option is added.

Not part of this feature: SDKMAN and Homebrew (#275, #276); running the agent through JBang
(`-javaagent` needs a file path, and `logctl attach` already supplies one); a JBang script that
wraps the launcher's `logctl.conf` settings.

## What is already established

- `jbang org.logaperture:logaperture-cli:<version>` resolves the published CLI from Maven Central
  and runs it, in about 1.5 s warm (spike #219, finding 7). The shaded jar's `Main-Class` is all
  JBang needs, and the published POM is the shade plugin's dependency-reduced one, so nothing else
  is fetched.
- The CLI jar carries the agent jar since #273, so `logctl attach` under JBang needs no second
  artifact.
- JBang runs on the JDK it finds, so `jdk.attach` is present on a JDK install. A JRE-only machine
  cannot attach, with or without JBang, and `logctl attach` already says so.
- The default branch is `main`, which moves only at a release. A catalog file there names a
  released version by construction.

## Design

A `jbang-catalog.json` at the repository root, with one alias:

```json
{
  "aliases": {
    "logctl": {
      "script-ref": "org.logaperture:logaperture-cli:<release>",
      "description": "LogAperture: change log levels in a running JVM"
    }
  }
}
```

JBang finds that file for `<alias>@ddeuchert/logaperture`, which is why the alias is `logctl`.
The guide recipe is a new how-to, `how-to/run-with-jbang.md`: install JBang, the one-line run,
the `attach` pair, pinning a version, and the differences from the zip (no `logctl.conf`, so
`JAVA_OPTS` and `LOGCTL_JAR_HOME` do not apply; the extracted agent jar still lands in
`~/.logaperture/agent/`).

## Decisions

| # | Question | Options | Recommendation |
|---|---|---|---|
| #1 | Where the catalog lives | (a) `jbang-catalog.json` in this repository; (b) a separate `ddeuchert/jbang-catalog` repository, reached as `logctl@ddeuchert` | **(a)**. One repository to release, and `logctl@ddeuchert/logaperture` names the project. (b) would need a second repository kept in step at every release. |
| #2 | Alias names | (a) `logctl` only; (b) `logctl` plus an alias for the agent jar | **(a)**. The agent is not something a user runs, and `attach` already supplies it. |
| #3 | How the alias tracks releases | (a) pinned to an exact version, bumped in the release process; (b) a Maven version range or `RELEASE`/`LATEST`; (c) pinned to the newest 1.x patch by hand | **(a)**. A run is reproducible and matches the versioning policy (any same-major pair works, so a user who needs another version names it). The file on `main` changes only with a release, so the release checklist gets one step. (b) depends on JBang's range support, which the spec does not rely on until it is checked. |
| #4 | What lands before 1.1.0 is published | (a) commit the file naming `1.1.0` now, verify it end to end at the release cut; (b) hold the file until the cut | **(a)**. `main` is where JBang reads it and `main` does not move before the release, so a dangling version is not visible to anyone. The first real check is at the cut, and the release checklist says so. |
| #5 | Guarding the file | (a) a unit test that the file parses, has the `logctl` alias, and names `org.logaperture:logaperture-cli`; (b) also compare its version with the project version | **(a)**. (b) cannot hold on `develop`, where the project version is a snapshot, and the version is checked for real at the cut. |
| #6 | The guide recipe's tone about attach | (a) lead with `attach`: "try it in two commands"; (b) lead with `status`, and mention `attach` second | **(a)**. It is the reason JBang is worth having now (spec §17.1): the whole trial needs no install and no restart. The page repeats the JEP 451 warning and the JDK requirement. |
| #7 | JBang is not installed on the development machine | (a) install it in a scratch directory for the verification, as the spike did; (b) skip real verification until the cut | **(a)**, with the built jar installed to the local Maven repository so the alias can be checked against `mavenLocal` before 1.1.0 exists. The cut check against Central still happens. |

## Testing

- A unit test for the catalog file (#5).
- A real run in a scratch directory with JBang (#7): `logctl@…` against a local catalog file and
  the locally installed CLI, `status` against a plain Logback JVM, then `attach` and `env`. The
  captured output goes into the guide page.
- At the release cut: the same two commands against the published artifacts, from a machine that
  has never built the project.
