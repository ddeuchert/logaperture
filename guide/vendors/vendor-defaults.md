# Vendor defaults

If you ship a product that runs on a JVM, its customers inherit its logging. A **vendor defaults
file** lets you ship sensible logging settings with the product, so customers don't start with
noisy logs or have to run `logctl` commands after every install.

The file is given to the agent at startup:

```sh
-javaagent:/opt/logaperture/lib/logaperture-agent.jar=--vendor-defaults=/opt/myproduct/logging.yaml
```

It can set logger levels, handler levels, `DEFAULT_HANDLERS`, and `drop` and `trim` rules. It can
also offer [recipes](library-recipes.md) for customers to switch on when they need them.

## What the file does

For every setting it names, the file replaces the application's own logging configuration. Your
customers' operators then work on top of it:

- `logctl list loggers` shows the file's level in a `VENDOR` column.
- `reset` goes back to the file's settings, not to the application's configuration. That's what
  makes the file a **baseline**; see [Configuration layers](../concepts/configuration-layers.md).
- `reset … --to-native` goes back to the application's own configuration until restart, for a
  customer who needs to see what the product would log without your settings.
- The file's rules have `vendor:` ids. A customer can change one with `alter rule`, and
  `reset rule` puts yours back.

The file is never written by LogAperture. If it has an error, **none of it applies**, the agent
starts anyway, and `logctl doctor` lists every error with its line number. Check a new file
before you ship it.

## Making the file: tune, then export

You don't need to write the file by hand. Run the product in a test environment, tune its logging
live with `sticky` changes until it is right, then export:

```sh
logctl set logger org.jboss.as.server.deployment WARN sticky
logctl set handler CONSOLE INFO sticky
logctl add rule drop io.undertow.request --message-contains "Matched default" --below WARN sticky
logctl export vendor-defaults --out logging.yaml
```

```yaml
# Exported by logctl export vendor-defaults, 2026-10-10T00:41:26Z, agent 1.0.0-beta.1-SNAPSHOT
# Started from: no vendor defaults file
schemaVersion: 1
loggers:
  - name: org.jboss.as.server.deployment
    level: WARN
    stateId: d4d6917d-4dd9-4792-9403-5a8faaf6ecba
handlers:
  - name: CONSOLE
    level: INFO
    stateId: 6b5c206a-60ff-4b05-82f0-23d8ea90a237
rules:
  # was r2
  - id: drop-request
    action: drop
    logger: io.undertow.request
    messageContains: "Matched default"
    below: WARN
    sampleFull: 5m
    stateId: 5a8faccc-5470-404b-9ebe-a0769215b780
```

The export is the file the JVM started with, plus every `sticky` change on top of it. `session` and
`for` changes are left out: only what you meant to keep is exported.

The `stateId` lines connect each entry to the `sticky` change it came from. When you restart with
the new file, the file takes those changes over, so each setting is active once, from the file.
Later edits to the file then take effect on the next restart.

### Taking an entry out

To remove a setting from the next version of the file, return it to the application's own
configuration before exporting. For a logger the current file sets to `DEBUG`, whose native
configuration says `INFO`:

| Before exporting, you run | The next file says |
|---|---|
| nothing | `DEBUG`, unchanged |
| `set logger org.perfmon4j WARN sticky` | `WARN` |
| `set logger org.perfmon4j INFO sticky` | `INFO`, fixed even if the native configuration changes later |
| `reset logger org.perfmon4j --to-native` | nothing: the native configuration decides from now on |

## Shipping it

- **Put the file where the JVM's account can't write it.** Otherwise anyone who can run code as
  that account can change your baseline. `doctor` warns when it can (`vendor-defaults.writable`).
- **Use exact logger names.** The file doesn't accept `*` patterns.
- **Handlers may not exist yet at startup.** A handler the file names is applied as soon as it
  appears. On WildFly some appear after startup; `doctor` reports one that never does.
- **Storm detection isn't set in the file.** Use the `--storm-detection=on` agent argument.

The full format is in [File formats](../reference/file-formats.md#vendor-defaults-file).
