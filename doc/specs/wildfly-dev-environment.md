# WildFly Dev Environment — VSCode-integrated deploy / run / debug

Status: Signed off (all decisions resolved to the recommended option). Not yet
implemented.
Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §12 (testing
strategy — this is the interactive/manual complement to the automated container
smoke test), §15.6 (WildFly), §14 (developer workflow — the primary use case).
Builds on: [`doc/specs/wildfly-support.md`](wildfly-support.md) — Slice 3 shipped
the agent's WildFly support and a gated Testcontainers `WildFlyContainerIT`; its
exit criterion still lists *"David's manual acceptance testing against a generic
WAR and the day-job application"* as the open item. This feature makes that
testing a committed, one-command environment any developer can run.

## Functional summary

After this feature, a developer with the repo open in VSCode will be able to:

- Bring up a real standalone WildFly with the LogAperture agent already attached,
  in one step — a `WildFly: up` task, or `wildflyctl up` in a terminal — using
  the same WildFly the CI integration test uses.
- Deploy a bundled **sample application** into it (`WildFly: deploy sample` /
  `wildflyctl deploy`), hit an HTTP endpoint that emits log lines through
  `java.util.logging`, SLF4J, and Log4j at every level, and start or stop a
  background thread that logs on a timer — via `POST /sample/timer/start` and
  `POST /sample/timer/stop` — so a `for 2m` override can be watched expiring and
  re-applying without repeatedly poking an endpoint.
- Deploy their own WAR (or the day-job application) the same way, by pointing the
  deploy command at its path or dropping it in `dev/wildfly/deployments/`.
- Drive `logctl` against that server without knowing the plumbing — a
  `WildFly: logctl…` task that prompts for the arguments, or
  `wildflyctl logctl -- debug org.acme.Foo for 30m`.
