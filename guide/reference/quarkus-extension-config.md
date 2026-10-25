# Quarkus extension configuration

!!! note "New in 1.1"
    The Quarkus extension arrives in LogAperture 1.1.

With the [Quarkus extension](../get-started/install-quarkus.md), LogAperture's settings are Quarkus
configuration properties. Set them in `application.properties`, as environment variables, or in a
Kubernetes ConfigMap: Quarkus reads them when the application starts, so one build serves every
environment. Most applications need none of them.

Each property has the environment-variable form Quarkus gives every property: upper case, with `.`
and `-` as `_`. `quarkus.logaperture.home` is `QUARKUS_LOGAPERTURE_HOME`.

A `-Dlogaperture.*` [system property](agent-options.md#system-properties) given on the command line
still works, and wins over the property here.

| Property | Default | With the agent | What it sets |
|---|---|---|---|
| `quarkus.logaperture.enabled` | `true` | `-Dlogaperture.disabled=true` | `false` turns LogAperture off completely: it starts nothing and changes nothing. |
| `quarkus.logaperture.vendor-defaults` | none | `--vendor-defaults=` | A [vendor defaults](../vendors/vendor-defaults.md) file. A relative path is resolved against the working directory. It replaces a file bundled in the application. |
| `quarkus.logaperture.bundled-vendor-defaults` | `true` | none | `false` starts the application without the vendor defaults file [bundled in it](../vendors/vendor-defaults.md#in-a-quarkus-application) at `META-INF/logaperture/vendor-defaults.yaml`. |
| `quarkus.logaperture.storm-detection` | `false` | `--storm-detection=` | Whether storm detection starts on. A `sticky` `logctl enable storms` or `disable storms` wins. See [Log storms](../how-to/storms.md). |
| `quarkus.logaperture.home` | `~/.logaperture` | `-Dlogaperture.home` | Where the state files are kept, and where the recipes folder is looked for. Set it when the application's user has no writable home directory, as in many containers. |
| `quarkus.logaperture.instance-id` | the working directory | `-Dlogaperture.instanceId` | Names this application's state file. Two applications started from the same directory need different names. |
| `quarkus.logaperture.recipes` | `<home>/recipes` | `-Dlogaperture.recipes` | The recipes folder. See [Use recipes](../how-to/recipes.md). |
| `quarkus.logaperture.audit.file` | standard error | `-Dlogaperture.audit.file` | Append the [audit trail](audit-log.md) to this file. |
| `quarkus.logaperture.diagnostics.level` | `WARN` | `-Dlogaperture.diagnostics.level` | How much LogAperture says about itself: `ERROR`, `WARN`, `INFO` or `DEBUG`. |
| `quarkus.logaperture.drop.summary-interval` | `10m` | `-Dlogaperture.drop.summaryInterval` | How often a log line reports what `drop` rules discarded. At least `1m`. |
| `quarkus.logaperture.follow-handlers` | `true` | `-Dlogaperture.followHandlers` | `false` stops the default handlers following `set logger`, as `--keep-handlers` does on every call. |

For example, in a container:

```sh
QUARKUS_LOGAPERTURE_HOME=/tmp/logaperture
QUARKUS_LOGAPERTURE_STORM_DETECTION=true
```

## The minimum level

The extension also sets one Quarkus property: `quarkus.log.min-level=TRACE`, so `logctl` can set any
logger to `TRACE` without a rebuild. It is a default with a low priority: a `quarkus.log.min-level`
in your own `application.properties` wins. See
[The build's minimum level](../get-started/install-quarkus.md#the-builds-minimum-level).

The [internal settings](agent-options.md#internal-settings) have no extension property. Set them
with `-D` if you need one.
