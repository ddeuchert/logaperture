#!/usr/bin/env python3
# Copyright 2026 David Deuchert
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

"""Driver for the LogAperture WildFly dev environment.

Standard library only — no venv, no requirements. Every subcommand is a thin
wrapper that runs the right `docker compose` / `mvn` command from the repo root
with the right environment. See doc/specs/wildfly-dev-environment.md.

    python3 dev/wildfly/wildflyctl.py up [--debug-suspend] [--sweep-seconds N] [--build]
    python3 dev/wildfly/wildflyctl.py deploy [PATH]
    python3 dev/wildfly/wildflyctl.py logctl -- debug org.acme.Foo for 30m
    python3 dev/wildfly/wildflyctl.py tail
    python3 dev/wildfly/wildflyctl.py down
"""

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent          # dev/wildfly/
REPO_ROOT = HERE.parent.parent
COMPOSE_FILE = HERE / "docker-compose.yml"
ENV_FILE = HERE / ".env"
DEPLOYMENTS = HERE / "deployments"
POM = REPO_ROOT / "pom.xml"

PROJECT = "logaperture"
SERVICE = "wildfly"
CONTAINER_LOG = "/opt/jboss/wildfly/standalone/log/server.log"
CONTAINER_DEPLOY_DIR = "/opt/jboss/wildfly/standalone/deployments"
DEFAULT_IMAGE = "quay.io/wildfly/wildfly:26.1.3.Final-jdk17"

AGENT_JAR = REPO_ROOT / "logaperture-agent" / "target" / "logaperture-agent.jar"
CLI_JAR = REPO_ROOT / "logaperture-cli" / "target" / "logaperture-cli.jar"
SAMPLE_WAR = REPO_ROOT / "logaperture-sample-war" / "target" / "logaperture-sample-war.war"


# --- shelling out --------------------------------------------------------------

def run(cmd, *, check=True, capture=False, env=None):
    """Run a command, echoing it first. Returns the CompletedProcess."""
    print("+ " + " ".join(str(c) for c in cmd), file=sys.stderr)
    return subprocess.run(
        [str(c) for c in cmd],
        check=check,
        text=True,
        capture_output=capture,
        env=env,
    )


def compose(*args, check=True, capture=False, env=None):
    cmd = ["docker", "compose", "-p", PROJECT, "-f", COMPOSE_FILE, *args]
    return run(cmd, check=check, capture=capture, env=env)


def require_docker():
    if shutil.which("docker") is None:
        sys.exit("error: `docker` is not on PATH. Install Docker (you already need "
                 "it for `mvn verify`'s WildFly IT).")


def mvn(*args):
    mvn_exe = "mvn.cmd" if os.name == "nt" else "mvn"
    run([mvn_exe, "-q", *args], check=True)


# --- image-version drift -----------------------------------------------------

def pom_image():
    try:
        text = POM.read_text(encoding="utf-8")
    except OSError:
        return None
    m = re.search(r"<wildfly\.image>\s*(.+?)\s*</wildfly\.image>", text)
    return m.group(1) if m else None


def env_image():
    try:
        for line in ENV_FILE.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if line.startswith("WILDFLY_IMAGE=") and not line.startswith("#"):
                return line.split("=", 1)[1].strip()
    except OSError:
        pass
    return None


def warn_on_image_drift():
    pom = pom_image()
    env = env_image()
    if pom and env and pom != env:
        print(
            "\n  warning: WildFly image drift\n"
            f"    dev/wildfly/.env : {env}\n"
            f"    pom.xml          : {pom}  (source of truth)\n"
            f"    fix: set WILDFLY_IMAGE={pom} in dev/wildfly/.env\n",
            file=sys.stderr,
        )


# --- build artifacts --------------------------------------------------------

