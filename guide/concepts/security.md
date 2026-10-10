# Security

Changing what an application logs is a security-relevant act. Raising a level can write
passwords, tokens or personal data to disk. Silencing a logger can hide what an attacker is doing.
This page says who can do either with LogAperture, and what it records.

## No network

The agent opens no network connection, inbound or outbound. It adds no port to the JVM, and
nothing in it calls out. A JVM with LogAperture is reachable over the network exactly as it was
without it.

## Who can use `logctl`

`logctl` reaches the agent through the JVM's **attach mechanism**, the same one `jcmd` and
debuggers use. It works only on the same machine, and only for the operating-system user the JVM
runs as. LogAperture adds no accounts or passwords of its own: whoever can run commands as the
JVM's user can use `logctl`, and nobody else can.

If you can already attach a debugger to the JVM, LogAperture gives you no access you didn't have.

## What gets recorded

Every change is written to the [audit trail](../reference/audit-log.md): what changed, from what to
what, who (the JVM's user), why (`--reason`), and what made the change, whether `logctl`, a timer
running out, or a restart restoring `sticky` changes. Reverts are recorded as well as changes.

The audit trail goes to standard error, or to a file with `-Dlogaperture.audit.file`. In 1.0 it
is plain lines that anyone who can write the file can edit. Treat it as a record, not as evidence.
If you need a record the JVM's user can't change, ship the JVM's standard error off the machine
as it is written.

## What LogAperture won't do

- **Edit configuration the application owns.** `standalone.xml`, `logback.xml` and
  `logging.properties` are read, never written. Every change lives in LogAperture's own state
  file and can be undone with `reset`.
- **Hide events without saying so.** A `drop` rule lets a sample through, every dropped event is
  counted, and a summary is logged every 10 minutes. `trim` marks every trace it shortens. See
  [Rules](rules.md).
- **Let a library hide output.** A library's [recipe](../vendors/library-recipes.md) can only raise
  levels. A recipe that would lower one is skipped.
- **Break the application when it fails.** If a rule can't be evaluated, the event is logged as if
  the rule weren't there.

## Things to know

- **Raising a level can expose data.** Prefer a timed change (`for 30m`) for anything that might
  log sensitive data, so it can't be forgotten. The default for `set` is `for 4h` for this reason.
- **The vendor defaults file is configuration.** If the account the JVM runs as can write the
  file, or its directory, anyone who can run code as that account can change the baseline. `doctor`
  warns about it (`vendor-defaults.writable`).
- **Permissions inside LogAperture are not configurable in 1.0.** The agent checks a permission
  for each kind of operation (viewing, raising, lowering, making a change `sticky`, adding rules),
  but in 1.0 every permission is granted to whoever can attach. A refused operation exits with
  code 6. Restricting them, and lists of loggers no rule may touch, are planned.
