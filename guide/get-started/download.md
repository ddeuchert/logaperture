# Download

LogAperture is two pieces: the **agent**, a jar the JVM loads with `-javaagent`, and **`logctl`**,
the command-line tool. Both are in the release zip, and both are on Maven Central.

Open source under the [Apache License 2.0](https://github.com/ddeuchert/logaperture/blob/main/LICENSE).

<!-- unreleased -->
!!! note "This is the guide for unreleased code"
    The examples show `<version>`. Use a released version from the
    [releases page]({{ logaperture.release_url }}), or read the guide for that version: the
    version menu at the top of the page.

<!-- /unreleased -->
## The release zip

Download [`logaperture-{{ logaperture.version }}.zip`]({{ logaperture.release_url }}) from GitHub.
It is everything you need on one machine, and what the install pages start from:

| Path | |
|---|---|
| `bin/logctl`, `bin/logctl.cmd` | `logctl`, for Linux and macOS, and for Windows; [an optional conf file](../vendors/logctl-launcher.md) next to them sets the Java it uses |
| `lib/logaperture-agent.jar` | the agent |
| `lib/logaperture-cli.jar` | the jar `logctl` runs |
| `docs/index.html` | this guide, for reading without a network |
| `LICENSE` | the Apache License 2.0 |

### Check the download

Next to the zip on the release page are `logaperture-{{ logaperture.version }}.zip.sha256`, its
SHA-256 checksum, and `logaperture-{{ logaperture.version }}.zip.asc`, its GPG signature. Download
them into the same directory as the zip.

To check that the zip arrived intact:

```sh
sha256sum -c logaperture-{{ logaperture.version }}.zip.sha256
```

On macOS, use `shasum -a 256 -c` instead. It prints `logaperture-{{ logaperture.version }}.zip: OK`.

To check that the zip is the one the project released, verify the signature. Releases are
signed with the key `7405 3993 02DB FD10 0BB7  C2C6 70F9 EFA2 295F D26E`, which is on
`keyserver.ubuntu.com`:

```sh
gpg --keyserver hkps://keyserver.ubuntu.com --recv-keys 7405399302DBFD100BB7C2C670F9EFA2295FD26E
gpg --verify logaperture-{{ logaperture.version }}.zip.asc logaperture-{{ logaperture.version }}.zip
```

```
gpg:                using RSA key 7405399302DBFD100BB7C2C670F9EFA2295FD26E
gpg: Good signature from "Logaperture Release (org.logaperture release artifact signing key) <releases@logaperture.org>" [unknown]
gpg: WARNING: This key is not certified with a trusted signature!
gpg:          There is no indication that the signature belongs to the owner.
Primary key fingerprint: 7405 3993 02DB FD10 0BB7  C2C6 70F9 EFA2 295F D26E
```

What matters is `Good signature` and a primary key fingerprint that matches the one above. The
warning only means you haven't certified the key yourself. The files on Maven Central are signed
with the same key.

Next: install it [on WildFly](install-wildfly.md) or [on a plain JVM](install-plain-jvm.md).

## Maven Central

From 1.0.0-beta.1, each release is on Maven Central with the group `org.logaperture` and the
version `{{ logaperture.version }}`:

| Artifact | Type | What it is |
|---|---|---|
| `logaperture-agent` | `jar` | the agent |
| `logaperture-cli` | `jar` | `logctl`'s jar: run it with `java -jar`, which is all `bin/logctl` does |
| `logaperture` | `zip` | the release zip, the same file as on GitHub |

Every file has a `.asc` GPG signature and checksums next to it.

!!! warning "The agent is not a dependency"
    No application code compiles against LogAperture, and the agent must not be on your
    application's class path. The JVM loads it from the `-javaagent:` line. So a build doesn't
    declare it as a dependency: it **copies the jar** somewhere, for example into a container
    image, and the `-javaagent:` line points there. The examples below do that.

### Command line

To fetch the agent into a directory, for example while building a container image:

```sh
mvn dependency:copy -Dartifact=org.logaperture:logaperture-agent:{{ logaperture.version }} \
    -DoutputDirectory=/opt/logaperture/lib -Dmdep.stripVersion=true
```

`-Dmdep.stripVersion=true` names it `logaperture-agent.jar`, as in the zip.

### Maven

In your build's `pom.xml`, this copies the agent to `target/agent/logaperture-agent.jar` when
the project is packaged:

```xml
<plugin>
  <groupId>org.apache.maven.plugins</groupId>
  <artifactId>maven-dependency-plugin</artifactId>
  <version>3.8.1</version>
  <executions>
    <execution>
      <id>copy-logaperture-agent</id>
      <phase>package</phase>
      <goals>
        <goal>copy</goal>
      </goals>
      <configuration>
        <artifactItems>
          <artifactItem>
            <groupId>org.logaperture</groupId>
            <artifactId>logaperture-agent</artifactId>
            <version>{{ logaperture.version }}</version>
          </artifactItem>
        </artifactItems>
        <outputDirectory>${project.build.directory}/agent</outputDirectory>
        <stripVersion>true</stripVersion>
      </configuration>
    </execution>
  </executions>
</plugin>
```

### Gradle

This adds a `copyLogapertureAgent` task that copies the agent to
`build/agent/logaperture-agent.jar`, and runs it as part of `assemble`. The project needs
`mavenCentral()` in its `repositories`.

=== "Kotlin DSL"

    ```kotlin
    val logapertureAgent by configurations.creating {
        isTransitive = false
    }

    dependencies {
        logapertureAgent("org.logaperture:logaperture-agent:{{ logaperture.version }}")
    }

    val copyLogapertureAgent by tasks.registering(Copy::class) {
        from(logapertureAgent)
        into(layout.buildDirectory.dir("agent"))
        rename { "logaperture-agent.jar" }
    }

    tasks.named("assemble") {
        dependsOn(copyLogapertureAgent)
    }
    ```

=== "Groovy DSL"

    ```groovy
    configurations {
        logapertureAgent {
            transitive = false
        }
    }

    dependencies {
        logapertureAgent 'org.logaperture:logaperture-agent:{{ logaperture.version }}'
    }

    def copyLogapertureAgent = tasks.register('copyLogapertureAgent', Copy) {
        from configurations.logapertureAgent
        into layout.buildDirectory.dir('agent')
        rename { 'logaperture-agent.jar' }
    }

    tasks.named('assemble') {
        dependsOn copyLogapertureAgent
    }
    ```

### Then

Point the JVM at the copied jar. In a container image built from your project, for example:

```dockerfile
COPY target/agent/logaperture-agent.jar /opt/logaperture/lib/logaperture-agent.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/opt/logaperture/lib/logaperture-agent.jar -Dlogaperture.home=/tmp/logaperture"
```

`-Dlogaperture.home` names a directory the application's user can write, for LogAperture's
state file, since a container's user often has no writable home directory. Use a volume
instead of `/tmp` if `sticky` changes should survive the container being replaced.

On WildFly the `-javaagent:` line goes in the server's startup configuration instead: see
[Install on WildFly](install-wildfly.md). `logctl` must run on the same machine, or in the same
container, as the JVM: see [Install on a plain JVM](install-plain-jvm.md).

## Try an unreleased build

Between releases, a build of a change under test can be published to Maven Central's snapshot
repository. This is for people who have been asked to try a fix: it is **not for production**, it
changes without notice, and Central removes snapshots after 90 days.

A snapshot has the next version followed by `-SNAPSHOT` and lives in a separate repository,
`https://central.sonatype.com/repository/maven-snapshots/`. To fetch its zip:

```sh
mvn dependency:copy -Dartifact=org.logaperture:logaperture:<version>-SNAPSHOT:zip \
    -DoutputDirectory=. \
    -DremoteRepositories=snapshots::::https://central.sonatype.com/repository/maven-snapshots/
```

Ask whoever sent you the request which version to use. `logctl env` shows the version of what is
running.
