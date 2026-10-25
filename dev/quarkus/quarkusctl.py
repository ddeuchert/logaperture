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

"""Driver for the LogAperture Quarkus dev environment.

Standard library only -- no venv, no requirements. The application runs as a host
child process (no container): `logctl` must share the machine and OS user with the
JVM it controls. See doc/specs/quarkus-dev-environment.md.

    python3 dev/quarkus/quarkusctl.py up [--jar PATH] [--port N] [--min-level L]
                                         [--quarkus-version V] [--file-log]
                                         [--vendor-defaults FILE] [--foreground]
                                         [--debug | --debug-suspend]
                                         [--java-home DIR] [--agent-arg ARG]
    python3 dev/quarkus/quarkusctl.py logctl -- set logger org.acme.Foo DEBUG for 30m
    python3 dev/quarkus/quarkusctl.py status | tail | down
    python3 dev/quarkus/quarkusctl.py build [--min-level L] [--quarkus-version V]
"""

import argparse
import os
import re
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent          # dev/quarkus/
REPO_ROOT = HERE.parent.parent
RUN = HERE / "run"                               # gitignored: pid, app.log, home, builds
HOME = RUN / "home"                              # -Dlogaperture.home, never ~/.logaperture
PID_FILE = RUN / "app.pid"
APP_LOG = RUN / "app.log"                        # the JVM's stdout/stderr
FILE_LOG = RUN / "quarkus-file.log"              # Quarkus's own file handler (--file-log)
BUILDS = RUN / "builds"
SAMPLE_POM = REPO_ROOT / "logaperture-sample-quarkus" / "pom.xml"
SAMPLE_APP = REPO_ROOT / "logaperture-sample-quarkus" / "target" / "quarkus-app"

AGENT_JAR = REPO_ROOT / "logaperture-agent" / "target" / "logaperture-agent.jar"
CLI_JAR = REPO_ROOT / "logaperture-cli" / "target" / "logaperture-cli.jar"
AGENT_MODULES = ["logaperture-core", "logaperture-agent"]
CLI_MODULES = ["logaperture-cli"]
SAMPLE_MODULES = ["logaperture-sample-quarkus"]

DEFAULT_PORT = 8081          # the WildFly environment uses 8080
DEBUG_PORT = 5006            # the WildFly environment uses 8787
INSTANCE_ID = "quarkus-dev"


# --- shelling out --------------------------------------------------------------

def run(cmd, *, check=True, capture=False, env=None):
    """Run a command, echoing it first. Returns the CompletedProcess."""
    print("+ " + " ".join(str(c) for c in cmd), file=sys.stderr)
    return subprocess.run([str(c) for c in cmd], check=check, text=True,
                          capture_output=capture, env=env)


def mvn(*args):
    wrapper = REPO_ROOT / ("mvnw.cmd" if os.name == "nt" else "mvnw")
    exe = str(wrapper) if wrapper.is_file() else ("mvn.cmd" if os.name == "nt" else "mvn")
    run([exe, "-q", "-f", REPO_ROOT / "pom.xml", *args])


def java_exe(java_home=None):
    home = java_home or os.environ.get("JAVA_HOME")
    if home:
        exe = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if not exe.is_file():
            sys.exit(f"error: no java at {exe}")
        return str(exe)
    found = shutil.which("java")
    if found is None:
        sys.exit("error: `java` is not on PATH and JAVA_HOME is not set.")
    return found


# --- build artifacts --------------------------------------------------------

def default_quarkus_version():
    m = re.search(r"<quarkus\.version>\s*(.+?)\s*</quarkus\.version>",
                  SAMPLE_POM.read_text(encoding="utf-8"))
    return m.group(1) if m else "unknown"


def variant_name(min_level, quarkus_version):
    return f"{quarkus_version or default_quarkus_version()}-{min_level or 'default'}"


def newest_source_change(modules):
    """Latest modification time (Unix seconds) across `modules`' `src/` trees: the
    newer of the latest commit touching them and any uncommitted file's own mtime.
    None when git cannot answer (staleness then just is not checked)."""
    paths = [f"{m}/src" for m in modules]
    latest = None
    log = run(["git", "-C", REPO_ROOT, "log", "-1", "--format=%ct", "--", *paths],
              check=False, capture=True)
    if log.returncode == 0 and log.stdout.strip():
        latest = int(log.stdout.strip())
    status = run(["git", "-C", REPO_ROOT, "status", "--porcelain", "--", *paths],
                 check=False, capture=True)
    if status.returncode == 0:
        for line in status.stdout.splitlines():
            f = REPO_ROOT / line[3:].strip()
            if f.is_file():
                mtime = int(f.stat().st_mtime)
                latest = mtime if latest is None else max(latest, mtime)
    return latest


