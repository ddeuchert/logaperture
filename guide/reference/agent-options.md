# Agent options and properties

The agent takes two kinds of settings: **agent arguments**, written after the jar on the
`-javaagent` line, and **system properties**, given as `-D` options to the same JVM.

## Agent arguments

```sh
-javaagent:/opt/logaperture/lib/logaperture-agent.jar=--vendor-defaults=/etc/myproduct/logging.yaml,--storm-detection=on
```

Arguments are `--name=value`, separated by commas. A comma only separates arguments when the next
one starts with `--`, so a path containing a comma needs no quoting. An unknown or malformed
argument is skipped with a warning in the log; the agent still starts.

`--vendor-defaults=<file>`
:   A vendor defaults file: logging settings that replace the application's own for every logger,
    handler and rule it names. A relative path is resolved against the JVM's working directory. If
    the file has an error, none of it applies, and `doctor` says why. See
    [Vendor defaults](../vendors/vendor-defaults.md) and the [file format](file-formats.md#vendor-defaults-file).

`--storm-detection=on|off`
:   Whether storm detection starts on. Default `off`. A `sticky` `logctl enable storms` or
    `disable storms` from an earlier run wins over this. See [Log storms](../how-to/storms.md).

## System properties

`-Dlogaperture.disabled=true`
:   Turn the agent off completely: it starts nothing and changes nothing. Use it to check whether
    LogAperture is involved in a startup problem, without removing the `-javaagent` line.

`-Dlogaperture.home=<dir>`
:   Where LogAperture keeps its state files, and where it looks for the recipes folder. Default
    `~/.logaperture` of the user running the JVM. Set it when that user has no writable home
    directory, as is common in containers.

`-Dlogaperture.instanceId=<name>`
:   Names this JVM's state file. By default the state file is named after the directory the JVM was
    started from; two JVMs started from the same directory need different names, or the second
    can't keep `sticky` changes.

`-Dlogaperture.recipes=<dir>`
:   The recipes folder. Default `<logaperture.home>/recipes`. See [Use recipes](../how-to/recipes.md).

`-Dlogaperture.audit.file=<path>`
:   Append the [audit trail](audit-log.md) to this file instead of standard error.

`-Dlogaperture.diagnostics.level=ERROR|WARN|INFO|DEBUG`
:   How much LogAperture says about itself in the log. Default `WARN`: the startup line, then only
    warnings and errors. `ERROR` hides the startup line too; `INFO` and `DEBUG` say more.

`-Dlogaperture.drop.summaryInterval=<duration>`
:   How often a log line reports what `drop` rules discarded, such as `30m`. Default `10m`, at
    least `1m`. It can't be turned off: dropped events are never hidden. On WildFly the line is
    logged under the `org.logaperture.drop` category, so it can be raised, lowered or sent to its
    own file like any other category.

`-Dlogaperture.handlerInstallDelaySeconds=<n>`
:   On WildFly, hold back the parts of LogAperture that wrap the server's handlers (`drop` and
    `trim` rules, `top`, storm detection) for `n` seconds after startup. Default `0`, at most `600`.
    The log says when they are installed. Level changes always apply at once. Only for a launch
    that needs it.

## Internal settings

These tune LogAperture's own bookkeeping. They are listed so you can recognize them in a bug report
or an `env` output, but they **may change or disappear in any release**, and the defaults are the
supported configuration.

| Property | Default | What it sets |
|---|---|---|
| `logaperture.sweep.seconds` | `30` | How often, in seconds (1–3600), LogAperture expires timed changes and re-applies changes the server undid. A `for` change can revert up to this much late. |
| `logaperture.top.maxTrackedLoggers` | `1000` | How many loggers `top` counts. |
| `logaperture.storm.thresholdEvents` | `1000` | Near-identical events within the window that make a storm. |
| `logaperture.storm.windowSeconds` | `10` | The window for the threshold. |
| `logaperture.storm.quietSeconds` | `60` | Quiet time after which a storm counts as over. |
| `logaperture.storm.maxTrackedFingerprints` | `4000` | How many distinct messages storm detection watches at once. |
| `logaperture.storm.maxHistory` | `100` | How many storms `logctl storms` remembers. |
| `logaperture.storm.firstOccurrenceBytes` | `8192` | How much of a storm's first event is kept. |
| `logaperture.storm.normalizationCacheSize` | `1024` | A cache for storm detection's message matching; `0` turns it off. |
| `logaperture.wildfly.handlerNames.debug` | `false` | Log how WildFly handler names were resolved. |

`logaperture.version` is set by the agent itself once it is running; `logctl` uses it to find JVMs
with the agent. Don't set it.
