# Script logctl

`logctl` works the same in a script, a cron job or a monitoring check as on a terminal, with two
differences: it never asks a question, and you can ask it for JSON.

## It never asks off a terminal

On a terminal, `logctl` asks for anything a command leaves out and lists matches to pick from. Off a
terminal it can't, so an incomplete command fails with exit code 2 and says what's missing:

```
'set' needs 'logger <target> <level>', 'handler <name> <level>', or 'default-handler <name> ...'.
Run this in a terminal to be prompted for the missing parts.
```

Give the whole command. Where a command would ask for confirmation, add `--yes`:

- a pattern target (`set logger '*.Worker' DEBUG --yes`, `add rule … '*.Worker' … --yes`) takes
  every match;
- `apply recipe … --yes` applies without showing the changes first.

`--json` never asks either.

## Pick the JVM explicitly

With several JVMs running the agent, `logctl` can't ask which one, and exits with code 4. In a
script, always pass `--pid`. See [Work with several JVMs](several-jvms.md).

## JSON

Add `--json` to any command except `export vendor-defaults` for one line of JSON on standard output:

```sh
logctl status --json | jq '.loggers[] | select(.tier == "STICKY") | .name'
```

The fields of each command are in [JSON output](../reference/json-output.md). From 1.0.0, fields
are only ever added within a major version, so ignore fields you don't know.

## Exit codes

| Code | Means |
|---|---|
| `0` | Done. |
| `1` | Unexpected error. |
| `2` | The command line is wrong, or incomplete off a terminal. |
| `3` | No JVM with the agent was found. |
| `4` | Several JVMs with the agent were found: pass `--pid`. |
| `5` | `logctl` couldn't attach to the JVM: run it as the user running the JVM. |
| `6` | The agent's policy doesn't allow this operation. |

`doctor` exits `0` whatever it finds; read its findings from `--json` to act on them.

## Examples

Raise a level for the length of a support call, with the ticket number in the audit trail:

```sh
logctl --pid "$PID" set logger com.acme.billing DEBUG for 45m --reason "$TICKET"
```

Fail a health check when `doctor` finds anything critical:

```sh
logctl --pid "$PID" doctor --json \
  | jq -e '[.findings[] | select(.severity == "CRITICAL")] | length == 0' > /dev/null
```

Undo everything a script set, keeping `sticky` changes made by people:

```sh
logctl --pid "$PID" reset loggers
logctl --pid "$PID" reset rules
```