def is_stale(jar, modules):
    if not jar.is_file():
        return True
    src_time = newest_source_change(modules)
    return src_time is not None and jar.stat().st_mtime < src_time


def ensure_agent_and_cli():
    stale = [p for p, mods in ((AGENT_JAR, AGENT_MODULES), (CLI_JAR, CLI_MODULES))
             if is_stale(p, mods)]
    if stale:
        print(f"note: building {', '.join(p.name for p in stale)}", file=sys.stderr)
        mvn("-pl", "logaperture-agent,logaperture-cli", "-am", "package", "-DskipTests")
    for p in (AGENT_JAR, CLI_JAR):
        if not p.is_file():
            sys.exit(f"error: expected {p} after build -- did `mvn package` fail?")


def build_sample(min_level, quarkus_version):
    """Build the sample into its own directory so variants coexist (QD5). Returns
    the variant's quarkus-run.jar."""
    variant = BUILDS / variant_name(min_level, quarkus_version)
    props = []
    if min_level:
        props.append(f"-Dquarkus.log.min-level={min_level}")
    if quarkus_version:
        props.append(f"-Dquarkus.version={quarkus_version}")
    # A previous variant's output would otherwise be reused: Quarkus build-time
    # properties are not part of Maven's up-to-date check.
    shutil.rmtree(SAMPLE_APP, ignore_errors=True)
    mvn("-Pquarkus", "-pl", "logaperture-agent,logaperture-cli,logaperture-sample-quarkus",
        "-am", "package", "-DskipTests", *props)
    if not (SAMPLE_APP / "quarkus-run.jar").is_file():
        sys.exit(f"error: expected {SAMPLE_APP}/quarkus-run.jar after build")
    shutil.rmtree(variant, ignore_errors=True)
    variant.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(SAMPLE_APP, variant)
    return variant / "quarkus-run.jar"


def ensure_sample(min_level, quarkus_version):
    jar = BUILDS / variant_name(min_level, quarkus_version) / "quarkus-run.jar"
    if is_stale(jar, SAMPLE_MODULES + AGENT_MODULES):
        return build_sample(min_level, quarkus_version)
    return jar


# --- process state ---------------------------------------------------------------

