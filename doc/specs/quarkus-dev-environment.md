# Quarkus Dev Environment — VSCode-integrated run / drive / debug

Status: **Signed off 2026-10-24** (decisions QD1 to QD9 below, all agreed as recommended). Implemented on `feature/263-quarkus-dev-environment`; the one remaining exit-criterion item is the hand check of `up --debug-suspend` under the VSCode debugger.

Parent spec: [`doc/logaperture-spec.md`](../logaperture-spec.md) §12 (testing strategy: this is the
interactive complement to the automated `QuarkusIT`), §14 (developer workflow), §15.9 (Quarkus).
Builds on: [`quarkus-support.md`](quarkus-support.md), which shipped the Quarkus container module,
the `logaperture-sample-quarkus` fixture and `QuarkusIT`. Counterpart of
[`wildfly-dev-environment.md`](wildfly-dev-environment.md), whose shape this follows where Quarkus
allows. Tracking issue: [#263](https://github.com/ddeuchert/logaperture/issues/263) (milestone `1.1.0`).

## Functional summary

After this feature, a developer with the repo open in VSCode will be able to:

- Start the bundled sample Quarkus application with the LogAperture agent attached, in one step: a
  `Quarkus: up` task, or `quarkusctl up` in a terminal. It rebuilds anything out of date first.
- Hit the sample's endpoint and see log lines at every level, then watch them change as `logctl`
  raises and lowers levels.
- Drive `logctl` against the running app without knowing the plumbing: a `Quarkus: logctl…` task that
  prompts for the arguments, or `quarkusctl logctl -- set logger org.acme.Foo DEBUG for 30m`.
- Run **their own** Quarkus application instead of the sample, by pointing `up` at its
  `quarkus-run.jar`.
- Try the Quarkus-specific behaviors on demand: a build with `quarkus.log.min-level=TRACE` (and
  without it, to see the below-floor warning), the file handler, a vendor defaults file, and a second
  Quarkus version (the 3.33 LTS stream).
- Attach the VSCode Java debugger to the running app ("Attach to Quarkus (5006)") and set breakpoints
  in the agent, or start it suspended (`quarkusctl up --debug-suspend`) to step through `premain`.
- Watch the application's console, either in the foreground (`quarkusctl up --foreground`, or the
  `Quarkus: run (foreground)` task) or by tailing the detached app (`Quarkus: tail`), and stop it
  (`Quarkus: down`).

None of this changes the agent, the CLI or the container modules. There is no new compiled code.

Guide pages this changes: none. This is repository tooling, not something an end user installs.
`DEVELOPMENT.md` gains a short pointer to `dev/quarkus/README.md`.

## Scope

**In scope:**

- `dev/quarkus/quarkusctl.py`, a stdlib-only Python 3 driver with subcommands `up`, `down`, `logctl`,
  `tail`, `status`, `build`.
- `dev/quarkus/README.md`: prerequisites, quick start, the "things worth trying" list.
- VSCode integration under `.vscode/`: tasks wrapping the driver, one debugger attach configuration,
  and a `dev/quarkus/sample.http` request file.
- `.gitignore` entries for the driver's run directory.

**Out of scope:**

- Containers (QD1). The Azure App Service / Kubernetes story is a separate, later environment.
- Changes to `logaperture-sample-quarkus` (QD4).
- Native images (never supported) and Quarkus dev mode (`quarkus:dev`, not a supported target).
- Windows. The driver is plain Python and avoids POSIX-only calls where cheap, but only Linux is
  exercised, as with the WildFly environment's Docker dependency.

## Design

### Runs on the host, no container (QD1)

`logctl` must run on the same machine and as the same OS user as the application (the install guide's
requirement). A container would force `logctl` into it, as the WildFly environment does. Quarkus is
an ordinary JVM, so the app runs as a host child process and `logctl` runs beside it. This is also
the shortest feedback loop, and it needs only a JDK, Maven and Python 3.

### Layout

```
dev/quarkus/
  README.md
  quarkusctl.py
  sample.http
  vendor/example-vendor-defaults.yaml   (reuses dev/wildfly/vendor/'s file if it applies, see QD7)
  run/                                   (gitignored: pid, app.log, LogAperture home)
```

### `quarkusctl.py`

