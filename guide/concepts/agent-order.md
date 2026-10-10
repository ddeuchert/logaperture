# Agent order

A JVM can run several Java agents. Each `-javaagent` entry starts in the order it is listed, and
an agent can log while it starts. LogAperture can only act on what is logged after it has started,
so its place on the list matters.

## Put LogAperture first

```sh
JAVA_OPTS="$JAVA_OPTS -javaagent:/opt/logaperture/lib/logaperture-agent.jar -javaagent:/opt/other/other-agent.jar"
```

An agent listed **before** LogAperture starts first. Anything it logs during startup is written
before LogAperture can apply its rules, so `drop` and `trim` rules can't affect it, not even
`sticky` ones. Listing LogAperture first makes that window as small as it can be.

A typical case: another agent logs a `ConnectException` with a full stack trace while it starts.
The `sticky` `trim` rule you added for exactly that exception shortens it every time it appears
later, but never the first one at startup.

Agents set in `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS` start before any on the command line.

Some sites are required to list another agent first, such as a monitoring or security agent. That
is fine: LogAperture works the same, and only that agent's startup output is out of reach.

## What stays out of reach

Even listed first, LogAperture only sees what goes through the logging framework. An agent that
prints to the console directly, writes its own file, or keeps its own buffer is outside it. Change
that agent's own settings instead.

On WildFly, level changes apply from the moment LogAperture starts. `drop` and `trim` rules, `top`
and storm detection take effect once LogAperture has installed itself into the server's logging
handlers. If a launch needs that held back, see `-Dlogaperture.handlerInstallDelaySeconds` in
[Agent options](../reference/agent-options.md).

## Checking the order

`logctl doctor` reports where LogAperture is:

```
[OK]    logaperture-agent.jar is the first -javaagent.
```

or, when other agents are ahead of it, an `INFO` finding naming them. It also warns about the same
jar listed twice. See [`agent.order` and `agent.duplicate`](../reference/doctor-checks.md#the-agent).
Neither is reported when LogAperture was attached to a JVM that was already running; a jar listed
twice still is.

## The same jar twice

Listing `logaperture-agent.jar` twice does no harm: LogAperture starts once, from the first entry,
and each later entry writes one warning at startup:

```
[logaperture] WARN LogAperture is already started in this JVM; ignoring the duplicate -javaagent entry.
```

Remove the extra entry anyway.

## If the server won't start

Add `-Dlogaperture.disabled=true`. The agent then does nothing. If the server starts that way,
LogAperture is involved: please [open an issue](https://github.com/ddeuchert/logaperture/issues)
with the server log and the full list of `-javaagent` entries.
