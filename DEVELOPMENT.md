# Developing LogAperture

How to build, test, run, and debug this project. For *what* the project is and
*why*, see the [README](README.md) and the [design spec](doc/logaperture-spec.md).
For contribution policy — the spec-driven workflow, gitflow branching, copyright
headers — see [CLAUDE.md](CLAUDE.md); it is written for agents but the rules
apply to everyone.

## Prerequisites

| Tool | For |
|---|---|
| **JDK 17** | the whole build (`maven.compiler.release` is pinned to 17); `logctl` needs a JDK, not a JRE (`com.sun.tools.attach`) |
| **Maven 3.9+** | `mvn` on your `PATH` (no wrapper is committed) |
| **Docker** | the real-WildFly integration test, and the `dev/wildfly` environment |
| **Python 3** | `dev/wildfly/wildflyctl.py` (standard library only — no venv) |

## Build and test

```sh
mvn verify        # full reactor: compile, unit tests, integration tests
```

- The reactor is a plain multi-module build; Maven computes build order from the
  dependency graph.
- **`logaperture-it`** holds `WildFlyContainerIT`, which boots a real WildFly via
  Testcontainers. It self-skips when Docker is unavailable
  (`@Testcontainers(disabledWithoutDocker = true)`), so `mvn verify` stays green
  without Docker; CI runs it on an ubuntu runner.
- Work on one module: `mvn -pl <module> -am test` (`-am` also builds its
  dependencies).

## Repository layout

The module list and its rationale are in
[doc/logaperture-spec.md](doc/logaperture-spec.md) §4.6. In short:

- `logaperture-api`, `logaperture-core` — the model and the level-control engine.
- `logaperture-adapter-logback`, `logaperture-adapter-jul` — per-backend
  `LoggingAdapter`s (JUL covers JBoss LogManager and plain `java.util.logging`).
- `logaperture-container-none`, `logaperture-container-wildfly` — per-environment
  `ContainerIntegration`s (detect, discover contexts, wire lifecycle).
- `logaperture-control-jmx` — the `LevelControlMXBean` surface.
- `logaperture-agent` — the `premain` / `agentmain` entry points.
- `logaperture-cli` — `logctl`.
- `logaperture-it` — real-server integration tests.
- `logaperture-sample-war` — **not part of the product**; a deploy target for
  hands-on WildFly testing (see below).

## Controlling a running JVM with `logctl`

`logctl` attaches to a JVM that has the agent loaded and changes levels live —
no restart, nothing written to any config file.

```sh
mvn -q -pl logaperture-cli -am package -DskipTests     # build the CLI jar

logaperture-cli/bin/logctl levels [filter]             # list loggers + effective levels
logaperture-cli/bin/logctl status                      # active overrides
logaperture-cli/bin/logctl debug <logger> [tier]       # also: trace | info | warn | error
logaperture-cli/bin/logctl set   <logger> <level> [tier]
logaperture-cli/bin/logctl reset <logger>              # or: reset --all
```

`tier` is `session` (until the JVM exits), `for <n>s|m|h|d` (auto-reverts), or
`sticky` (survives a restart). Omit it and you get `for 4h`. Options:
`--pid <n>` (when discovery is ambiguous), `--reason "<text>"`,
`--include-children`, `--json`.

`logctl` finds the target JVM on its own when exactly one is running with the
agent attached — it filters on the `logaperture.version` system property the
agent sets once installed. It works only for a JVM you could already attach a
debugger to; authorization is the OS's.

## Local iteration without a container

For agent-bootstrap logic that isn't WildFly-specific (`AgentBootstrap`, the
`none` integration, `JmxRegistrar`), the fastest loop is the **Launch
FixtureApp** config in [.vscode/launch.json](.vscode/launch.json).

`FixtureApp`
(`logaperture-agent/src/test/java/org/logaperture/agent/it/FixtureApp.java`) is a
plain `java -jar`-style SLF4J/Logback app with a known baseline
(`logback-test.xml`: `ROOT` at `INFO`). It logs one line, prints `FIXTURE-READY`,
then blocks on stdin until you press Enter.

1. In the config's `vmArgs`, add:
   `-javaagent:${workspaceFolder}/logaperture-agent/target/logaperture-agent.jar -Dlogaperture.home=${workspaceFolder}/logaperture-agent/target/logaperture-home`
2. Run it (Debug if you also want to breakpoint the agent — `request: launch`
   runs under the debugger from the first instruction, so a `premain` breakpoint
   binds before `premain` runs; no suspend dance needed).
