# WildFly dev environment

A committed, one-command WildFly for hands-on testing of the LogAperture agent:
deploy an app, drive `logctl`, attach a debugger. This is the interactive
counterpart to the automated `logaperture-it/WildFlyContainerIT`.

**The agent never touches `standalone.xml`.** Its level overrides live only in
the agent's own store, expire on a timer, and are reverted by `logctl reset` —
nothing here is written back into any server- or app-owned config file. That is
the whole point versus changing a level through the WildFly CLI.

Full design: [`doc/specs/wildfly-dev-environment.md`](../../doc/specs/wildfly-dev-environment.md).

## Prerequisites

- **Docker** — you already need it for `mvn verify` (the WildFly IT).
- **Python 3** — standard library only; no venv, no `pip install`.

## Quickstart

From the repo root:

```sh
python3 dev/wildfly/wildflyctl.py up             # build agent+CLI if needed, start WildFly
python3 dev/wildfly/wildflyctl.py deploy         # build + deploy logaperture-sample-war
curl http://localhost:8080/logaperture-sample-war/log

python3 dev/wildfly/wildflyctl.py logctl -- levels org.logaperture.sample
python3 dev/wildfly/wildflyctl.py logctl -- debug org.logaperture.sample.work.Worker for 2m
curl http://localhost:8080/logaperture-sample-war/log        # now DEBUG lines appear
python3 dev/wildfly/wildflyctl.py logctl -- status

python3 dev/wildfly/wildflyctl.py tail           # follow server.log
python3 dev/wildfly/wildflyctl.py down
```

In VSCode the same actions are **Tasks** (`Terminal → Run Task…`): *WildFly: up*,
*WildFly: deploy sample*, *WildFly: logctl…* (prompts for arguments),
*WildFly: tail server.log*, *WildFly: down*.

## The sample app

`logaperture-sample-war` (a reactor module) deploys at
`/logaperture-sample-war`:

| Request | Effect |
|---|---|
| `GET /log` | logs one line at TRACE→ERROR via `java.util.logging`, SLF4J, and Log4j |
| `POST /timer/start?periodMs=3000` | starts a background thread doing the same every `periodMs` (500–60000) |
| `POST /timer/stop` | stops it |
| `GET /timer/status` | running?, period, tick count |

Every logger is named `org.logaperture.sample.work.Worker` — one `logctl`
override target hits all three frameworks. The timer lets you watch a `for`
override expire (and the verification sweep re-apply drift) without hitting an
endpoint by hand.

Fire the endpoints from `curl` (above), a browser (the `GET`s), or — with the
**REST Client** extension — [`sample.http`](sample.http), which has a
"Send Request" link above each one.

## Debugging the agent

The container publishes JDWP on **8787**. Two VSCode launch configs:

- **Attach to WildFly (8787)** — for breakpoints in code the agent runs *after*
  boot (the verification sweep, the JUL adapter, the override applier). Runs
  *WildFly: up* first (a no-op if already running), then attaches.
- **Attach to WildFly — premain (8787)** — for stepping through agent
  initialization. Run **WildFly: up (debug-suspend)** first (or `wildflyctl up
  --debug-suspend`); the JVM boots suspended and waits. Then launch this config
  (it has no pre-launch step), set a breakpoint in `AgentBootstrap.start`,
  `WildFlyContainerIntegration.detect` / `.activate`, or
  `WildFlyLogManagerReadiness`, and resume.

## `wildflyctl` commands

| Command | |
|---|---|
| `up [--debug-suspend] [--sweep-seconds N] [--build]` | start; builds the agent/CLI jars if missing |
| `down` | stop and remove the container |
| `restart-agent` | rebuild `logaperture-agent`, recreate WildFly (normal mode) to pick it up |
| `deploy [PATH]` | deploy a WAR/EAR (default: the sample); waits for `.deployed` |
| `undeploy [NAME]` | undeploy (default: the sample) |
| `logctl -- ARG…` | run `logctl` inside the container |
| `tail [--lines N]` | follow `server.log` |
| `status` | container state + `logctl status` + image-version drift check |

Deploy your own app with `wildflyctl deploy path/to/app.war`, or just drop an
archive into `dev/wildfly/deployments/` (git-ignored).

## WildFly version

Pinned by `WILDFLY_IMAGE` in [`.env`](.env). The **source of truth** is the
`<wildfly.image>` property in the repo-root `pom.xml` (also used by
`logaperture-it`, so debugging happens in the same image CI tests). `wildflyctl
up` / `status` print a non-fatal warning if the two drift; fix `.env` to match.

## Not wired into CI

This is a local developer aid. Automated WildFly coverage is
`WildFlyContainerIT` under `mvn verify`; nothing here runs in any workflow.
