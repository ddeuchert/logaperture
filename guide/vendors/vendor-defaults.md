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
# Exported by logctl export vendor-defaults, 2026-10-16T01:45:07Z, agent 1.0.0-beta.4
# Started from: no vendor defaults file
schemaVersion: 1
loggers:
  - name: org.jboss.as.server.deployment
    level: WARN
    stateId: 937f83b2-c403-4735-8999-47cc17dd5c3e
handlers:
  - name: CONSOLE
    level: INFO
    stateId: 51eca665-4862-47c7-a4ba-41b0c8187919
rules:
  # was r1
  - id: drop-request
    action: drop
    logger: io.undertow.request
    messageContains: "Matched default"
    below: WARN
    sampleFull: 5m
    stateId: 0d5498e8-4026-4da9-ab36-c3b104e7dfb0
```

The export is the file the JVM started with, plus every `sticky` change on top of it. `session` and
`for` changes are left out: only what you meant to keep is exported. So is a handler that is only
following a `sticky` logger change: it went to `AUTO` for that change, not as a setting of its own.
To ship a handler in `AUTO`, set it yourself (`logctl set handler CONSOLE AUTO sticky`).

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

### In a Quarkus application

!!! note "New in 1.1"
    Bundled vendor defaults come with the [Quarkus extension](../get-started/install-quarkus.md) in
    LogAperture 1.1.

With the LogAperture Quarkus extension, ship the file inside the application instead: put it at

```
src/main/resources/META-INF/logaperture/vendor-defaults.yaml
```

and rebuild. It then applies wherever the application runs, with no property, volume or ConfigMap.
Everything above holds for it, and it is named by where it is bundled:

```
[logaperture] LogAperture 1.1.0-SNAPSHOT active (Quarkus extension): vendor defaults bundled:META-INF/logaperture/vendor-defaults.yaml (1 logger)
```

- **A mistake stops the build.** The extension checks the file when the application is built, and
  a file with errors fails the build with each error and its line:

    ```
    [ERROR] [error]: Build step org.logaperture.quarkus.deployment.LogApertureProcessor#bundledVendorDefaults threw an exception: java.lang.IllegalStateException: LogAperture: META-INF/logaperture/vendor-defaults.yaml has errors, so the application was not built:
    [ERROR]   line 4: unknown level 'LOUD' (expected one of [ALL, TRACE, DEBUG, INFO, WARN, ERROR, OFF])
    [ERROR]   line 5: unknown field 'nme' in a logger entry
    ```

- **Only the application's own file applies.** The same path in a library jar is ignored, and the
  build log names it:

    ```
    [WARNING] [org.logaperture.quarkus.deployment.LogApertureProcessor] Ignoring META-INF/logaperture/vendor-defaults.yaml in jar:file:///home/dev/.m2/repository/org/example/test/lib-with-vendor/1.0/lib-with-vendor-1.0.jar!/META-INF/logaperture/vendor-defaults.yaml: only the application's own file applies
    ```

    A library offers settings as [recipes](library-recipes.md) instead.
- **A site can replace it or turn it off** without a rebuild: an external file named by
  `quarkus.logaperture.vendor-defaults` replaces the bundled one entirely, and
  `quarkus.logaperture.bundled-vendor-defaults=false` starts the application without it. See
  [Quarkus extension configuration](../reference/quarkus-extension-config.md).
- **Tune, export, bundle.** `logctl export vendor-defaults` from a running application works as
  above (its header says `Started from: bundled:...`). Put the exported file at the same path and
  rebuild; it takes over the `sticky` settings it was exported from.
- In `quarkus dev`, an edit to the file reloads the application with the edited file.
- The writable-file warning doesn't apply to a file inside the application.
