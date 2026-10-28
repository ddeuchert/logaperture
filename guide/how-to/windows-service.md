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
    elevated command prompt. Other service accounts (`LocalService`, `NetworkService`, a named
    account) and a prompt that is not elevated have not been tested.

## If `logctl` doesn't find it

```
No LogAperture-enabled JVM found. Start the application with -javaagent:logaperture-agent.jar.
(A JVM that runs as another account, such as a Windows service, may not be listed — find its PID and pass --pid <n>.)
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
