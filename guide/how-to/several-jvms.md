# Work with several JVMs

`logctl` controls one JVM per command. This page covers how it picks that JVM.

## How `logctl` finds the JVM

`logctl` looks for running JVMs that have the agent started, among those it is allowed to attach to:
on the same machine, run by the same operating-system user.

- **Exactly one**: it uses that one. Nothing to do.
- **None**: it says so and exits with code 3:

    ```
    No LogAperture-enabled JVM found. Start the application with -javaagent:logaperture-agent.jar.
    ```

    Check that the JVM was started with the agent, and that you are the user it runs as.

- **Several**: on a terminal, it lists them as a numbered list to pick from. In a script, or with
  `--json` or `--yes`, it can't ask. It prints the same list and exits with code 4:

    ```
    PID     VERSION                STARTED           DIRECTORY        COMMAND
    320862  1.0.0-beta.1-SNAPSHOT  2026-10-09 20:28  /srv/app-a       org.logaperture.agent.it.FixtureApp
    320867  1.0.0-beta.1-SNAPSHOT  2026-10-09 20:28  /srv/app-b       org.logaperture.agent.it.FixtureApp
    Several candidates — pass --pid <n>.
    ```

## Choosing one

Pass the process id:

```sh
logctl --pid 320862 status
```

The list shows each JVM's directory and main class to tell them apart. `jps -l` lists Java
processes too.

## When attaching is refused

```
Can't attach to PID 1 — run as the user that owns that process, or as root.
```

Exit code 5. Run `logctl` as the user the JVM runs as, for example with `sudo -u jboss logctl …`.

## Two applications from one directory

Each JVM keeps its `sticky` changes in its own state file, named after the directory it was started
from. Two JVMs started from the same directory can't share one: the second to start can't keep
`sticky` changes, and says so in its log. Give each its own name with
`-Dlogaperture.instanceId=<name>`. See [Agent options](../reference/agent-options.md).