3. Drive it from a terminal: `logctl levels org.logaperture.agent.it.fixture`,
   `logctl debug org.logaperture.agent.it.fixture.Worker`, etc. `FixtureApp` only
   logs once, so confirm changes with `logctl levels` / `status`, not console
   output.

---

# WildFly integration

`dev/wildfly/` is a committed, VSCode-integrated environment for deploying an
app into a real standalone WildFly with the agent attached, driving `logctl`
against it, and attaching a debugger to the agent. It is the interactive
counterpart to the automated `WildFlyContainerIT`. Full design:
[doc/specs/wildfly-dev-environment.md](doc/specs/wildfly-dev-environment.md);
quickstart: [dev/wildfly/README.md](dev/wildfly/README.md).

**It is not wired into CI.** Automated WildFly coverage is `WildFlyContainerIT`
under `mvn verify`; nothing under `dev/wildfly/` runs in any workflow.

## What it is

A `docker compose` service running the **same pinned image the integration test
uses** — `quay.io/wildfly/wildfly:26.1.3.Final-jdk17`, from the `wildfly.image`
property in the root `pom.xml`. The freshly-built agent and `logctl` jars and a
host `deployments/` directory are bind-mounted in. Ports: `8080` (app), `9990`
(management), `8787` (JDWP).

`dev/wildfly/.env` carries `WILDFLY_IMAGE`; keep it equal to the pom property
(`wildflyctl up` / `status` warn, non-fatally, on drift).

### Docker access

`wildflyctl` shells out to `docker`. On Linux your user must be in the `docker`
group **and your login session must post-date being added to it** — otherwise
every call fails with `permission denied ... /var/run/docker.sock` even though
`getent group docker` lists you. Fix: log out and back in (then relaunch
VSCode). Stopgap for a single terminal: `newgrp docker`, or
`sg docker -c '<command>'`.

## `wildflyctl`

Run from the repo root; the VSCode **WildFly: …** tasks wrap each subcommand.

| Command | |
|---|---|
| `up [--debug-suspend] [--sweep-seconds N] [--build]` | start; builds the agent/CLI jars if missing |
| `down` | stop and remove the container |
| `restart-agent` | rebuild `logaperture-agent`, recreate WildFly (normal mode) to pick it up |
| `deploy [PATH]` | deploy a WAR/EAR (default: the sample); waits for `.deployed` |
| `undeploy [NAME]` | undeploy (default: the sample) |
| `logctl -- ARG…` | run `logctl` inside the container |
| `tail [--lines N]` | follow the container's stdout/stderr (`docker compose logs -f`) — includes the agent's `[logaperture] …` diagnostics |
| `status` | container state + `logctl status` + image-drift check |

## The sample app

`logaperture-sample-war` deploys at context path `/logaperture-sample-war`:

| Method · path | Effect |
|---|---|
| `GET /log` | one burst: TRACE→ERROR via `java.util.logging`, SLF4J, and Log4j |
| `POST /timer/start?periodMs=3000` | a background thread does the same every `periodMs` (500–60000); `&restart=true` replaces a running timer |
| `POST /timer/stop` | stop it |
| `GET /timer/status` | running?, period, tick count |

Every logger is named `org.logaperture.sample.work.Worker`, so one `logctl`
override hits all three frameworks. Fire the endpoints with `curl`, a browser
(the `GET`s), or — with the `humao.rest-client` extension —
[dev/wildfly/sample.http](dev/wildfly/sample.http) ("Send Request" codelens).

End-to-end check that an override changes what reaches the log:

```sh
python3 dev/wildfly/wildflyctl.py up
python3 dev/wildfly/wildflyctl.py deploy
python3 dev/wildfly/wildflyctl.py tail &        # stream the log
curl -X POST 'http://localhost:8080/logaperture-sample-war/timer/start?periodMs=2000'
python3 dev/wildfly/wildflyctl.py logctl -- debug org.logaperture.sample.work.Worker for 2m
#   DEBUG/TRACE lines now appear in the tail; after 2m the verification sweep reverts it
python3 dev/wildfly/wildflyctl.py logctl -- status
curl -X POST http://localhost:8080/logaperture-sample-war/timer/stop
```

## Debugging the agent inside WildFly

The container publishes JDWP on **8787**. Two attach configs in
[.vscode/launch.json](.vscode/launch.json):

- **Attach to WildFly (8787)** — for breakpoints in code the agent runs *after*
  boot (the verification sweep, adapter operations). Carries
  `preLaunchTask: "WildFly: up"` (a no-op if already running).
- **Attach to WildFly — premain (8787)** — for stepping through agent
  initialization. **No** pre-launch task. Use it after the
  **WildFly: up (debug-suspend)** task.

