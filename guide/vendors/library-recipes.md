# Recipes for library authors

You know which of your library's loggers to turn up when something goes wrong, and your users
usually don't. A **recipe** writes that knowledge down: a named set of logger levels, with a
description, that an operator can find and switch on with one command:

```sh
logctl list recipes
logctl apply recipe io.undertow:sessions for 30m
```

Ship a recipe file inside your jar and every application that uses your library offers it, with
no setup on the operator's side.

## The file

Put it at `META-INF/logaperture/recipes.yaml` on your library's class path:

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

- **`namespace`**: use a reverse domain you control, normally your Maven group id. The recipe's
  id is `<namespace>:<name>`, here `io.undertow:sessions`. Nothing checks that the namespace is
  yours, but a reverse domain keeps it from colliding with anyone else's. `vendor`, `logaperture`
  and `org.logaperture` are reserved.
- **`summary`**: one line. `list recipes` shows it.
- **`description`**: what the recipe turns on, how much it logs, and anything sensitive it might
  write. `show recipe` prints it. Write it after `description: |`, indented on the following
  lines; a `#` inside it is just text.
- **`loggers`**: exact logger names and the level for each, with an optional `reason` that shows
  in `logctl status`.

The full format is in [File formats](../reference/file-formats.md#recipe-files).

## Where the file goes

The file must be on the **class path**, so where it goes depends on how your code is packaged:

| Packaging | Path |
|---|---|
| A jar | `META-INF/logaperture/recipes.yaml` at the root of the jar |
| A war | `WEB-INF/classes/META-INF/logaperture/recipes.yaml`, or inside any jar in `WEB-INF/lib` |
| An ear | inside any jar in the ear's `lib/` |

A war's own `META-INF/` folder is not on its class path, and neither is an ear's, so a file there is
never found.

## Rules for library recipes

- **Only raising levels.** A library may suggest more visibility, never less. A recipe entry that
  would make a logger less verbose than it currently is gets skipped when the recipe is applied.
- **Loggers only.** Handler levels and `drop` and `trim` rules aren't allowed in a library's file,
  and make it fail. Those belong to the people running the application, in the vendor defaults file
  or the recipes folder.
- **Found once your library has loaded.** `list recipes` looks at what the JVM has loaded. Before
  your library's classes are used, its recipes aren't listed.
- **One bad file doesn't hide others.** A file with an error offers none of its recipes, and
  `logctl list recipes --verbose` and `doctor` show the error. Other files are unaffected.

## Testing your recipe

Run an application that uses your library with the agent attached, make it load your library, then:

```sh
logctl list recipes
logctl show recipe io.undertow:sessions
logctl apply recipe io.undertow:sessions for 5m
logctl reset recipe io.undertow:sessions
```

[Use recipes](../how-to/recipes.md) shows what each one prints.
