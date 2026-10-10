# Audit log

Every change LogAperture makes is written to the audit trail: the changes you make with `logctl`,
and the ones LogAperture makes by itself, such as a timed change running out. A revert is recorded
as well as the change, so the trail shows when a change ended, not just when it started.

The audit trail goes to the JVM's standard error, so on WildFly it appears on the console. Send it
to a file of its own with `-Dlogaperture.audit.file=<path>`; LogAperture appends to the file.
Rules can't drop audit lines: they don't go through the application's logging.

## Format

One line per change:

```
[logaperture-audit] 2026-10-09T05:12:26.576398427Z action=MUTATION principal=jboss source=jmx handler=CONSOLE previous=INFO new=DEBUG reason=
```

| Field | Contains |
|---|---|
| timestamp | When, in UTC. |
| `action` | `MUTATION` for a change, `REVERSION` for undoing one. |
| `principal` | The operating-system user the JVM runs as, which is also the user `logctl` has to run as. |
| `source` | What made the change: `jmx` for `logctl`; `expiry-sweep` for a timed change running out; `verification-sweep` for re-applying a change the server undid; `resume` for `sticky` changes restored at startup; `vendor-defaults` for the vendor defaults file; `recipe` for a recipe. |
| target | What changed, labelled by kind: `logger=<name>` for a logger's level or a rule on it; `handler=<name>` for a handler's level or `DEFAULT_HANDLERS`; `file=<path>` for a whole file, such as the vendor defaults file loaded at startup; `switch=storm-detection` for turning storm detection on or off. |
| `previous`, `new` | The value before and after. For a rule, its id, action and options. `<inherited>` means the logger has no level of its own. |
| `reason` | The `--reason` given, or empty. |
| `origin` | Only on a change made by a recipe: which one. |

The target labels let you search for one kind of change: `grep 'handler='` finds every handler
change without matching a logger that happens to share a name.

## Examples

A level change, and its reset:

```
[logaperture-audit] 2026-10-09T04:56:22.540201163Z action=MUTATION principal=jboss source=jmx logger=io.undertow.request previous=INFO new=DEBUG reason=
[logaperture-audit] 2026-10-09T05:12:29.297118383Z action=REVERSION principal=jboss source=jmx logger=io.undertow.request previous=DEBUG new=<inherited> reason=
```

A rule added, changed and removed:

```
[logaperture-audit] 2026-10-09T05:12:24.572167230Z action=MUTATION principal=jboss source=jmx logger=io.undertow.request previous=null new=r1 (drop) reason=
[logaperture-audit] 2026-10-09T05:12:25.905395437Z action=MUTATION principal=jboss source=jmx logger=io.undertow.request previous=r1 (drop) --message-contains "Matched default" --below ERROR --sample-full 5m [FOR until 2026-10-09T09:12:24.572167230Z, 0 hits] new=r1 (drop) --message-contains "Matched default" --below WARN --sample-full 5m [FOR until 2026-10-09T09:12:24.572167230Z] reason=
[logaperture-audit] 2026-10-09T05:12:28.630269369Z action=REVERSION principal=jboss source=jmx logger=io.undertow.request previous=r1 (drop) new=null reason=
```

Storm detection switched on:

```
[logaperture-audit] 2026-10-09T05:12:31.219692216Z action=MUTATION principal=jboss source=jmx switch=storm-detection previous=off new=on reason=
```