def read_pid():
    try:
        return int(PID_FILE.read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return None


def alive(pid):
    if pid is None:
        return False
    try:
        os.kill(pid, 0)
    except ProcessLookupError:
        return False
    except PermissionError:
        return True
    return True


def running_pid():
    pid = read_pid()
    return pid if alive(pid) else None


def http_get(port, path, timeout=2):
    with urllib.request.urlopen(f"http://127.0.0.1:{port}{path}", timeout=timeout) as r:
        return r.read().decode("utf-8", "replace")


def saved_port():
    try:
        return int((RUN / "app.port").read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return DEFAULT_PORT


# --- logctl ----------------------------------------------------------------------

def logctl(args, pid, java_home=None, check=False):
    cmd = [java_exe(java_home), "-jar", CLI_JAR, "--pid", pid, *args]
    return run(cmd, check=check)


# --- subcommands -------------------------------------------------------------------

def cmd_build(a):
    ensure_agent_and_cli()
    jar = build_sample(a.min_level, a.quarkus_version)
    print(f"built {jar.relative_to(REPO_ROOT)}")


def cmd_up(a):
    existing = running_pid()
    if existing is not None:
        sys.exit(f"error: the application is already running (pid {existing}). "
                 "Run `quarkusctl down` first.")
    ensure_agent_and_cli()
    if a.jar:
        run_jar = Path(a.jar).resolve()
        if not run_jar.is_file():
            sys.exit(f"error: no such jar: {run_jar}")
    else:
        run_jar = ensure_sample(a.min_level, a.quarkus_version)
    java = java_exe(a.java_home)

    agent_arg = []
    if a.vendor_defaults:
        vendor = Path(a.vendor_defaults).resolve()
        if not vendor.is_file():
            sys.exit(f"error: no such vendor defaults file: {vendor}")
        agent_arg.append(f"--vendor-defaults={vendor}")
    agent_arg.extend(a.agent_arg or [])
    javaagent = f"-javaagent:{AGENT_JAR}" + ("=" + ",".join(agent_arg) if agent_arg else "")

    cmd = [java, javaagent,
           f"-Dlogaperture.home={HOME}",
           f"-Dlogaperture.instanceId={INSTANCE_ID}",
           f"-Dquarkus.http.port={a.port}",
           "-Dquarkus.http.host=127.0.0.1"]
    if a.file_log:
        cmd += ["-Dquarkus.log.file.enabled=true", f"-Dquarkus.log.file.path={FILE_LOG}"]
    if a.debug or a.debug_suspend:
        suspend = "y" if a.debug_suspend else "n"
        cmd.append(f"-agentlib:jdwp=transport=dt_socket,server=y,suspend={suspend},"
                   f"address=*:{DEBUG_PORT}")
    cmd += ["-jar", str(run_jar)]

    RUN.mkdir(parents=True, exist_ok=True)
    HOME.mkdir(parents=True, exist_ok=True)
    print("+ " + " ".join(cmd), file=sys.stderr)
    if a.foreground:
        run_foreground(cmd, a)
        return
    out = open(APP_LOG, "ab")
    out.write(f"\n=== quarkusctl up {time.strftime('%Y-%m-%d %H:%M:%S')} ===\n".encode())
    out.flush()
    proc = subprocess.Popen(cmd, stdout=out, stderr=subprocess.STDOUT,
                            stdin=subprocess.DEVNULL, start_new_session=True)
    PID_FILE.write_text(str(proc.pid), encoding="utf-8")
    (RUN / "app.port").write_text(str(a.port), encoding="utf-8")

    if a.debug_suspend:
        print(f"\nStarted suspended (pid {proc.pid}). Attach 'Attach to Quarkus ({DEBUG_PORT})' "
              "in VSCode; the JVM runs premain only after you do.\n  tail: quarkusctl tail")
        return
    wait_for_ready(proc, a.port, java, a.java_home)
    print(f"\nQuarkus is up (pid {proc.pid}).\n"
          f"  endpoint: http://127.0.0.1:{a.port}/log\n"
          f"  try:      python3 dev/quarkus/quarkusctl.py logctl -- env")
    if a.debug:
        print(f"  debugger: attach to localhost:{DEBUG_PORT}")


def run_foreground(cmd, a):
    """Run the JVM attached to this terminal: its console is ours, Ctrl-C stops it. The pid
    file is still written, so `logctl`, `status` and `down` work from another terminal."""
    proc = subprocess.Popen(cmd, stdin=subprocess.DEVNULL)
    PID_FILE.write_text(str(proc.pid), encoding="utf-8")
    (RUN / "app.port").write_text(str(a.port), encoding="utf-8")
    print(f"\nRunning in the foreground (pid {proc.pid}); Ctrl-C stops it.\n"
          f"  endpoint: http://127.0.0.1:{a.port}/log\n"
          f"  logctl:   python3 dev/quarkus/quarkusctl.py logctl -- env  (from another terminal)\n",
          file=sys.stderr)
    def forward(signum, _frame):
        # A closed terminal (SIGHUP) or a killed task (SIGTERM) must not orphan the JVM.
        if proc.poll() is None:
            proc.send_signal(signal.SIGTERM)

    for signum in (signal.SIGTERM, signal.SIGHUP):
        signal.signal(signum, forward)
    try:
        while True:
            try:
                code = proc.wait()
                break
            except KeyboardInterrupt:
                pass    # the JVM got the same SIGINT and is shutting down; keep waiting
    finally:
        PID_FILE.unlink(missing_ok=True)
    sys.exit(0 if code in (0, 130, 143, -2, -15) else code)


def wait_for_ready(proc, port, java, java_home, timeout=120):
    deadline = time.time() + timeout
    answered = False
    while time.time() < deadline:
        if proc.poll() is not None:
            PID_FILE.unlink(missing_ok=True)
            sys.exit(f"error: the application exited with {proc.returncode}. See {APP_LOG}")
        try:
            answered = answered or "trace=" in http_get(port, "/log?tag=ready")
        except (urllib.error.URLError, OSError):
            pass
        if answered:
            r = run([java, "-jar", CLI_JAR, "--pid", proc.pid, "env"],
                    check=False, capture=True)
            if r.returncode == 0 and "quarkus" in r.stdout.lower():
                return
        time.sleep(0.5)
    sys.exit(f"error: not ready within {timeout}s. See {APP_LOG}")


def cmd_down(a):
    pid = read_pid()
    if not alive(pid):
        PID_FILE.unlink(missing_ok=True)
        print("not running")
        return
    # Only the process this driver started (the pid file), never a process it did not.
    os.kill(pid, signal.SIGTERM)
    deadline = time.time() + 20
    while time.time() < deadline and alive(pid):
        time.sleep(0.25)
    if alive(pid):
        os.kill(pid, signal.SIGKILL)
        time.sleep(0.5)
    PID_FILE.unlink(missing_ok=True)
    print(f"stopped pid {pid}")


def cmd_logctl(a):
    args = a.logctl_args
    if args and args[0] == "--":
        args = args[1:]
    if not args:
        sys.exit("usage: quarkusctl logctl -- <logctl args>   e.g. -- env")
    pid = running_pid()
    if pid is None:
        sys.exit("error: the application is not running. Start it with `quarkusctl up`.")
    ensure_agent_and_cli()
    sys.exit(logctl(args, pid, a.java_home).returncode)


def cmd_tail(a):
    if not APP_LOG.is_file():
        sys.exit(f"error: no log yet at {APP_LOG}")
    try:
        subprocess.run(["tail", "-n", "100", "-F", str(APP_LOG)])
    except KeyboardInterrupt:
        pass


def cmd_status(a):
    pid = running_pid()
    if pid is None:
        print("not running")
        return
    print(f"running: pid {pid}, http://127.0.0.1:{saved_port()}/log")
    print("\n--- logctl env ---", file=sys.stderr)
    logctl(["env"], pid)
    print("\n--- logctl status ---", file=sys.stderr)
    logctl(["status"], pid)


# --- argument parsing --------------------------------------------------------------

def add_variant_arguments(p):
    p.add_argument("--min-level", metavar="L",
                   help="build the sample with quarkus.log.min-level=L (e.g. TRACE)")
    p.add_argument("--quarkus-version", metavar="V",
                   help="build the sample against Quarkus V (e.g. the LTS 3.33.4)")


def build_parser():
    p = argparse.ArgumentParser(prog="quarkusctl", description=__doc__.split("\n\n")[0])
    sub = p.add_subparsers(dest="command", required=True)

    b = sub.add_parser("build", help="build the agent, logctl and the sample")
    add_variant_arguments(b)
    b.set_defaults(func=cmd_build)

    u = sub.add_parser("up", help="start the application with the agent attached")
    add_variant_arguments(u)
    u.add_argument("--jar", metavar="PATH",
                   help="run this quarkus-run.jar (or uber-jar) instead of the sample")
    u.add_argument("--port", type=int, default=DEFAULT_PORT, help=f"HTTP port (default {DEFAULT_PORT})")
    u.add_argument("--file-log", action="store_true",
                   help="enable Quarkus's file handler, so `file` appears in `list handlers`")
    u.add_argument("--vendor-defaults", metavar="FILE", help="pass --vendor-defaults=FILE to the agent")
    u.add_argument("--foreground", action="store_true",
                   help="run attached to this terminal (console on screen, Ctrl-C stops it)")
    u.add_argument("--debug", action="store_true", help=f"JDWP on {DEBUG_PORT}, suspend=n")
    u.add_argument("--debug-suspend", action="store_true",
                   help=f"JDWP on {DEBUG_PORT}, suspend=y (to step through premain)")
    u.add_argument("--java-home", metavar="DIR", help="run with this JDK (17, 21 or 25)")
    u.add_argument("--agent-arg", metavar="ARG", action="append",
                   help="extra agent argument, e.g. --storm-detection=on (repeatable)")
    u.set_defaults(func=cmd_up)

    d = sub.add_parser("down", help="stop the application this driver started")
    d.set_defaults(func=cmd_down)

    lc = sub.add_parser("logctl", help="run logctl against the application (args after --)")
    lc.add_argument("--java-home", metavar="DIR", help="run logctl with this JDK")
    lc.add_argument("logctl_args", nargs=argparse.REMAINDER)
    lc.set_defaults(func=cmd_logctl)

    t = sub.add_parser("tail", help="follow the application's output")
    t.set_defaults(func=cmd_tail)

    s = sub.add_parser("status", help="process state plus logctl env and status")
    s.set_defaults(func=cmd_status)
    return p


def main(argv=None):
    args = build_parser().parse_args(argv)
    args.func(args)


if __name__ == "__main__":
    main()
