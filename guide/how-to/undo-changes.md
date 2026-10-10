# Undo changes

Everything `logctl` changes can be undone, and timed changes undo themselves. This page covers
undoing them sooner.

The output on this page is from WildFly 26.1.3.

## See what is changed

```sh
logctl status
```

```
Storm detection: disabled

LOGGER            LEVEL  TIER    REVERTS      REASON
com.example.kept  DEBUG  STICKY  until reset  —

ID  LOGGER               ACTION  TIER  EXPIRES               HITS
r1  io.undertow.request  drop    FOR   07:33:38 (in 3h 59m)  0
```

`status` lists every logger and handler with a change on it and every rule, plus the vendor
defaults file if there is one. A rule from the vendor defaults file appears only once you have
altered it or switched it off. `logctl list rules --verbose` adds what each rule matches.

## Reset

| Command | Undoes |
|---|---|
| `reset logger <name>` | the change on that logger, on loggers `--force` set with it, and the rules attached to it |
| `reset logger '<pattern>'` | the changes on every logger matching the pattern, such as `'com.example.*'` or `'*.Worker'` |
| `reset loggers` | every logger change |
| `reset handler <name>`, `reset handlers` | the change on one handler, or all of them |
| `reset rule <id>`, `reset rules` | one rule, or all of them |
| `reset recipe <id>` | the changes a recipe made that you haven't changed since ([Use recipes](recipes.md)) |
| `reset default-handler` | the `DEFAULT_HANDLERS` membership ([command reference](../reference/logctl.md#default-handler)) |

```sh
logctl reset logger 'com.example.*'
```

```
com.example.temp → INFO (baseline)
Left 1 sticky override(s) in place (pass --include-sticky to include them): com.example.kept
```

On a terminal, `logctl reset` alone lists everything currently changed, to pick from.

## `sticky` changes are skipped

A `sticky` change was made to last, so a reset leaves it alone unless you add `--include-sticky`.
A pattern or bulk reset leaves it and says so, as above. Naming one by its exact name is refused,
so it can't look as if it worked:

```sh
logctl reset logger com.example.kept
```

```
logctl: 'com.example.kept' is STICKY -- reset refused without --include-sticky.
```

```sh
logctl reset loggers --include-sticky
```

```
Reverted 1 override(s).
```

## What a reset goes back to

The **baseline**: the application's own logging configuration, with the vendor defaults file on
top when the product ships one. `(baseline)` in the output means that.

With a vendor defaults file, `--to-native` goes back to the application's own configuration
instead, ignoring the vendor defaults for that logger, handler or rule until the JVM restarts. A
later plain `reset` returns it to the baseline. Without a vendor defaults file, the two are the
same. See [Configuration layers](../concepts/configuration-layers.md).

## Undo everything

```sh
logctl reset loggers --include-sticky
logctl reset handlers --include-sticky
logctl reset rules --include-sticky
logctl disable storms
logctl status
```

`status` then shows `No active overrides.` The application's own configuration was never changed,
so there is nothing else to put back.
