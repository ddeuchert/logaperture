# Coming in 1.1

!!! warning "Work in progress, not in 1.0"
    Everything on this page is being built for LogAperture 1.1. **None of it is in 1.0**, and the commands
    below do not exist in the version you are reading about. Details may change before it ships. This is a plan,
    not a promise.

1.0 supports WildFly and Logback. 1.1 is about reaching the applications that aren't there yet, and about
trying LogAperture without committing to it first. A 1.1.0 pre-release is planned for **late November or early
December**, and 1.1.0 itself follows it. The pre-release carries whatever is merged by then; its release notes say
exactly what that is. This guide stays on 1.0 until 1.1.0 is out.

!!! tip "Try it early"
    You don't have to wait for 1.1.0. The in-progress guide for 1.1 is at
    [logaperture.org/dev](https://logaperture.org/dev/): it describes each feature on this page as it stands today,
    and changes as work lands. When a pre-release is available, it is listed on the
    [GitHub releases page](https://github.com/ddeuchert/logaperture/releases), announced in
    [Discussions](https://github.com/ddeuchert/logaperture/discussions), and its guide appears in this guide's
    version menu. No pre-release has been published yet.

## Quarkus, with no `-javaagent`

LogAperture on a Quarkus application in JVM mode ([#114](https://github.com/ddeuchert/logaperture/issues/114)).
You add one dependency, the LogAperture extension, and rebuild. The command that starts the application doesn't
change, so it suits platforms where you don't control the command line, such as Kubernetes or Azure App Service.
A `-javaagent` option works too, with no rebuild. Quarkus native images can never be supported: they have no JVM
to attach an agent to.

## Try it without a restart

`logctl attach` loads the agent into a JVM that is already running, so you can try LogAperture on an application
without changing its launch line or restarting it
([#273](https://github.com/ddeuchert/logaperture/issues/273)). It works on a standalone WildFly and on a plain
JVM. It lasts until that JVM stops; to keep LogAperture, add the `-javaagent` option as usual.

```text
logctl attach 12345
LogAperture 1.1.0 is active in PID 12345 (JBoss LogManager).
```

The command does not exist in 1.0.0, and the output is an illustration of its shape.

## Log4j 2

Applications that log through Log4j 2.20 or later
([#302](https://github.com/ddeuchert/logaperture/issues/302)). The commands are the ones you already know from
WildFly and Logback: `set logger`, `reset`, `drop` and `trim` rules, `top`, `doctor`, vendor defaults. LogAperture
finds Log4j on the class path or bundled inside the application: a Spring Boot fat jar, a Tomcat web application
that brings its own `log4j-core`, or a WildFly deployment with its own copy.

## A plain JVM on `java.util.logging`

A plain `java -jar` application that logs through `java.util.logging` alone
([#221](https://github.com/ddeuchert/logaperture/issues/221)), with no container around it. The WildFly commands
work there too, except that `doctor` can't check file sizes and disk use on the JDK's own file handler.

## `logctl` with JBang

[JBang](https://www.jbang.dev/) runs `logctl` straight from Maven Central, so there is nothing to download or
unpack ([#274](https://github.com/ddeuchert/logaperture/issues/274)). Together with `attach`, that is a trial on
a running application with no install and no restart.

```text
jbang logctl@ddeuchert/logaperture attach
```

Not in 1.0.0 either.

## Further out

Not decided, and no dates: Spring Boot, Tomcat with each web application's own Logback, configurable storm
suppression, and a console for `logctl`. They follow 1.1. Follow along in the
[issue tracker](https://github.com/ddeuchert/logaperture/issues), or tell us what you need in
[Discussions](https://github.com/ddeuchert/logaperture/discussions).