| Subcommand | Behavior |
|---|---|
| `build [--min-level L] [--quarkus-version V]` | Build the agent, `logctl` and the sample (`./mvnw -Pquarkus …`). With `--min-level` or `--quarkus-version` the sample is built into its own directory (QD5), so variants coexist. |
| `up` | Run `build` if a jar is missing or older than its sources (the WildFly driver's stale-jar warning, reused). Start the app detached with `-javaagent`, wait until it answers HTTP and `logctl env` names Quarkus, print the endpoint and the `logctl` command to try next. Refuses if one is already running. |
| `down` | Stop the app it started (pid file), never a process it did not start. |
| `logctl -- ARGS` | Run the freshly-built `logctl` jar against the app with the same JDK, passing the arguments through. |
| `tail` | Follow `run/app.log`. |
| `status` | Process state, port, and `logctl env` plus `logctl status`. |

`up` options:

| Option | Meaning |
|---|---|
| `--jar PATH` | Run this `quarkus-run.jar` (or uber-jar) instead of the sample (QD3). |
| `--port N` | HTTP port, default `8081` so it does not collide with the WildFly environment's 8080. |
| `--min-level L` | Use the sample built with `quarkus.log.min-level=L` (QD5). |
| `--quarkus-version V` | Use the sample built against Quarkus `V`, for example the LTS `3.33.4` (QD5). |
| `--file-log` | Enable Quarkus's file handler into `run/` so `file` appears in `list handlers`. |
| `--vendor-defaults FILE` | Pass `--vendor-defaults=FILE` to the agent. |
| `--foreground` | Run attached to the terminal instead of detached: the console is on screen and Ctrl-C stops the app. The pid file is still written, so `logctl`, `status` and `down` work from another terminal. Skips the readiness wait. |
| `--debug` / `--debug-suspend` | Add JDWP on port 5006 (QD6), `suspend=n` / `suspend=y`. |
| `--java-home DIR` | Run with this JDK, to try 17 or 25 (the supported set is 17, 21, 25). |
| `--agent-arg ARG` | Extra text appended to the agent argument, for trying other agent options. |

The agent is given `-Dlogaperture.home=dev/quarkus/run/home`, so the environment never touches the
developer's real `~/.logaperture` and `down` plus deleting `run/` is a full reset (QD8).

### VSCode integration

- `tasks.json`: `Quarkus: up`, `Quarkus: up (debug-suspend)`, `Quarkus: down`,
  `Quarkus: build`, `Quarkus: logctl…` (prompts for arguments, its own input id), `Quarkus: tail`,
  `Quarkus: status`. Each shells the driver, exactly as the WildFly tasks do.
- `launch.json`: one attach configuration, "Attach to Quarkus (5006)", with `projectName` unset so
  the debugger resolves agent sources from the workspace.
- `sample.http`: `GET /log`, `GET /log?tag=x&repeat=50`, for the REST Client extension that
  `dev/wildfly/sample.http` already assumes.

### The sample application

`logaperture-sample-quarkus` is used unchanged: `GET /log?tag=&repeat=` logs at every level through
JBoss Logging and `java.util.logging` and reports what its loggers will emit. It has no timer
endpoint (the WildFly sample does); adding one is QD4, deliberately not done here.

## Decisions

All nine were agreed on 2026-10-24 as recommended.

| Id | Question | Recommendation |
|---|---|---|
| QD1 | Host process or container? | Host process. `logctl` has to share the machine and user with the app, and it is the fastest loop. A container environment is a separate feature for the Azure / Kubernetes story. |
| QD2 | Driver language and name? | Python 3, stdlib only, `quarkusctl.py`, mirroring `wildflyctl.py` so the two feel the same. |
| QD3 | Support the developer's own app? | Yes, via `--jar`, because testing the day-job application is the main reason this exists. |
| QD4 | Add a timer endpoint to the sample? | No for now. It is a `QuarkusIT` fixture marked "not part of the product", and a `for 2m` expiry can be watched with `curl` and `watch`. Revisit if it proves annoying. |
| QD5 | How are build variants (`min-level`, Quarkus version) kept apart? | Each variant builds to `dev/quarkus/run/builds/<version>-<min-level>/` via a copy of the `quarkus-app` directory, so switching variants does not force a rebuild of the others. |
| QD6 | Debug port? | 5006. WildFly's environment uses 8787, and the two can run together. |
| QD7 | Vendor defaults example? | Ship a small Quarkus-flavored example in `dev/quarkus/vendor/` rather than reuse WildFly's, since logger and handler names differ. |
| QD8 | Where does state live? | `dev/quarkus/run/` (gitignored), including the LogAperture home. Deleting it is a full reset. |
| QD9 | Branch and release line? | `feature/<issue>-quarkus-dev-environment` from `develop`, milestone `1.1.0`, spec committed with the code. |

## Open points, resolved during implementation

- **How `logctl` finds the app:** the driver passes `--pid` (from its pid file), so the home
  location does not matter. `QuarkusIT` does the same.
- **`--quarkus-version`:** `-Dquarkus.version=` works, provided the sample's `quarkus-app` output is
  removed first (build-time properties are not part of Maven's up-to-date check). The driver does
  that for every build and copies the result to the variant directory. Verified with 3.33.4.
- **Vendor defaults example:** only handler names (`console`, `file`) and the sample's logger names
  are Quarkus-specific; the format is the common one.

## Exit criterion

On a clean checkout: `quarkusctl up`, `curl localhost:8081/log`, `quarkusctl logctl -- env` showing
`Quarkus`, a `set logger … DEBUG` visibly changing the output, `up --min-level TRACE` showing real
TRACE lines, `up --debug-suspend` stopping at `premain` under the VSCode debugger, and `down` leaving
no process behind. Verified by hand, with the output of the first three captured in the README.
