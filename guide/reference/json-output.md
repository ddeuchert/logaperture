# JSON output

Every `logctl` command except `export vendor-defaults` takes `--json`, and then prints one line of
JSON on standard output instead of text. With `--json`, `logctl` never asks a question: a command
that would need an answer fails instead, as it does off a terminal.

The examples below are formatted for reading. They were captured from WildFly 26.1.3.

## What stays the same

From 1.0.0, within a major version:

- A field is never removed or renamed, and never changes its type or meaning.
- New fields can appear in any release. **Ignore fields you don't know.**
- New values can appear in fields that hold a name from a fixed set, such as a severity or a tier.

The same promise covers the agent's management interface; see the
[versioning rules](https://github.com/ddeuchert/logaperture/blob/main/DEVELOPMENT.md#versioning-semver-and-what-forces-a-major-bump).

## Conventions

- Times are UTC, in ISO-8601: `2026-10-09T05:42:12.441226621Z`.
- Levels are names: `TRACE`, `DEBUG`, `INFO`, `WARN`, `ERROR`, `OFF`, `ALL`.
- Tiers are `SESSION`, `FOR` or `STICKY`. `expiresAt` is set for `FOR` and `null` otherwise.
- A field that doesn't apply is `null`, not left out.
- `source` names where a change came from: `jmx` for a change made with `logctl`, `resume` for a
  `sticky` change restored at startup, `expiry-sweep` and `verification-sweep` for LogAperture's
  own periodic work, `vendor-defaults` and `recipe` for changes from those.
- `context` names the logging context a row belongs to; on WildFly the server's own is `system`.
- On a failure, the exit code says what went wrong (below) and the message goes to standard
  error, as text.

## Exit codes

| Code | Means |
|---|---|
| `0` | Done. |
| `1` | Unexpected error. |
| `2` | The command line is wrong. |
| `3` | No JVM with the agent was found. |
| `4` | Several JVMs with the agent were found, and `logctl` couldn't ask which: pass `--pid`. |
| `5` | `logctl` couldn't attach to the JVM: run it as the user running the JVM. |
| `6` | The agent's policy doesn't allow this operation. |

## Loggers

### list loggers

The loggers shown, one object per logger, under `loggers`.

```sh
logctl list loggers io.undertow --json
```

```json
{
  "loggers": [
    {
      "name": "io.undertow.request",
      "configuredLevel": null,
      "effectiveLevel": "DEBUG",
      "overrideActive": true,
      "overrideSource": "jmx",
      "overrideReason": "demo",
      "tier": "FOR",
      "expiresAt": "2026-10-10T04:32:09.361891539Z",
      "vendorDefaultLevel": null,
      "resetToNative": false,
      "recipe": null,
      "forcedBy": null
    }
  ]
}
```

### status

The loggers with an override (same fields as `list loggers`), the handlers with one, the rules
(same object per rule as [`add rule`](#add-rule), leaving out vendor rules nobody has changed), the
vendor defaults file if any, and storm detection's state.

```sh
logctl status --json
```

```json
{
  "loggers": [
    {
      "name": "com.example.kept",
      "configuredLevel": null,
      "effectiveLevel": "DEBUG",
      "overrideActive": true,
      "overrideSource": "jmx",
      "overrideReason": null,
      "tier": "STICKY",
      "expiresAt": null,
      "vendorDefaultLevel": null,
      "resetToNative": false,
      "recipe": null,
      "forcedBy": null
    }
  ],
  "handlerOverrides": [],
  "rules": [
    {
      "id": "r1",
      "loggerName": "io.undertow.request",
      "action": "drop",
      "levelAtMost": "INFO",
      "messageContains": "Matched default",
      "messageIgnoreCase": false,
      "throwableType": null,
      "throwableMessageContains": null,
      "anyCause": false,
      "reason": "health checks",
      "tier": "FOR",
      "expiresAt": "2026-10-10T07:33:38.543016798Z",
      "createdAt": "2026-10-10T03:33:38.543016798Z",
      "context": "system",
      "hitCount": 0,
      "frames": null,
      "collapseCauses": null,
      "origin": null,
      "toNative": false,
      "altered": false,
      "recipe": null,
      "sampleFullEnabled": true,
      "sampleFullEveryMillis": 300000,
      "expression": "--message-contains \"Matched default\" --below WARN --sample-full 5m"
    }
  ],
  "vendorDefaults": null,
  "stormDetection": {
    "enabled": false,
    "changedAt": null,
    "tier": "SESSION",
    "expiresAt": null
  }
}
```

### set logger

The overrides made (several for a pattern target or `--force`), the handlers whose level would
hide the new output (`warnings`), and the descendants `--force` reached.

```sh
logctl set logger io.undertow.request DEBUG for 30m --reason demo --json
```

```json
{
  "overrides": [
    {
      "loggerName": "io.undertow.request",
      "level": "DEBUG",
      "reason": "demo",
      "appliedAt": "2026-10-09T05:12:12.441226621Z",
      "source": "jmx",
      "tier": "FOR",
      "expiresAt": "2026-10-09T05:42:12.441226621Z",
      "forcedBy": null
    }
  ],
  "warnings": [
    {
      "handlerRef": "CONSOLE",
      "currentLevel": "INFO"
    }
  ],
  "descendants": []
}
```

### reset

`reset logger` prints the logger after the reset, plus what it did to rules and forced
descendants under it. `reset loggers` and `reset handlers` print what was reverted and which
`sticky` overrides were skipped. `reset rule` prints the rule's fate.

```sh
logctl reset logger io.undertow.request --json
```

```json
{
  "name": "io.undertow.request",
  "configuredLevel": null,
  "effectiveLevel": "INFO",
  "overrideActive": false,
  "overrideSource": null,
  "overrideReason": null,
  "tier": null,
  "expiresAt": null,
  "vendorDefaultLevel": null,
  "resetToNative": false,
  "recipe": null,
  "forcedBy": null,
  "removedRuleIds": [],
  "skippedStickyRuleIds": [],
  "vendorResetRuleIds": [],
  "forcedRevertedLoggerNames": [],
  "forcedSkippedStickyLoggerNames": []
}
```

```sh
logctl reset handlers --json
```

```json
{
  "revertedHandlerRefs": [
    "CONSOLE"
  ],
  "skippedStickyHandlerRefs": []
}
```

```sh
logctl reset rule r1 --json
```

```json
{
  "id": "r1",
  "removed": true,
  "vendorReset": false,
  "toNative": false
}
```

## Handlers

### list handlers

```sh
logctl list handlers --json
```

```json
{
  "handlers": [
    {
      "ref": "CONSOLE",
      "level": "DEBUG",
      "persistent": false,
      "targetPath": null,
      "autoFlush": true,
      "overrideActive": true,
      "overrideLevel": "DEBUG",
      "overrideMode": "FIXED",
      "overrideTier": "FOR",
      "overrideExpiresAt": "2026-10-09T05:22:26.576398427Z",
      "membersSummary": null,
      "context": "system",
      "vendorDefault": null,
      "resetToNative": false,
      "recipe": null
    }
  ]
}
```

### set handler

```sh
logctl set handler CONSOLE DEBUG for 10m --json
```

```json
{
  "handlerRef": "CONSOLE",
  "level": "DEBUG",
  "mode": "FIXED",
  "reason": null,
  "appliedAt": "2026-10-09T05:12:26.576398427Z",
  "source": "jmx",
  "tier": "FOR",
  "expiresAt": "2026-10-09T05:22:26.576398427Z",
  "warnings": []
}
```

## Rules

### add rule

The rule as created. `levelAtMost` is the most severe level it acts on: `--below ERROR` makes it
`WARN`. `expression` is the rule's options in the form `alter rule` takes. `list rules` prints
`{"rules": [...]}` with the same object per rule.

```sh
logctl add rule drop io.undertow.request --message-contains "Matched default" --json
```

```json
{
  "id": "r1",
  "loggerName": "io.undertow.request",
  "action": "drop",
  "levelAtMost": "WARN",
  "messageContains": "Matched default",
  "messageIgnoreCase": false,
  "throwableType": null,
  "throwableMessageContains": null,
  "anyCause": false,
  "reason": null,
  "tier": "FOR",
  "expiresAt": "2026-10-09T09:12:24.572167230Z",
  "createdAt": "2026-10-09T05:12:24.572167230Z",
  "context": "system",
  "hitCount": 0,
  "frames": null,
  "collapseCauses": null,
  "origin": null,
  "toNative": false,
  "altered": false,
  "recipe": null,
  "sampleFullEnabled": true,
  "sampleFullEveryMillis": 300000,
  "expression": "--message-contains \"Matched default\" --below ERROR --sample-full 5m"
}
```

### alter rule

The rule after the change, with every field `add rule` prints (shortened here), plus what it was
before and whether anything changed.

```sh
logctl alter rule r1 --below WARN --json
```

```json
{
  "id": "r1",
  "levelAtMost": "INFO",
  "expression": "--message-contains \"Matched default\" --below WARN --sample-full 5m",
  "previousExpression": "--message-contains \"Matched default\" --below ERROR --sample-full 5m",
  "previousTier": "FOR",
  "previousExpiresAt": "2026-10-09T09:12:24.572167230Z",
  "changed": true
}
```

## Diagnostics

### doctor

`check` is one of the [check ids](doctor-checks.md). `checksRun` counts the checks that ran. Two
of the findings are shown here.

```sh
logctl doctor --json
```

```json
{
  "findings": [
    {
      "check": "handler.unbounded-growth",
      "severity": "WARNING",
      "subject": "FILE",
      "summary": "FILE has no size cap — writes are unbounded.",
      "detail": "no size-based rotation is configured",
      "suggestedFix": "configure a size-based rotation policy on FILE.",
      "context": "system"
    },
    {
      "check": "disk.headroom",
      "severity": "OK",
      "subject": "overlay:overlay",
      "summary": "/opt/jboss/wildfly/standalone/log/server.log is stable — no writes observed during the sample window.",
      "detail": null,
      "suggestedFix": null,
      "context": "system"
    }
  ],
  "checksRun": 5
}
```

### top

Byte counts since `measurementStartedAt`, worst first. The text output's rates and daily
projections are worked out from these.

```sh
logctl top --limit 2 --json
```

```json
{
  "loggers": [
    {
      "loggerName": "org.logaperture.sample.work.Worker",
      "totalBytes": 1170,
      "stackTraceBytes": 0,
      "context": "system"
    },
    {
      "loggerName": "org.wildfly.extension.undertow",
      "totalBytes": 864,
      "stackTraceBytes": 0,
      "context": "system"
    }
  ],
  "measurementStartedAt": "2026-10-09T04:56:07.092712548Z",
  "trackedCount": 14
}
```

### storms

```sh
logctl storms --json
```

```json
{
  "storms": [
    {
      "loggerName": "io.undertow.request",
      "level": "DEBUG",
      "throwableClass": null,
      "normalizedMessage": "Matched default handler path %s",
      "topFrames": null,
      "status": "ONGOING",
      "firstEventAt": "2026-10-10T02:41:22.667701316Z",
      "lastEventAt": "2026-10-10T02:41:26.038228375Z",
      "endedAt": null,
      "eventCount": 1500,
      "firstOccurrence": "2026-10-10T02:41:24.990134470Z DEBUG [io.undertow.request] Matched default handler path /x997",
      "sampleEventNumber": 1000,
      "context": "system"
    }
  ],
  "trackedCount": 1,
  "ongoingCount": 1,
  "measurementStartedAt": "2026-10-10T02:41:21.497247862Z",
  "notRetainedCount": 0,
  "detectionEnabled": true,
  "detectionChangedAt": "2026-10-10T02:41:21.486477057Z"
}
```

`firstOccurrence` is the storm's sample: the event that took it over the threshold, with its
message as logged and its stack trace, if any. `sampleEventNumber` says which event of the storm
that was, counted from `firstEventAt`; it is `0` from an older agent that doesn't report it.

### enable storms, disable storms

```sh
logctl enable storms --json
```

```json
{
  "enabled": true,
  "previous": false,
  "changedAt": "2026-10-09T05:12:31.219692216Z",
  "tier": "SESSION",
  "expiresAt": null
}
```

### env

```sh
logctl env --json
```

```json
{
  "agentVersion": "1.0.0-beta.1-SNAPSHOT",
  "cliVersion": "1.0.0-beta.1-SNAPSHOT",
  "javaVersion": "17.0.5",
  "javaVendor": "Eclipse Adoptium",
  "osName": "Linux",
  "osVersion": "7.2.8-200.fc44.x86_64",
  "osArch": "amd64",
  "backendName": "JBoss LogManager",
  "backendVersion": "2.1.18.Final",
  "containerName": "WildFly",
  "containerVersion": "26.1.3.Final",
  "diagnosticsLevel": null,
  "stateFilePath": "/opt/jboss/wildfly/standalone/tmp/logaperture/instances/af001a751ad6462d-jboss.state.yaml",
  "vendorDefaultsPath": null,
  "vendorDefaultsStatus": "not configured"
}
```
