# Try LogAperture without a restart

`logctl attach` loads the agent into a JVM that is already running, so you can try LogAperture on
an application without changing its launch line or restarting it. It works for an application on
Logback, on `java.util.logging`, and for a standalone WildFly.

It lasts until that JVM stops. To keep LogAperture, add the `-javaagent` option to the launch line
as in the [install guides](../get-started/install-plain-jvm.md).

With [JBang](run-with-jbang.md) installed, the whole trial needs nothing else: write
`jbang logctl@ddeuchert/logaperture` where this page says `logctl`.

## Attach

Find the process id of the application (`jps -l` lists Java processes), then:

```sh
logctl attach 1344371
```

```
LogAperture 1.1.0 is active in PID 1344371 (java.util.logging (JUL)).
  Agent jar: ~/.logaperture/agent/logaperture-agent-1.1.0-47ad08a130b3.jar (the agent jar carried inside this logctl)
  Java 21 prints a warning about dynamic agent loading on that JVM's own standard error. It is expected.
  This lasts until PID 1344371 stops. To keep it, add -javaagent:<agent jar> to its launch line.
```

Your version, process id and paths will differ. From here every `logctl` command works on that
JVM as it would after a normal start: `set logger`, `top`, `doctor`, rules.

On a terminal, `logctl attach` without a process id lists the JVMs it can attach to and asks which.
It asks even when there is only one, since the only other JVM on a machine is often an IDE or a build
daemon. Off a terminal, or with `--json`, it prints the list and exits with code 4: name the JVM.

## If nothing is active yet

LogAperture starts when the application first logs through Logback or `java.util.logging`. For an
application that is already logging, that is at once. If the application has not logged yet,
`attach` waits ten seconds and then says so:

```
The LogAperture agent is loaded in PID 1344371 but not active yet: it starts when the application
first logs through Logback or java.util.logging. Run 'logctl status' once it has.
```

The application's own standard error gets one line saying it is waiting. If a minute passes without
the application logging through either, a second line says which backends LogAperture looked for.
An application on another backend, such as Log4j 2, cannot be controlled.

## Attach twice

Attaching to a JVM that already runs LogAperture, from `-javaagent`, an earlier attach or the
Quarkus extension, loads nothing and exits 0, so a script can run it without checking first:

```
PID 1344371 already runs LogAperture 1.1.0, through the agent, from -javaagent or an earlier attach. Nothing was loaded.
```

## Options

`attach` takes the options the `-javaagent` line takes:

```sh
logctl attach 1344371 --vendor-defaults /etc/acme/vendor-defaults.yaml --storm-detection on
```

A relative path is resolved against the directory you run `logctl` from.

## Which agent jar is loaded

The `logctl` jar carries its own copy of the agent jar, written once to
`~/.logaperture/agent/` (or, when that can't be written, to `/tmp/logaperture-<user>/agent/`) and
never deleted, because the JVM keeps it open. A newer agent jar of the same major version next to the
`logctl` jar is used instead. `--agent-jar <file>` always wins.

A copy that is already there is reused only if its contents are exactly what `logctl` would write. Under
`/tmp`, `logctl` uses the directory only if you own it and nobody else can write to it. Each new `logctl`
version adds a copy of about 0.6 MB, and old ones are left alone, because a JVM you attached to earlier
may still be running from one. Delete copies that no running JVM uses.

The JVM you attach to opens that file, so it must exist where that JVM runs. For a JVM in a
container, run `logctl` inside the container, or pass `--agent-jar` with a path that exists there.

## Limits

- **Java 21 and later print a warning** on the application's own standard error when an agent is
  loaded dynamically. It is expected, and appears once.
- **A JVM started with `-XX:-EnableDynamicAgentLoading` refuses.** `logctl` says so and exits 5.
  Restart that JVM with `-javaagent`, or without the flag. A future Java may turn dynamic loading off
  by default, which would need `-XX:+EnableDynamicAgentLoading` when the JVM starts.
- **Same machine, same user.** As for every `logctl` command.
- **Logging that happened before the attach** is not seen. A `sticky` change from an earlier run is
  applied once LogAperture starts.
- **Tested on Linux.** The same attach mechanism exists on macOS and Windows, but it has not been
  tested there.
- **A Quarkus application** should use the [extension](../get-started/install-quarkus.md) instead.
  Spring Boot and Tomcat are not supported yet.

`doctor` reports an attached JVM with an `INFO` line, [`agent.attached`](../reference/doctor-checks.md#the-agent),
because the `-javaagent` order check has nothing to read.
