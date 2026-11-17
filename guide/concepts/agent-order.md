# Agent order

A JVM can run several Java agents. Each `-javaagent` entry starts in the order it is listed, and
an agent can log while it starts. LogAperture can only act on what is logged after it has started,
so its place on the list matters.

## Put LogAperture first

```sh
JAVA_OPTS="$JAVA_OPTS -javaagent:/opt/logaperture/lib/logaperture-agent.jar -javaagent:/opt/other/other-agent.jar"
```

An agent listed **before** LogAperture starts first. Anything it logs before LogAperture has
installed its logging handlers is out of reach of `drop` and `trim` rules, including `sticky`
rules. On a plain JVM, Tomcat or Quarkus, where the logging framework is ready while the agents
start, listing LogAperture first shrinks that window. **On WildFly it doesn't help with another
agent's startup output**, because the server's logging doesn't exist yet; see
[What stays out of reach](#what-stays-out-of-reach).

A typical case: another agent logs a `ConnectException` with a full stack trace while it starts.
The `sticky` `trim` rule you added for exactly that exception shortens it every time it appears
later, but never the first one at startup.

Agents set in `JAVA_TOOL_OPTIONS` or `JDK_JAVA_OPTIONS` start before any on the command line.

Some sites are required to list another agent first, such as a monitoring or security agent.
LogAperture still applies its rules to later records once its handlers are installed.

## What stays out of reach

Even listed first, LogAperture only sees what goes through the logging framework. An agent that
prints to the console directly, writes its own file, or keeps its own buffer is outside it. Change
that agent's own settings instead.

### On WildFly

All Java agents finish their `premain` methods before the server's `main` starts, and WildFly
initializes JBoss LogManager during `main`. LogAperture waits for that log manager before
installing into the server's logging system. This is the order on a successful startup:

1. The agents run `premain`, and any of them may log a startup trace.
2. WildFly starts and initializes its logging system. Its earliest boot messages are written
   before LogAperture can see them.
3. LogAperture installs into the server's logging system and announces `active (WildFly)`. Level
   changes apply from here.
4. LogAperture wraps the server's logging handlers, immediately by default. `drop` and `trim`
   rules, `top` and storm detection act from here.

Everything written before step 4 is out of reach, whichever agent wrote it. Changing the
`-javaagent` order or the rule tier can't change that, and neither can `sticky` rules or rules
loaded from vendor defaults. With `-Dlogaperture.handlerInstallDelaySeconds` set, step 4 happens
that many seconds after startup instead, and the log says when; see
[Agent options](../reference/agent-options.md). Only the delay moves the line later, so leave it
at `0` unless you need it.

To quiet another agent's startup trace:

- use that agent's own logging settings, which is the cleanest fix;
- filter it outside LogAperture, for example with a filter on the server's own handler that
  writes it;
- or accept it: the trace appears once per restart, and later repeats of the same trace, logged
  after step 4, are covered by your rules.

## Checking the order

`logctl doctor` reports where LogAperture is:

```
[OK]    logaperture-agent.jar is the first -javaagent.
```

or, when other agents are ahead of it, an `INFO` finding naming them. It also warns about the same
jar listed twice. See [`agent.order` and `agent.duplicate`](../reference/doctor-checks.md#the-agent).
Neither is reported when LogAperture was attached to a JVM that was already running, and `doctor`
says so with an [`agent.attached`](../reference/doctor-checks.md#the-agent) line; a jar listed twice
still is reported.

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
