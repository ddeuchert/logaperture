# File formats

LogAperture reads two kinds of file you write: the **vendor defaults file** and **recipe files**.
Both are YAML, in a deliberately small subset.

## The YAML subset

Supported: maps, lists of maps, one-line lists (`[CONSOLE, FILE]`), plain and double-quoted values,
`#` comments, and multi-line text written as `key: |` followed by indented lines.

Not supported: tabs for indentation, anchors and aliases (`&`, `*`), several documents in one file,
and the other multi-line forms (`>`, `|-`, `|+`). A file that uses them is rejected.

A file is checked as a whole before any of it is used, and every error is reported with its line
number, not just the first. Unknown keys are errors, so a typo like `levle:` is caught.

## Vendor defaults file

Given to the agent with `--vendor-defaults=<file>`. Every setting it names replaces the
application's own; see [Vendor defaults](../vendors/vendor-defaults.md). If the file has any error,
none of it applies: `logctl doctor` lists the errors.

```yaml
schemaVersion: 1

loggers:
  - name: org.hibernate.SQL
    level: WARN
  - name: com.acme.support
    level: DEBUG
    reason: "support asked for this by default"

handlers:
  - name: FILE
    level: INFO
  - name: CONSOLE
    level: AUTO

defaultHandlers: [CONSOLE]

rules:
  - id: healthcheck-noise
    action: drop
    logger: com.acme.health
    messageContains: "ping ok"
    below: WARN
    reason: "load balancer health probes"
  - id: autoupdate-trace
    action: trim
    logger: com.acme.AutoUpdateHelper
    throwable: java.net.ConnectException
    frames: 0
```

| Key | Required | Contains |
|---|---|---|
| `schemaVersion` | yes | `1` |
| `loggers` | no | `name` (an exact logger name, no `*`), `level`, `reason` |
| `handlers` | no | `name` (as `logctl list handlers` shows it), `level` (a level or `AUTO`), `reason` |
| `defaultHandlers` | no | the handlers `DEFAULT_HANDLERS` means |
| `rules` | no | `drop` and `trim` rules, below |
| `namespace` | with `recipes` | the namespace of the file's recipe ids |
| `recipes` | no | recipes the file offers, as in a [recipe file](#recipe-files); loading the file doesn't apply them |

A handler named in the file that doesn't exist yet is not an error: it is applied when the handler
appears, and `doctor` reports it until then.

### Rules

Each rule's fields are named after the `logctl add rule` options. Fields left out take the
`add rule` defaults. Vendor rules have no tier: they last as long as the file is in use.

| Field | For | `logctl add rule` option |
|---|---|---|
| `id` (required) | both | none. Lowercase letters, digits and `-`, up to 40. The rule's id becomes `vendor:<id>`. |
| `action` (required) | both | `drop` or `trim` |
| `logger` (required) | both | the target; an exact logger name |
| `below` | both | `--below`; a level, or `FATAL` for every level |
| `messageContains`, `messageContainsIgnoreCase` | both | `--message-contains`, `--message-contains-ignore-case` |
| `throwable` | both | `--throwable` |
| `throwableMessageContains` | both | `--throwable-message-contains` |
| `anyCause` | both | `--any-cause` (`true` or `false`) |
| `sampleFull` | `drop` | `--sample-full`, a duration; or `false` for `--no-sample-full` |
| `frames` | `trim` | `--frames` |
| `collapseCauses` | `trim` | `--collapse-causes` (`true` or `false`) |
| `reason` | both | `--reason` |

### Files written by `export vendor-defaults`

`logctl export vendor-defaults` writes this format. Entries that came from a `sticky` change carry an
extra `stateId` field (and the file may have `handlerGroupStateIds` and `defaultHandlersStateId`).
They let a restart with the new file take over those changes instead of applying them twice. A
file you write by hand never needs them.

## Recipe files

A recipe is a named set of settings for watching one thing. Recipe files come from three places:

| Source | Where | May contain |
|---|---|---|
| A library | `META-INF/logaperture/recipes.yaml` on its class path | logger levels that raise verbosity |
| The vendor defaults file | its `recipes:` key | logger levels, handler levels, rules |
| The recipes folder | any `*.yaml` in `~/.logaperture/recipes`, or the folder named by `-Dlogaperture.recipes` | logger levels, handler levels, rules |

```yaml
schemaVersion: 1
namespace: io.undertow
recipes:
  - name: sessions
    summary: Watch HTTP session creation, expiry and invalidation
    description: |
      Logs each session create/expire/invalidate with its id. Moderate volume
      under load. Session ids are sensitive; don't leave this on.
    loggers:
      - name: io.undertow.server.session
        level: DEBUG
        reason: session lifecycle events
      - name: io.undertow.request
        level: DEBUG
```

| Key | Required | Contains |
|---|---|---|
| `schemaVersion` | yes | `1` |
| `namespace` | yes | lowercase letters, digits, `.` and `-`, up to 64. A reverse domain you control, such as a library's Maven group id. `vendor`, `logaperture` and `org.logaperture` are reserved. |
| `recipes` | yes | the recipes |
| `recipes[].name` | yes | lowercase letters, digits and `-`, up to 40. The recipe's id is `<namespace>:<name>`. |
| `recipes[].summary` | yes | one line, shown by `list recipes` |
| `recipes[].description` | no | longer text, shown by `show recipe` |
| `recipes[].loggers` | | `name`, `level`, `reason`, as in the vendor defaults file |
| `recipes[].handlers` | | as in the vendor defaults file; not in a library's file |
| `recipes[].rules` | | as in the vendor defaults file, with `id` optional; not in a library's file |

A recipe must change at least one thing. A file with an error offers none of its recipes;
`logctl list recipes --verbose` and `doctor` report it. A library's recipe that would lower a level
is skipped, since a library can only offer more visibility, never less.

Where a library's file goes in a war or an ear is covered in
[Recipes for library authors](../vendors/library-recipes.md).