def ensure_jars(build):
    missing = [p for p in (AGENT_JAR, CLI_JAR) if not p.is_file()]
    if build or missing:
        if missing and not build:
            print(f"note: building missing jar(s): {', '.join(p.name for p in missing)}",
                  file=sys.stderr)
        mvn("-pl", "logaperture-agent,logaperture-cli", "-am", "package", "-DskipTests")
    for p in (AGENT_JAR, CLI_JAR):
        if not p.is_file():
            sys.exit(f"error: expected {p} after build — did `mvn package` fail?")


def ensure_sample_war():
    if not SAMPLE_WAR.is_file():
        print("note: building logaperture-sample-war", file=sys.stderr)
        mvn("-pl", "logaperture-sample-war", "-am", "package", "-DskipTests")
    if not SAMPLE_WAR.is_file():
        sys.exit(f"error: expected {SAMPLE_WAR} after build")


# --- readiness -------------------------------------------------------------

def booted():
    r = compose("exec", "-T", SERVICE, "sh", "-c",
                f"grep -q WFLYSRV0025 {CONTAINER_LOG}",
                check=False, capture=True)
    return r.returncode == 0


def wait_for_boot(timeout=180):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if booted():
            return True
        time.sleep(3)
    return False


def logctl(args, check=False):
    return compose("exec", "-T", SERVICE, "java", "-jar", "/opt/logctl.jar", *args,
                   check=check)


# --- subcommands ----------------------------------------------------------

def cmd_up(a):
    require_docker()
    warn_on_image_drift()
    ensure_jars(a.build)

    env = dict(os.environ)
    env["DEBUG_SUSPEND"] = "y" if a.debug_suspend else "n"
    env["LOGAPERTURE_SWEEP_SECONDS"] = str(a.sweep_seconds)

    compose("up", "-d", env=env)

    if a.debug_suspend:
        print(
            "\nWildFly started with suspend=y — the JVM is paused before premain.\n"
            "Attach VSCode 'Attach to WildFly (8787)' now; the server proceeds once\n"
            "the debugger connects.\n", file=sys.stderr)
        return

    print("\nwaiting for a clean boot (WFLYSRV0025)…", file=sys.stderr)
    if wait_for_boot():
        print("WildFly is up.\n"
              "  app:        http://localhost:8080/\n"
              "  management: http://localhost:9990/\n"
              "  debug:      attach VSCode 'Attach to WildFly (8787)'\n"
              "  logs:       python3 dev/wildfly/wildflyctl.py tail\n", file=sys.stderr)
    else:
        sys.exit("error: WildFly did not report a clean boot in time — check "
                 "`python3 dev/wildfly/wildflyctl.py tail`.")


def cmd_down(a):
    require_docker()
    compose("down")


def cmd_restart_agent(a):
    require_docker()
    ensure_jars(build=True)
    compose("restart", SERVICE)
    if wait_for_boot():
        print("WildFly restarted with the rebuilt agent.", file=sys.stderr)
    else:
        sys.exit("error: WildFly did not come back cleanly — check `wildflyctl tail`.")


def cmd_deploy(a):
    require_docker()
    if a.path:
        src = Path(a.path).resolve()
        if not src.is_file():
            sys.exit(f"error: no such file: {src}")
    else:
        ensure_sample_war()
        src = SAMPLE_WAR

    DEPLOYMENTS.mkdir(exist_ok=True)
    dest = DEPLOYMENTS / src.name
    shutil.copy2(src, dest)
    print(f"copied {src.name} -> dev/wildfly/deployments/", file=sys.stderr)

    marker = f"{CONTAINER_DEPLOY_DIR}/{src.name}.deployed"
    failed = f"{CONTAINER_DEPLOY_DIR}/{src.name}.failed"
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        if compose("exec", "-T", SERVICE, "test", "-f", marker,
                   check=False, capture=True).returncode == 0:
            print(f"{src.name} deployed.", file=sys.stderr)
            return
        if compose("exec", "-T", SERVICE, "test", "-f", failed,
                   check=False, capture=True).returncode == 0:
            sys.exit(f"error: {src.name} failed to deploy — check `wildflyctl tail`.")
        time.sleep(2)
    sys.exit(f"error: {src.name} did not deploy in time — check `wildflyctl tail`.")


