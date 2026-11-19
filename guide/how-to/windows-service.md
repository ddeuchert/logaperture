# Use logctl with a Windows service

When the application runs as a Windows service, often as `LocalSystem`, you don't have to become
that account to use `logctl`. Open a command prompt **as Administrator** and run it from there:

```
logctl status
```

```
Vendor defaults: C:\wildfly\standalone\configuration\logaperture-vendor-defaults.yaml - loaded, no settings
Storm detection: disabled

No active overrides.
```

`logctl` finds the one JVM that runs LogAperture, even when the service started it. Other Java
processes on the machine are ignored. If several JVMs run LogAperture, `logctl` asks which one in a
terminal, or stops and asks for `--pid` in a script (see [Work with several
JVMs](several-jvms.md)).

!!! note "Tested on"
    Windows Server 2019, JDK 21.0.4, an application running as `LocalSystem`, `logctl` from an
    elevated command prompt. `logctl attach` was also checked from an elevated prompt against a
    `LocalSystem` JVM started without `-javaagent`. Other service accounts (`LocalService`,
    `NetworkService`, a named account) have not been tested.

## Start LogAperture in a service that is already running

If the service was started without `-javaagent`, load the agent into it from the same Administrator
prompt, with `logctl attach`. With no PID it lists the JVMs it can attach to, including a service's
JVM, and asks which one. To build that list it briefly attaches to each `java.exe` and `javaw.exe` the
machine runs, whoever owns it, and reads its system properties; it loads nothing into any of them until
you choose one. With the PID from `tasklist` (see below) it goes straight to it:

```
logctl attach 7100
```

Run from an elevated prompt against a WildFly service running as `LocalSystem`, started without
`-javaagent`, the list and the attach look like this (the user name in the path is replaced):

```
F:\wildfly-dev\logaperture\bin>logctl attach
1 JVM can be attached to:
  #  PID   STARTED  DIRECTORY                        COMMAND
  1  4484  ?        F:\wildfly-dev\wildfly\bin
Attach to which? (a number from the list, or a PID; Enter to cancel)
>

F:\wildfly-dev\logaperture\bin>logctl attach 4484
LogAperture 1.1.0-SNAPSHOT is active in PID 4484 (JBoss LogManager 2.1.18.Final).
  Agent jar: C:\Users\<you>\.logaperture\agent\logaperture-agent-1.1.0-SNAPSHOT-2857a059712b.jar (the agent jar carried inside this logctl)
  This lasts until PID 4484 stops. To keep it, add -javaagent:<agent jar> to its launch line.

F:\wildfly-dev\logaperture\bin>logctl status --pid 4484
Storm detection: disabled

No active overrides.
```

The service's JVM is not restarted. See [Try LogAperture without restarting](try-without-restart.md)
for what `attach` does and its limits.

### What `attach` says when it cannot attach

A PID that does not exist:

```
F:\wildfly-dev\logaperture\bin>logctl attach 999999
No process with PID 999999.
```

A JVM started with `-XX:-EnableDynamicAgentLoading`:

```
F:\wildfly-dev\logaperture\bin>logctl attach 13292
PID 13292 was started with dynamic agent loading turned off (-XX:-EnableDynamicAgentLoading), so no agent can be loaded into it. Restart it with -javaagent:logaperture-agent.jar, or without that flag.
(A future Java may turn dynamic loading off by default; the JVM would then need -XX:+EnableDynamicAgentLoading at launch, which is as much of a restart.)
```

From a prompt that is not elevated, against the service's JVM:

```
F:\wildfly-dev\logaperture\bin>logctl attach 9616
Can't attach to PID 9616 - open the command prompt as Administrator, or run as the account that owns that process.
```

## If `logctl` doesn't find it

```
No LogAperture-enabled JVM found. Start the application with -javaagent:logaperture-agent.jar.
(If the JVM runs LogAperture as another account, such as a Windows service, but is not listed, find its PID and pass --pid <n>.)
```

Check the prompt says *Administrator* in its title. Then find the JVM's process id and pass it:

```
tasklist /fi "imagename eq java.exe"
logctl status --pid 7100
```

`tasklist` lists every `java.exe`, whoever owns it:

```
Image Name                     PID Session Name        Session#    Mem Usage
========================= ======== ================ =========== ============
java.exe                      7100 Services                   0  7,656,836 K
```

The service's JVM is the one in session `Services`. With `--pid` the output is the same as above.

`sc queryex <service name>` gives the PID of the *service wrapper*, not the JVM. The JVM is its
child process, so use the PID from `tasklist`.

## Notes

- Use a JDK to run `logctl`, as everywhere (see [Troubleshooting](../troubleshooting.md)). If the
  command prompt has no `JAVA_HOME`, set it for that window.
- `logctl` is the account's own view, not a remote one: it must run on the same machine as the
  service.
- If attaching is refused, `logctl` exits with code 5 and says so; see [When attaching is
  refused](several-jvms.md#when-attaching-is-refused).
