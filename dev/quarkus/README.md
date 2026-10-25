# Quarkus dev environment

A committed, one-command way to run the sample Quarkus app with the LogAperture agent
attached, drive `logctl` against it, and attach a debugger to the agent. It is the
interactive counterpart to the automated `logaperture-it/QuarkusIT`.

Unlike the WildFly environment there is **no container**: `logctl` has to run on the same
machine and as the same OS user as the JVM it controls, and Quarkus is an ordinary JVM, so the
app is a host child process.

Full design: [`doc/specs/quarkus-dev-environment.md`](../../doc/specs/quarkus-dev-environment.md).

## Prerequisites

- A **JDK** (17, 21 or 25) and the repo's `./mvnw`.
- **Python 3** — standard library only; no venv, no `pip install`.

## Quickstart

From the repo root:

```sh
python3 dev/quarkus/quarkusctl.py up                       # build if needed, start on :8081
curl 'localhost:8081/log?tag=a'
python3 dev/quarkus/quarkusctl.py logctl -- env
python3 dev/quarkus/quarkusctl.py logctl -- set logger org.logaperture.sample.quarkus.Worker DEBUG for 30m
curl 'localhost:8081/log?tag=b'                            # DEBUG-line b now appears
python3 dev/quarkus/quarkusctl.py tail                     # follow the output (Ctrl-C)
python3 dev/quarkus/quarkusctl.py down
```

From a real run (Quarkus 3.39.5, JDK 21):

```
$ curl 'localhost:8081/log?tag=a'
trace=false debug=false julFine=false floor=500 manager=org.jboss.logmanager.LogManager

$ quarkusctl logctl -- env
LogAperture agent    1.1.0-SNAPSHOT  (logctl 1.1.0-SNAPSHOT)
Java                 21.0.12  Eclipse Adoptium
Logging backend      JBoss LogManager 3.0.6.Final
Framework/container  Quarkus 3.39.5

$ quarkusctl logctl -- set logger org.logaperture.sample.quarkus.Worker DEBUG for 5m
org.logaperture.sample.quarkus.Worker → DEBUG   (FOR, reverts 21:50:43 local — in 4m)
```

`logctl` is pointed at the app with `--pid` (read from the driver's pid file), so it works whatever
`-Dlogaperture.home` is. The environment's LogAperture home is `dev/quarkus/run/home`, never your
real `~/.logaperture`.

To see the console directly, run `up --foreground` (the **Quarkus: run (foreground)** task):
the JVM is attached to your terminal and Ctrl-C stops it. Use `logctl` from a second terminal.
Otherwise `tail` follows the detached app's output.

In VSCode, the same things are tasks: **Quarkus: up**, **up (debug-suspend)**, **down**, **build**,
**logctl…** (prompts for the arguments), **tail**, **status**. `dev/quarkus/sample.http` has the
requests for the REST Client extension.

## Things worth trying

| To see | Run |
|---|---|
| TRACE actually emitted (the build floor lowered) | `up --min-level TRACE`, then `set logger … TRACE`. Without it, `doctor` warns the build only emits DEBUG and above. |
| The file handler in `list handlers` | `up --file-log` (writes `dev/quarkus/run/quarkus-file.log`) |
| `logctl top` | `up --file-log`, send some requests, then `logctl -- top`. `top` counts bytes written to files, and Quarkus logs to the console by default. Without `--file-log`, `top` says there are no log files to measure and names the handlers it found. |
| A vendor defaults file | `up --vendor-defaults dev/quarkus/vendor/example-vendor-defaults.yaml`, then `logctl -- env` |
| The 3.33 LTS stream | `up --quarkus-version 3.33.4` |
| Another JDK | `up --java-home /path/to/jdk-17` |
| Other agent options | `up --agent-arg --storm-detection=on` |
| **Your own app** | `up --jar /path/to/quarkus-app/quarkus-run.jar` (a fast-jar or uber-jar) |

Each `--min-level` / `--quarkus-version` variant builds into its own directory under
`dev/quarkus/run/builds/`, so switching between them does not rebuild.

A `for 2m` expiry has no timer endpoint to watch (the sample is a test fixture); use
`watch -n 5 curl -s 'localhost:8081/log?tag=watch'`.

## Debugging the agent

- **After boot** (sweeps, adapter operations): `up --debug`, then run **Attach to Quarkus (5006)**.
- **`premain`**: run the **Quarkus: up (debug-suspend)** task first. The JVM waits for the attach
  before running a line of agent code, so a breakpoint in `LogApertureAgent.premain` is in place in
  time. Then run **Attach to Quarkus (5006)**.

The port is 5006 so this and the WildFly environment (8787) can run together.

## Reset

`quarkusctl down`, then delete `dev/quarkus/run/`. That removes the pid file, the output, the
LogAperture home (sticky overrides, rules) and the variant builds.
