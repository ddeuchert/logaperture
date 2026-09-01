# LogAperture — evaluation bundle

Runtime logging control for the JVM: see and change log levels on a **running**
server, with changes that revert themselves on a timer and never touch
`standalone.xml`.

> ## ⚠️ PRE-PRODUCTION / EVALUATION USE ONLY
>
> This is an early, experimental build. **Do not run it in production.** There is
> no warranty and no support commitment, and the format of the agent's override
> store may change between builds without a migration path. It is for trying the
> tool in a disposable pre-production environment and telling us what breaks.

The version of this build is in the name of the archive you unzipped, and in
each jar's `Implementation-Version` manifest entry.

## What's here

```
bin/logctl            control CLI (POSIX shell launcher)
bin/logctl.cmd        control CLI (Windows launcher)
lib/logaperture-agent.jar   the Java agent — goes on -javaagent
lib/logaperture-cli.jar     the CLI's jar (invoked by the launchers)
docs/INSTALL-wildfly.md     install, verify, use, uninstall, limits
LICENSE               Apache License 2.0
```

## Start here

**[docs/INSTALL-wildfly.md](docs/INSTALL-wildfly.md)** — the WildFly install and
usage guide.

## Feedback

Issues and observations: https://github.com/ddeuchert/logaperture/issues

## Licence

Apache License 2.0 — see [LICENSE](LICENSE).
