# WildFly: handler names within seconds of start (issue #246)

Status: **agreed 2026-10-20** (E1–E6); implementation to follow on this branch.
Parent spec: [`handler-floor-control.md`](handler-floor-control.md), "WildFly handler name
resolution (issue #14)", including its "Gated on `server-state == running`" paragraph (issue #66).
Also touches [`vendor-defaults.md`](vendor-defaults.md) (handlers pending at start).
Findings: [`../spikes/wildfly-early-handler-names.md`](../spikes/wildfly-early-handler-names.md).

## Functional summary

After this feature, the user will be able to:

- See `CONSOLE`, `FILE` and the other configured handler names in `logctl list handlers` within a
  few seconds of WildFly starting, however long the application takes to deploy.
- Put `CONSOLE: AUTO` (or any handler level) in the vendor defaults and have it take effect within
  a few seconds of the start of the boot, not at the first 30-second sweep, so a logger set to TRACE shows on the console during deployment without
  editing `standalone.xml`.
- Set a handler override with `logctl set handler CONSOLE …` while the application is still
  starting.
- Stop seeing the startup warning that a vendor-defaults handler was "not found", except for a
  handler that really doesn't exist.

Guide pages changed: [`logctl` reference](../../guide/reference/logctl.md) (the `list handlers`
text, generated from the help) and [Install on WildFly](../../guide/get-started/install-wildfly.md)
(one sentence on when names appear). The [vendor defaults](../../guide/vendors/vendor-defaults.md)
page gets a note if any detail below changes what a user sees.

## Why

Handler names come from the management model, which WildFly makes readable only when the boot
finishes (`server-state=running`, after every deployment). The spike measured the gap at about
60 s with a 60 s application, and it grows with the application. Until then handlers are anonymous
tokens (`ConsoleHandler@2e2c32ba`), a vendor-defaults handler entry is held as pending, and a
logger level raised for the boot can't reach a console whose own level is INFO.

The logging subsystem keeps its own configuration object, and the spike found it holds the names
and the live handler instances a few seconds into boot, about 60 s before `running`
([findings](../spikes/wildfly-early-handler-names.md)).

## Behavior

### E1. A new first source for names

On WildFly the name resolver tries the logging subsystem's configuration before the management
model. It reads the configured handler names and, for each, the handler instance it constructed,
and binds by **identity** to the handlers the adapter already holds. No model read is made, so the
`WFLYCTL0013` errors #66 removed don't return.

The reads go through the boot module loader, as module `org.jboss.as.logging`, and call only the
public interfaces (`LogContextConfiguration`, `HandlerConfiguration`, `ObjectConfigurable`), never
`getClass()` on a module-private instance, per the rule already in the #14 spec.

### E2. Retry, not wait

Before WildFly installs its configuration object, the call fails (the log context still has the
properties-file configurator). That is "not ready yet", not an error: nothing is logged above
debug, and the next resolution attempt tries again. Attempts keep the cadence they have today (the
sweep, and any request that needs names). The `server-state` check is not applied to this source.

### E3. The model route stays, as the fallback

The `server-state == running` model route (#14, #66) is unchanged and runs when E1 binds nothing
or fails for a reason other than "not ready yet": an unexpected class layout on some WildFly
version, or a handler E1 can't see. It binds, by type and file name as today, any handler E1 left
anonymous, so the outcome is never worse than today's.

### E4. Names that change nothing else

The set of names, the `HandlerRef` for each, the token-to-name upgrade, and what `logctl` prints
are as now; names only arrive earlier. A handler still anonymous after E1 (one the configuration
doesn't list) stays a token until the model route runs.

### E5. Vendor defaults on a handler pending at start

A vendor-defaults handler entry whose name isn't known at install (the normal case on WildFly,
where nothing resolves yet) goes on the pending list as today. The sweep would retry it, but its
first tick is one full interval after start (30 s by default), which would waste the early names.
So **while any vendor handler entry is pending, a retry runs about once a second** (a fixed 1 s,
not configurable) until nothing is pending, or for two minutes, whichever comes first. After two
minutes a name that still hasn't resolved is probably not going to, so the retry stops and the
normal sweep alone carries on (it still applies the entry as soon as the handler appears). The
retry starts after a context is installed with something pending; an install with no vendor
handler entries never starts it.

An applied entry is audited as today (once per load, not per entry; quieter-output.md Q1). What
"warns" about a handler that isn't found is the informational `vendor-defaults.unresolved-handler`
finding in `logctl doctor`; it is raised from the same pending list, so it disappears as soon as
the entry applies and is no longer shown for the seconds it used to cover.

### E6. What this does not do

- It doesn't guess a name from a type (`CONSOLE` from `ConsoleHandler`); see the issue thread.
- It doesn't cover the first second or two, before WildFly installs its configuration object.
- It doesn't change domain mode, EAP, or any non-WildFly adapter.
- It doesn't touch `standalone.xml` or the MXBean and `--json` surface.

## Tests

- A unit test of the resolver with fake `LogContextConfiguration` / `HandlerConfiguration`
  objects: names bound by identity; not-ready (`ClassCastException`-like failure) returns no names
  without an error; a handler the configuration doesn't list stays anonymous; E1 failing hands over
  to the model route.
- A WildFly IT (the dev image, 26.1.3) with the sample WAR delayed
  (`-Dsample.startupDelaySeconds`): while the deployment is still starting, `list handlers` shows
  `CONSOLE` and `FILE`; a vendor-defaults `CONSOLE: AUTO` with a logger at TRACE lowers the console
  within a few seconds of start (well before the first sweep tick), while the deployment is still running, and a TRACE record from a logger set in the vendor defaults
  reaches the console log during the delay.
- The same IT checks that vendor-defaults logger levels still hold after WildFly applies its own
  logging configuration during boot (the open point in the issue).
- Not covered by CI: WildFly 27 and later, and a `custom-handler`; both are checked by hand and
  recorded here.

## Guide changes

- `HelpTopics.java`: the `list handlers` sentence "appear once the server is up" becomes "appear
  within a few seconds of the server starting"; regenerate `guide/reference/logctl.md`.
- `guide/get-started/install-wildfly.md`: one sentence that handler names show a few seconds
  after start, and that `ALL_HANDLERS` always works.
- `guide/release-notes.md` / CHANGELOG entry.

## Decisions

| # | Decision | Status |
|---|---|---|
| E1 | Logging subsystem configuration is the first source for names, bound by identity | Agreed (David, 2026-10-20) |
| E2 | "Not ready yet" is retried quietly; the `running` check is not applied to E1 | Agreed (David, 2026-10-20) |
| E3 | Model route stays as the fallback, unchanged | Agreed (David, 2026-10-20) |
| E4 | Handlers E1 doesn't list stay anonymous until the model route runs | Agreed (David, 2026-10-20) |
| E5 | While a vendor handler entry is pending, retry every 1 s until applied or for two minutes; the doctor finding reflects the same list | Agreed (David, 2026-10-20; revised same day) |
| E6 | No type-based guessing, no change outside WildFly | Agreed (David, 2026-10-20) |