def cmd_undeploy(a):
    require_docker()
    name = a.name or SAMPLE_WAR.name
    if not name.endswith(".war") and not name.endswith(".ear") and not name.endswith(".jar"):
        name += ".war"
    archive = DEPLOYMENTS / name
    if archive.exists():
        archive.unlink()
        print(f"removed dev/wildfly/deployments/{name}", file=sys.stderr)
    marker = f"{CONTAINER_DEPLOY_DIR}/{name}.undeployed"
    deadline = time.monotonic() + 60
    while time.monotonic() < deadline:
        if compose("exec", "-T", SERVICE, "test", "-f", marker,
                   check=False, capture=True).returncode == 0:
            print(f"{name} undeployed.", file=sys.stderr)
            return
        time.sleep(2)
    print(f"note: no .undeployed marker for {name} — it may not have been deployed.",
          file=sys.stderr)


def cmd_logctl(a):
    require_docker()
    args = a.args
    if args and args[0] == "--":
        args = args[1:]
    if not args:
        sys.exit("usage: wildflyctl logctl -- <logctl args>   e.g. -- levels org.jboss")
    r = logctl(args, check=False)
    sys.exit(r.returncode)


def cmd_tail(a):
    require_docker()
    compose("logs", "-f", "--tail", str(a.lines), SERVICE, check=False)


def cmd_status(a):
    require_docker()
    warn_on_image_drift()
    compose("ps", check=False)
    print("\n--- logctl status ---", file=sys.stderr)
    logctl(["status"], check=False)


# --- arg parsing ----------------------------------------------------------

def build_parser():
    p = argparse.ArgumentParser(
        prog="wildflyctl",
        description="Drive the LogAperture WildFly dev environment.")
    sub = p.add_subparsers(dest="cmd", required=True)

    up = sub.add_parser("up", help="start WildFly with the agent attached")
    up.add_argument("--debug-suspend", action="store_true",
                    help="boot with suspend=y so premain can be breakpointed")
    up.add_argument("--sweep-seconds", type=int, default=5,
                    help="logaperture.sweep.seconds (default 5)")
    up.add_argument("--build", action="store_true",
                    help="rebuild the agent + CLI jars first")
    up.set_defaults(func=cmd_up)

    down = sub.add_parser("down", help="stop and remove the container")
    down.set_defaults(func=cmd_down)

    ra = sub.add_parser("restart-agent", help="rebuild the agent jar and restart WildFly")
    ra.set_defaults(func=cmd_restart_agent)

    dep = sub.add_parser("deploy", help="deploy a WAR (default: the sample)")
    dep.add_argument("path", nargs="?", help="path to a .war/.ear (default: logaperture-sample-war)")
    dep.set_defaults(func=cmd_deploy)

    und = sub.add_parser("undeploy", help="undeploy an archive (default: the sample)")
    und.add_argument("name", nargs="?", help="archive name (default: logaperture-sample-war.war)")
    und.set_defaults(func=cmd_undeploy)

    lc = sub.add_parser("logctl", help="run logctl inside the container (args after --)")
    lc.add_argument("args", nargs=argparse.REMAINDER)
    lc.set_defaults(func=cmd_logctl)

    tl = sub.add_parser("tail", help="follow the server log")
    tl.add_argument("--lines", type=int, default=80, help="initial lines (default 80)")
    tl.set_defaults(func=cmd_tail)

    st = sub.add_parser("status", help="container state + logctl status")
    st.set_defaults(func=cmd_status)

    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        args.func(args)
    except subprocess.CalledProcessError as e:
        sys.exit(e.returncode or 1)
    except KeyboardInterrupt:
        sys.exit(130)


if __name__ == "__main__":
    main()