### How agent init is structured — two phases, two threads

| Phase | Thread | Path |
|---|---|---|
| **A — premain** | `main` | `LogApertureAgent.premain` → `AgentBootstrap.start` → `WildFlyContainerIntegration.detect()` → `.activate()`, which constructs `WildFlyContainer`, starts the `logaperture-wildfly-detect` daemon, and **returns immediately** |
| **B — install** | `logaperture-wildfly-detect` | `WildFlyLogManagerReadiness.awaitJBossLogManagerThen` polls the `java.util.logging.manager` property (a side channel, never JUL itself); once it reads `org.jboss.logmanager.LogManager` → `JulAdapterFactory.forCurrentContext()` → `installContext` → `wireConfigurationListener` → `JmxRegistrar.register` → sets `-Dlogaperture.version` |

Between the two, WildFly's own boot runs (~1s) until jboss-modules installs
JBoss LogManager. The premain gotcha (spec §15.6) is that phase B must not touch
`java.util.logging` before then.

### The `-agentlib:jdwp` ordering gotcha

To breakpoint phase A you must catch `premain` before it runs, which needs
`suspend=y`. But **`-agentlib:jdwp` has to come before `-javaagent` on the
command line.** VM_INIT callbacks fire in agent load order, and `libinstrument`
invokes the agent's `premain` from *its* VM_INIT callback. With `-javaagent`
first (the intuitive order), `premain` runs to completion before jdwp's
`suspend=y` takes hold — the debugger attaches too late and the breakpoint never
binds. `dev/wildfly/docker-compose.yml` puts jdwp first for exactly this reason.

Symptom of getting it wrong: the container log shows a `premain` stack trace
*followed by* `Listening for transport dt_socket at address: 8787`. Correct: the
log shows *only* `Listening for transport …` and the container sits at
`health: starting`.

### Procedure

1. **WildFly: down**
2. Set your breakpoint (e.g. `AgentBootstrap.start`) — **before** attaching; the
   debugger pushes breakpoints during the attach handshake, then resumes.
3. **WildFly: up (debug-suspend)** — waits until it prints "the JVM is paused
   before premain", then returns. `docker logs logaperture-wildfly` shows only
   `Listening for transport …`.
4. **Attach to WildFly — premain (8787)** → press Continue. `premain` runs, the
   breakpoint hits.

Phase B has a **60-second readiness budget** (only counted while the VM is
running, not while paused at a breakpoint). Fine for stepping phase A; if you
step slowly through phase B, resume fully before the budget elapses or level
control won't install for that session.

For post-boot code, skip all of the above: **Attach to WildFly (8787)** on a
normally-booted server, breakpoint e.g. `WildFlyContainer.sweepTick` (runs every
`logaperture.sweep.seconds`, default 5 in this environment).

## How the container boot line is assembled

The image ignores `JAVA_OPTS_APPEND`, and a wholesale `JAVA_OPTS` wipes its
`--add-opens`/`--add-exports`. So the compose command appends one line to
`standalone.conf` before the normal boot (same technique as `WildFlyContainerIT`):

```
-agentlib:jdwp=…,suspend=${DEBUG_SUSPEND:-n},address=*:8787   # jdwp first (see above)
-javaagent:/opt/logaperture-agent.jar
-Dlogaperture.sweep.seconds=${LOGAPERTURE_SWEEP_SECONDS:-5}
-Dlogaperture.home=/opt/jboss/wildfly/standalone/tmp/logaperture
```

`-Dlogaperture.home` is required because the image's `$HOME` (`/opt/jboss`) is
not writable by the `jboss` user; without it the agent's state store fails with
`AccessDeniedException` and degrades to session-only persistence.

## Troubleshooting

| Symptom | Fix |
|---|---|
| `permission denied … /var/run/docker.sock` | not in the `docker` group in this session — log out/in, or `sg docker -c` (see *Docker access*) |
| premain breakpoint never hits | attached before setting it, or used **Attach to WildFly (8787)** instead of the **— premain** config, or a stale image — `down`, set breakpoint, `up --debug-suspend`, attach with **— premain** |
| breakpoint hollow / "unverified" | stale agent jar — `mvn -pl logaperture-agent,logaperture-cli -am package`, then **WildFly: rebuild agent + restart** |
| `logctl` says "no LogAperture-enabled JVM" / "ambiguous" | pass `--pid <n>`; inside the container use `wildflyctl logctl -- …` |
| WildFly image differs from CI | `.env` `WILDFLY_IMAGE` drifted from the pom `wildfly.image` — match them |
