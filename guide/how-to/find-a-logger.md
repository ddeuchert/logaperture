# Find a logger

To change a logger you need its full name, but a log line often shows only part of it. WildFly's
default console format prints the last segment of the category, so a line reads `infinispan`, not
`org.jboss.as.clustering.infinispan`.

The output on this page is from WildFly 26.1.3.

## From the end of the name

Start the pattern with `*.`:

```sh
logctl list loggers '*.infinispan' --show-all
```

```
LOGGER                              CONFIGURED  EFFECTIVE  OVERRIDE
org.jboss.as.clustering.infinispan  —           INFO       —
```

`--show-all` matters here: without it, `list loggers` shows only loggers you have changed.

## From the start of the name

A plain name lists that logger and everything under it:

```sh
logctl list loggers org.jboss.as.server --show-all
```

```
LOGGER                                                 CONFIGURED  EFFECTIVE  OVERRIDE
org.jboss.as.server                                    —           INFO       —
org.jboss.as.server.RuntimeExpressionResolver          —           INFO       —
org.jboss.as.server.deployment                         —           INFO       —
org.jboss.as.server.deployment.module.extension-index  —           INFO       —
org.jboss.as.server.deployment.scanner                 —           INFO       —
org.jboss.as.server.moduleservice                      —           INFO       —
org.jboss.as.server.net                                —           INFO       —
```

A pattern may start and end with `*`. Quote patterns so the shell doesn't expand them.

## Reading the columns

| Column | Shows |
|---|---|
| `CONFIGURED` | the level the application's own configuration gives this logger, or `—` if it inherits |
| `VENDOR` | the level the vendor defaults file sets (only when there is one) |
| `EFFECTIVE` | the level it runs at now |
| `OVERRIDE` | a change made with `logctl`: its tier, when it reverts, and why |

## Loggers that don't exist yet

A logger appears once the application has used it. If a class hasn't logged anything yet, its
logger may not be listed. You can still set it by its exact name: the setting applies when the
logger is created.

## Let `logctl` pick

On a terminal, `logctl set` and `logctl add rule` accept part of a name, such as `Worker`, and list
the matching loggers to choose from. Type `?` at the logger question to pick from the loggers you
changed recently, or to see the top-level packages when you have no history. Here it is on a plain
JVM with nothing set yet:

```
$ logctl set logger
Which logger? A name or pattern, e.g. Deployer or *.deployment.* (Enter to cancel, ? for recent)
> ?
No history yet.
4 top-level packages (from 144 loggers): com.shop, io.netty, org.apache, org.hibernate.
Which logger? A name or pattern, e.g. Deployer or *.deployment.* (Enter to cancel, ? for recent)
> org.hibernate
Level? (i=INFO, d=DEBUG, t=TRACE, w=WARN, e=ERROR, o=OFF)
>
Not applied.
```

A plain name like `org.hibernate` stands for that logger and everything under it, so the next
question is the level. Here the session was cancelled there (the end of input). To pick one logger
under it, type a pattern instead, such as `*.SQL`.

The list shows only the first two parts of each name, and only the deepest of each family, so `com`
alone isn't listed next to `com.shop`. It is a hint for what to type, not a
complete list. For the full list, use
`logctl list loggers --show-all`. See [Raise a log level](raise-a-level.md#let-logctl-ask) for the
rest: picking from recent loggers, paging through long lists, and one-letter levels.
