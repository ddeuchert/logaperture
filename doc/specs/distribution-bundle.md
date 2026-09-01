# Distribution bundle — the hand-off evaluation zip

Status: Draft, implemented alongside this spec.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §4.6 (module
layout), §19 (first deliverables). Related: [`doc/specs/wildfly-support.md`](wildfly-support.md)
("Installation mechanics", "Never touch standalone.xml") — the source for the
install doc's WildFly steps.

## Functional summary

After this feature:

- A maintainer runs `mvn package` (or `mvn -pl logaperture-dist -am package`) and
  gets **`logaperture-dist/target/logaperture-<version>.zip`** — a single file to
  hand to an interested party.
- The zip contains the LogAperture **agent jar**, the **`logctl`** CLI (its jar
  plus `logctl` / `logctl.cmd` launchers), a **WildFly install guide**, and the
  Apache-2.0 `LICENSE`.
- A recipient unzips it on a standalone WildFly host, adds **one `-javaagent`
  line** to `standalone.conf`, restarts once, and controls log levels with
  `bin/logctl`.
- The bundle's `README.md` and install guide both state, prominently, that this
  is **pre-production / evaluation use only**.

Not in scope here: version labelling (the zip name follows `${project.version}`,
so it is `logaperture-0.1.0-SNAPSHOT.zip` until versioning is done separately),
branch protection, Maven Central publishing, and GitHub Release automation.

## The module

`logaperture-dist`, `packaging` `pom`, added **last** in the root `<modules>`.
It is not a product artifact — it exists only to assemble the zip.

- **Dependencies:** `logaperture-agent` and `logaperture-cli` at
  `${project.version}` — **for reactor build ordering only**, so `mvn package`
  builds both shaded jars before this module runs. The assembly does *not* pull
  them as dependencies (see below).
- **Build:** `maven-assembly-plugin` (version managed in the root
  `pluginManagement`), `single` goal bound to `package`, driven by
  `src/assembly/dist.xml`, with `<finalName>logaperture-${project.version}</finalName>`,
  `<appendAssemblyId>false</appendAssemblyId>`, and `<attach>false</attach>`. The
  zip is a build output to hand out, not a Maven artifact — `attach=false` keeps
  `mvn install` from putting it in the local repo next to the module's `.pom`
  (and, on older plugin versions, from replacing the `.pom` because
  `appendAssemblyId` is false). Maven Central / `deploy` is deferred.

## The assembly — `src/assembly/dist.xml`

- Format `zip`; `includeBaseDirectory` true, so everything lives under
  `logaperture-<version>/`.
- **The shaded jars go in by path**, not as a `dependencySet`. The shade plugin's
  custom `<finalName>` writes `logaperture-agent/target/logaperture-agent.jar`
  but leaves the Maven artifact pointing at the thin `maven-jar-plugin` output,
  so a `dependencySet` would bundle the wrong (thin) jar. Two `<file>` entries
  copy `../logaperture-agent/target/logaperture-agent.jar` and
  `../logaperture-cli/target/logaperture-cli.jar` into `lib/` under fixed names —
  the same path-based approach `logaperture-it` uses to reference the agent jar.
- **`fileSet`s** copy `logaperture-cli/bin/logctl` (mode 0755, `unix` line
  endings) and `logctl.cmd` (`dos` line endings) into `bin/`, and the bundle
  `README.md` to the top.
- **`file`s** also place the repo-root `LICENSE` at the top and
  `INSTALL-wildfly.md` into `docs/`.

### Zip contract

```
logaperture-<version>/
  README.md                 what it is, PRE-PRODUCTION ONLY, pointer to docs/
  LICENSE                   Apache-2.0
  bin/logctl                POSIX launcher (0755)
  bin/logctl.cmd            Windows launcher
  lib/logaperture-agent.jar the -javaagent
  lib/logaperture-cli.jar   logctl's jar
  docs/INSTALL-wildfly.md   install / verify / use / uninstall / limits
```

The version is carried by the archive name, each jar's `Implementation-Version`
manifest entry (already set from `${project.version}` by the shade config), and
`README.md`'s prose. No build-time filtering of the doc files.

## Launcher change

`logaperture-cli/bin/logctl` and `logctl.cmd` resolved the CLI jar from
`../target/` only. They now try `../lib/logaperture-cli.jar` (distribution
layout) first, then `../target/logaperture-cli.jar` (source-tree layout), and
keep the existing "not found" error. This is the only change outside the new
module; it lets the same committed launchers work in both layouts.

## The install guide — `INSTALL-wildfly.md`

Content (see the file for the full text): a **pre-production only** banner;
prerequisites (standalone WildFly 26.1.3.Final tested, JDK 17, same host + OS
user for `logctl`); the one-line `standalone.conf` / `standalone.conf.bat` edit
and a verify step; a `logctl` command + tier reference; the handler-floor
caveat; uninstall (remove the line, restart); this build's limits (standalone
only, level control only); a feedback URL. The WildFly mechanics mirror
`wildfly-support.md` and `WildFlyContainerIntegration.guidance()`.

## Testing

The assembly is exercised by the build itself (`mvn package` fails if a listed
path is missing). Manual verification, per the plan:

- `unzip -l` shows exactly the contract above under a single
  `logaperture-<version>/` base dir; `bin/logctl` is mode 0755.
- An extracted `bin/logctl --help` runs (resolves `../lib/...`); the in-tree
  `logaperture-cli/bin/logctl --help` still runs (falls back to `../target/...`).
- `logaperture-agent.jar`'s manifest has `Premain-Class` and the project version.
- Full `mvn verify` stays green with `logaperture-dist` built last.

## Exit criterion

`mvn package` produces `logaperture-dist/target/logaperture-<version>.zip` with
the contract above; an extracted copy's `bin/logctl` works against a running
WildFly that has `lib/logaperture-agent.jar` on `-javaagent`; `README.md` and
`INSTALL-wildfly.md` both carry the pre-production-only notice.