- Attach the VSCode Java debugger to the running server ("Attach to WildFly
  (8787)") and set breakpoints in the agent's runtime code — the verification
  sweep, the JBoss LogManager adapter, the override applier.
- Step through the agent's `premain` / LogManager-readiness path from the very
  first instruction, by starting the server with `wildflyctl up --debug-suspend`
  (or the `WildFly: up (debug-suspend)` task) so the JVM waits for the debugger
  before it runs.
- Tail the server log (`WildFly: tail server.log` / `wildflyctl tail`) and tear
  the environment down (`WildFly: down` / `wildflyctl down`).

None of this changes the agent, adapter, CLI, or container modules. The only new
compiled code is the sample application.

## Scope

**In scope:**

- A committed Docker Compose environment under `dev/wildfly/` running the pinned
  WildFly image the integration test already uses
  (`quay.io/wildfly/wildfly:26.1.3.Final-jdk17` — the `wildfly.image` property in
  the root `pom.xml`), with the freshly-built agent and `logctl` jars and a
  host `deployments/` directory bind-mounted in.
- A single stdlib-only Python 3 driver script, `dev/wildfly/wildflyctl.py`, with
  subcommands for the lifecycle (`up`, `down`, `restart-agent`, `deploy`,
  `undeploy`, `logctl`, `tail`, `status`).
- VSCode integration committed under `.vscode/`: a debugger **attach**
  configuration in `launch.json`, and a new `tasks.json` wrapping the driver
  subcommands (including a prompted `logctl` task).
- A new reactor module `logaperture-sample-war` — a plain-servlet Jakarta EE 8
  WAR that exercises multi-framework logging and a REST-toggled background timer.
- Docs: a `dev/wildfly/README.md`, and pointers added to the top-level spec
  (§15.6 and §12).

**Out of scope:**

- Any change to CI. The automated coverage is `WildFlyContainerIT`, which already
  runs on the ubuntu runner; this environment is a local developer aid and is
  not wired into any workflow.
- Domain mode, non-WildFly targets (Tomcat, Spring Boot, Quarkus — later specs),
  and any JMX-over-a-published-port control plane. The agent's control plane is
  local-attach only; `logctl` runs inside the container, exactly as the IT does
  it (`-Dcom.sun.management.jmxremote.port` at launch breaks WildFly boot — a
  shakeout finding recorded in `wildfly-support.md` Slice 3).
- Per-deployment `LogContext` isolation (`use-deployment-logging-config`,
  `<logging-profile>`) — the agent doesn't support it yet, so the sample app
  doesn't use it.
- A local-install / `JBOSS_HOME`-discovery path. Considered and declined during
  sign-off: every developer already needs Docker for `mvn verify`, and debugging
  against the exact CI image keeps "works when I step through it" and "passes in
  CI" the same environment. A local WildFly may be added later behind an env var
  if a concrete need appears.

## The Compose environment — `dev/wildfly/`

```
dev/wildfly/
  docker-compose.yml
  .env                     # WILDFLY_IMAGE=…  (kept in sync with pom wildfly.image)
  wildflyctl.py            # stdlib-only driver
  sample.http              # REST Client requests for the sample app
  deployments/
    .gitkeep               # dir tracked; *.war contents ignored
  README.md
```

### `docker-compose.yml`

One service, `wildfly`:

- **Image:** `image: ${WILDFLY_IMAGE:?set in dev/wildfly/.env}`.
- **Mounts:**
  - `../../logaperture-agent/target/logaperture-agent.jar` →
    `/opt/logaperture-agent.jar` (read-only)
  - `../../logaperture-cli/target/logaperture-cli.jar` → `/opt/logctl.jar`
    (read-only)
  - `./deployments` → `/opt/jboss/wildfly/standalone/deployments` (read-write)
- **Ports:** `8080:8080` (app), `9990:9990` (management), `8787:8787` (JDWP).
- **Command** — mirrors `WildFlyContainerIT`'s proven shape. The image ignores
  `JAVA_OPTS_APPEND` and setting `JAVA_OPTS` wholesale wipes the image's
  `--add-opens` / `--add-exports`, so the command appends one line to
  `standalone.conf` and then execs the normal boot:

  ```sh
  sh -c 'echo "JAVA_OPTS=\"\$JAVA_OPTS \
      -agentlib:jdwp=transport=dt_socket,server=y,suspend=${DEBUG_SUSPEND:-n},address=*:8787 \
      -javaagent:/opt/logaperture-agent.jar \
      -Dlogaperture.sweep.seconds=${LOGAPERTURE_SWEEP_SECONDS:-5} \
      -Dlogaperture.home=/opt/jboss/wildfly/standalone/tmp/logaperture\"" \
      >> "\$JBOSS_HOME/bin/standalone.conf" \
    && exec "\$JBOSS_HOME/bin/standalone.sh" -b 0.0.0.0 -bmanagement 0.0.0.0'
  ```

  Two ordering/placement details that matter:

  - **`-agentlib:jdwp` comes before `-javaagent`.** VM_INIT callbacks fire in
    agent load order, and `libinstrument` invokes the agent's `premain` from
    *its* VM_INIT callback. With jdwp first, `suspend=y` halts the VM before
    `premain` runs, so a breakpoint in `AgentBootstrap` / `premain` can be set
    before it executes. With `-javaagent` first (the intuitive order), `premain`
    has already finished by the time the debugger can attach — the breakpoint
    never binds in time.
  - **`-Dlogaperture.home`** points the agent's state store at a
    runtime-writable directory. The image's `$HOME` (`/opt/jboss`) is not
    writable by the `jboss` user, so the default `${user.home}/.logaperture`
    fails with `AccessDeniedException` and the agent degrades to session-only
    persistence (no `--sticky` survival across a restart).

- **Healthcheck:** poll `http://localhost:9990/health` (or grep `server.log` for
  `WFLYSRV0025`) so `wildflyctl status` and task ordering can wait on "ready".
- **`container_name: logaperture-wildfly`** and a fixed `project name` (via
  `wildflyctl` passing `-p logaperture`) so `exec` / `logs` targeting is stable.

`DEBUG_SUSPEND` and `LOGAPERTURE_SWEEP_SECONDS` are passed through by
`wildflyctl`, not stored in `.env` (which holds only `WILDFLY_IMAGE`).

### `wildflyctl.py`

Python 3, standard library only (`argparse`, `subprocess`, `pathlib`, `sys`,
`urllib`, `re`). No venv, no `requirements.txt`. It is a thin, readable wrapper
whose whole job is to run the right `docker compose` / `mvn` command from the
repo root with the right environment.

Subcommands:

| Command | Behaviour |
|---|---|
| `up [--debug-suspend] [--sweep-seconds N] [--build]` | Verify `logaperture-agent/target/logaperture-agent.jar` and `logaperture-cli/target/logaperture-cli.jar` exist; if missing (or `--build`), run `mvn -q -pl logaperture-agent,logaperture-cli -am package`. Then `docker compose -p logaperture up -d`, exporting `DEBUG_SUSPEND` / `LOGAPERTURE_SWEEP_SECONDS`. Print the attach hint and, with `--debug-suspend`, that the JVM is paused until the debugger connects. |
| `down` | `docker compose -p logaperture down`. |
| `restart-agent [--build]` | Rebuild the agent jar (default on), `docker compose -p logaperture restart wildfly`. |
| `deploy [PATH]` | Copy `PATH` (default: the built `logaperture-sample-war/target/*.war`) into `dev/wildfly/deployments/`; poll for the `<name>.war.deployed` marker via `docker compose exec`; fail on `<name>.war.failed`. |
| `undeploy [NAME]` | Remove the archive from `deployments/`; wait for `.undeployed`. Default `NAME` = the sample. |
| `logctl -- ARG...` | `docker compose -p logaperture exec -T wildfly java -jar /opt/logctl.jar ARG...`. The `--` separates driver args from `logctl` args. |
| `tail [--lines N]` | Follow `server.log` (`docker compose exec wildfly tail -n N -f …`). |
| `status` | Container state + `logctl status` + a `WILDFLY_IMAGE` vs pom drift check (see below). |

**Image-version drift check.** The single source of truth for the WildFly version
is the root `pom.xml` `wildfly.image` property (also consumed by
`logaperture-it`). `wildflyctl` reads it with a narrow regex — no XML
dependency — and warns (non-fatal) on `up` and `status` if `dev/wildfly/.env`
disagrees, naming both values and the one-line fix. `.env` is not
auto-regenerated: a committed value that CI can't silently change is safer than a
value that moves under the developer.

**Invocation.** Called as `python3 dev/wildfly/wildflyctl.py <cmd>` from the repo
root, and by the VSCode tasks. No shell shim is shipped (decision #2); the file
carries a `#!/usr/bin/env python3` shebang and the executable bit for anyone who
wants to `PATH`-link it.

## VSCode integration — `.vscode/`

### `launch.json` — add two attach configurations

Keep the three existing configs. Add:

- **Attach to WildFly (8787)** — `request: attach`, `localhost:8787`,
  `projectName: logaperture-agent`, `preLaunchTask: "WildFly: up"`. For
  breakpoints in code the agent runs *after* boot (the verification sweep,
  adapter operations). `up -d` on an already-running project is a no-op, so the
  `preLaunchTask` is safe to trigger every launch.
- **Attach to WildFly — premain (8787)** — the same, **without** a
  `preLaunchTask`. Used after the *WildFly: up (debug-suspend)* task: the JVM
  boots suspended and waits for this attach before running any code, so a
  breakpoint in `LogApertureAgent.premain` / `AgentBootstrap.start` /
  `WildFlyContainerIntegration` is guaranteed to be set before `premain` fires.
  The plain `up` wait is omitted here because it would never see a clean boot
  while the JVM is suspended.

Source resolution: the agent jar is a Maven build of `logaperture-agent` in the
same workspace and the shade config applies no relocations, so the Java
extension maps frames in any `org.logaperture.*` class to workspace sources with
no extra `sourcePaths`. The bind-mounted jar is byte-identical to
`logaperture-agent/target/logaperture-agent.jar`.

### `tasks.json` — new file

`shell` tasks invoking `python3 ${workspaceFolder}/dev/wildfly/wildflyctl.py …`:

- **WildFly: up** — `up`
- **WildFly: up (debug-suspend)** — `up --debug-suspend`
- **WildFly: down** — `down`
- **WildFly: rebuild agent + restart** — `restart-agent --build`
- **WildFly: deploy sample** — `deploy` (depends on a `mvn package` task for the
  sample module)
- **WildFly: tail server.log** — `tail`, `isBackground`, in a dedicated panel
- **WildFly: logctl…** — `logctl -- ${input:logctlArgs}` with a `promptString`
  input; `presentation.reveal: always` so the output shows

A compound flow for the common "start and debug" case (decision #5): the
**Attach to WildFly (8787)** launch config gets `"preLaunchTask": "WildFly: up"`
so a single "Run and Debug" both boots the server (idempotent — `up -d` on an
already-running project is a no-op) and attaches. The debug-suspend path stays a
deliberate two-step (`WildFly: up (debug-suspend)` task, then the launch config),
because there the ordering matters and should be explicit.

### `extensions.json`

Add two soft recommendations; nothing here depends on either, and there is no
`unwantedRecommendations` change:

- `ms-azuretools.vscode-docker` (decision #6) — makes the container and its logs
  visible in the UI.
- `humao.rest-client` — gives a "Send Request" codelens for
  `dev/wildfly/sample.http` (below).

### `dev/wildfly/sample.http`

A REST Client request file for the sample app's endpoints — `GET /log`,
`POST /timer/start` (with a `periodMs` and a `restart=true` variant),
`GET /timer/status`, `POST /timer/stop` — against a `@base` of
`http://localhost:8080/logaperture-sample-war`. Header comment points at
`wildflyctl up` / `deploy` and notes the timer's output lands in the server log,
not the response. `curl` equivalents stay in the README for anyone without the
extension.

## The sample application — `logaperture-sample-war`

A new reactor module, added to the root `pom.xml` `<modules>` after
`logaperture-it`. `packaging` is `war`.

**Dependencies** (all `provided` — WildFly supplies them at runtime):

- `javax.servlet:javax.servlet-api:4.0.1` — WildFly 26.1.3.Final is Jakarta EE 8,
  `javax.servlet` namespace (same constraint `logaperture-it` documents).
- `org.slf4j:slf4j-api` (`${slf4j.version}`, already managed in the parent).
- `org.apache.logging.log4j:log4j-api` — the Log4j 2 API; WildFly funnels it into
  JBoss LogManager (decision #7). Version added to parent dependency management.

No `logaperture-*` dependency of any kind — the sample is an ordinary app the
agent observes from outside.

**Classes** — `src/main/java/org/logaperture/sample/`, Apache-2.0 header (year
2026), no wildcard imports, 4-space indent:

- **`WorkLog`** — a tiny helper holding the three framework loggers, all named
  `org.logaperture.sample.work.Worker`, with one method
  `emitAllLevels(String marker)` that logs `marker` at TRACE→ERROR through each
  of JUL (`java.util.logging.Logger`, `FINEST`/`FINE`/`INFO`/`WARNING`/`SEVERE`),
  SLF4J, and Log4j. One obvious logger name to target with `logctl`.
- **`LogServlet`** (`@WebServlet("/log")`) — `doGet` calls
  `emitAllLevels("http GET /log #" + counter)` and returns a one-line text
  summary of what it logged.
- **`TimerServlet`** (`@WebServlet("/timer/*")`) —
  - `POST /timer/start` (optional `?periodMs=`, default 3000, clamped
    500..60000) — starts a single-thread daemon `ScheduledExecutorService` that
    calls `emitAllLevels("timer tick #" + n)` each period. Idempotent-ish:
    a second start with no stop returns 409 unless `?restart=true`.
  - `POST /timer/stop` — cancels the schedule, shuts the executor down.
  - `GET /timer/status` — running?, period, tick count.
- **`SampleContextListener`** (`@WebListener`) — logs `"sample deployed"` via
  `WorkLog` on `contextInitialized`; on `contextDestroyed` stops any running
  timer and shuts the executor down (so a redeploy doesn't leak a thread — and
  exercises the adapter keeping the logger node and its override alive across the
  deployment going away, which `wildfly-support.md` Slice 3 calls out).

**Deployed context path:** `logaperture-sample-war` (from the WAR name), so the
endpoints are `/logaperture-sample-war/log`, `/logaperture-sample-war/timer/*`.
The README and the functional summary use the shorter `/sample/...` only as
shorthand.

## Docs

- **`dev/wildfly/README.md`** — prerequisites (Docker, Python 3); the one-command
  quickstart; the debug-suspend workflow with a screenshot-free step list; the
  `.env`-vs-pom version rule; and — per §15.6's instruction to put it "in the
  first paragraph" for a WildFly audience — a note that the agent's overrides
  live only in its own store, expire on a timer, and never touch
  `standalone.xml`.
- **`doc/logaperture-spec.md`**:
  - §15.6: add under the existing `> Implementation spec:` line a
    `> Dev environment: doc/specs/wildfly-dev-environment.md — a committed,
    VSCode-integrated Docker Compose environment for manual deploy/run/debug
    against a real WildFly.`
  - §12: one bullet noting the interactive complement to the container smoke
    test lives in `wildfly-dev-environment.md`.

## `.gitignore`

```
dev/wildfly/deployments/*
!dev/wildfly/deployments/.gitkeep
```

(`*.war`, `*.jar` are already globally ignored, so the sample's build output and
any copied WAR are covered; the rule above keeps the directory itself tracked and
ignores anything else that lands there.)

## Decisions (resolved at sign-off)

Reviewed against the published walkthrough artifact; every one resolved to the
recommended option. Recorded here so the history shows what was chosen and why.

1. **File location** — `dev/wildfly/`. Reads as "developer environment", is not a
   Maven module, and leaves room for `dev/<other-target>/` when Tomcat / Spring
   Boot follow.
2. **`wildflyctl` invocation** — the script only (`python3
   dev/wildfly/wildflyctl.py <cmd>`), with a `#!/usr/bin/env python3` shebang and
   the executable bit. No `wildflyctl` / `.cmd` shims — the VSCode tasks are the
   primary entry point and call `python3` directly.
3. **Dev sweep interval** — `-Dlogaperture.sweep.seconds=5` by default (the IT
   uses `3`, prod default is `30`), overridable per run with `--sweep-seconds`.
4. **Image-version sync** — `wildflyctl` emits a non-fatal warning on `up` /
   `status` when `dev/wildfly/.env`'s `WILDFLY_IMAGE` disagrees with the root
   `pom.xml` `wildfly.image` property. `.env` is not auto-regenerated.
5. **`up + attach` UX** — the *Attach to WildFly (8787)* launch config carries
   `"preLaunchTask": "WildFly: up"` for the plain path; the debug-suspend path
   stays a deliberate two-step (`WildFly: up (debug-suspend)` task, then the
   separate *Attach to WildFly — premain (8787)* config, which has no
   `preLaunchTask` — the plain `up` wait can't complete against a suspended JVM).
6. **Docker extension** — `ms-azuretools.vscode-docker` added to
   `.vscode/extensions.json` `recommendations` (soft; nothing here depends on it).
7. **Sample WAR: Log4j surface** — JUL + SLF4J + Log4j. JBoss LogManager funnels
   Log4j too, and it is the framework a real WildFly app is most likely to bring
   itself; the sample's point is one override landing across all of them.
8. **Sample WAR module name** — `logaperture-sample-war`. Names the artifact type
   and matches the `-agent` / `-cli` / `-container-wildfly` pattern.

## Exit criterion

`wildflyctl up` boots the pinned WildFly image with the agent attached and a
clean log (`WFLYSRV0025`, no "LogManager was not properly installed", no
premature-JUL warning); `wildflyctl deploy` deploys `logaperture-sample-war`;
hitting `/log` and starting the timer produce multi-framework log lines from
`org.logaperture.sample.work.Worker`; `wildflyctl logctl -- debug
org.logaperture.sample.work.Worker for 2m` raises the level, the change is
visible in the log, `logctl status` shows the override, and it reverts on the
sweep; the **Attach to WildFly (8787)** config attaches and a breakpoint in the
agent's runtime code is hit; `wildflyctl up --debug-suspend` lets a breakpoint in
`premain` be hit before the server proceeds; and `standalone.xml` is
`md5sum`-identical after a session of overrides. All committed under `dev/wildfly/`
and `.vscode/`, with `logaperture-sample-war` in the reactor, and `mvn verify`
still green (the new module builds; nothing else changes).
