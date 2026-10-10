/*
 * Copyright 2026 David Deuchert
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.logaperture.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The Slice 3 exit criterion, against a real standalone WildFly (image
 * configurable via {@code -Dwildfly.image}, default 26.1.3.Final, per issue
 * #65): agent attached by a bare {@code -javaagent}, driven entirely through
 * {@code logctl} — see doc/specs/wildfly-support.md.
 *
 * <p>{@code logctl} runs <em>inside</em> the container (it attaches to the
 * WildFly JVM locally, same as a real operator on the box), so there is no
 * JMX-over-Docker plumbing. Self-skips when Docker is absent; CI runs it on
 * an ubuntu runner.
 *
 * <p>Covers: clean boot with a bare {@code -javaagent}; {@code logctl}
 * discovery; raise a boot logger + {@code reset}; {@code standalone.xml}
 * untouched; a deployed WAR's logger visible under the one system context
 * and its override surviving a redeploy; a {@code /subsystem=logging}
 * management change being corrected by the verification sweep; and
 * doc/specs/pattern-selection-semantics.md's exit criterion against real
 * JBoss LogManager inheritance -- a trailing-star {@code setLogger} rejected
 * outright, a bare-ancestor {@code setLogger} covering an already-known
 * descendant with no override of its own, and a trailing-star {@code reset}
 * reverting an exact-name override under its scope.
 *
 * <p>Harness notes from the shakeout: this image ignores {@code
 * JAVA_OPTS_APPEND} (so the container command appends a {@code JAVA_OPTS}
 * line to {@code standalone.conf}); {@code -Dlogaperture.sweep.seconds=3}
 * tightens the verification-sweep window so the management-change test is fast;
 * and {@code -Dlogaperture.home=...} points the state store at a
 * runtime-writable directory -- this image's {@code $HOME} is owned by
 * {@code root}, not the {@code jboss} user the agent actually runs as, so
 * without it every run of this suite silently persisted nothing at all
 * (caught only once doc/specs/environment-report.md's "State file" fact gave
 * this gap something concrete to assert against).
 */
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class WildFlyContainerIT {

    private static final String DEPLOYMENTS = "/opt/jboss/wildfly/standalone/deployments";
    private static final String SERVER_LOG = "/opt/jboss/wildfly/standalone/log/server.log";
    private static final String STANDALONE_XML = "/opt/jboss/wildfly/standalone/configuration/standalone.xml";
    private static final String JBOSS_CLI = "/opt/jboss/wildfly/bin/jboss-cli.sh";
    private static final String BOOT_LOGGER = "org.jboss.as.server";
    private static final String APP_LOGGER = "com.myapp.probe.Worker";
    private static final int HTTP_PORT = 8080;

    /**
     * Matches the version tag out of an image reference like
     * {@code quay.io/wildfly/wildfly:33.0.0.Final-jdk21} -- group 1 is the
     * full version ({@code 33.0.0.Final}), group 2 its major ({@code 33}).
     */
    private static final Pattern IMAGE_VERSION = Pattern.compile(":(?<version>(?<major>\\d+)[\\w.]*)-jdk\\d+$");

    /**
     * WildFly 27 jumped straight from Jakarta EE 8 (javax.servlet) to Jakarta
     * EE 10 (jakarta.servlet) -- there was no EE9/jakarta-namespace release in
     * between (issue #65).
     */
    private static final int FIRST_JAKARTA_NAMESPACE_MAJOR = 27;

    @TempDir
    private Path scratch;

    private GenericContainer<?> wildfly;
    private String expectedContainerVersion;
    private boolean jakartaServletNamespace;

    @BeforeAll
    void startWildFly() throws Exception {
        String agentJar = requireFile("logaperture.agent.jar",
                System.getProperty("logaperture.agent.jar"));
        String cliJar = requireFile("logaperture.cli.jar",
                System.getProperty("logaperture.cli.jar"));

        String image = System.getProperty("logaperture.wildfly.image", "quay.io/wildfly/wildfly:26.1.3.Final-jdk17");
        Matcher versionMatch = IMAGE_VERSION.matcher(image);
        assertTrue(versionMatch.find(), "expected a recognizable WildFly version tag in " + image);
        expectedContainerVersion = versionMatch.group("version");
        jakartaServletNamespace = Integer.parseInt(versionMatch.group("major")) >= FIRST_JAKARTA_NAMESPACE_MAJOR;

        // This image ignores JAVA_OPTS_APPEND, and setting JAVA_OPTS would wipe
        // its --add-opens/--add-exports -- so append one line to standalone.conf.
        // -Dlogaperture.sweep.seconds=3 tightens the verification-sweep window
        // so the management-CLI-collision test does not wait 30s.
        // -Dlogaperture.handlerInstallDelaySeconds=3 switches on the (default-off, issue #87)
        // hold-back on handler-level installs (doc/specs/wildfly-deferred-handler-install.md)
        // so the deferral path stays exercised, kept short so the drop/trim scenarios, which
        // add rules right after boot, need not wait long for it.
        // -Dlogaperture.home points the agent's state store at a runtime-writable
        // dir, same fix and same reason as dev/wildfly/docker-compose.yml: this
        // image's $HOME (/opt/jboss) is owned by root, not writable by the jboss
        // user, so the default ${user.home}/.logaperture silently degrades every
        // run of this suite to session-only persistence (AccessDeniedException,
        // caught by WildFlyContainer.openStateStore()) -- undetected until
        // doc/specs/environment-report.md's "State file" fact gave it something
        // concrete to assert against and this suite's own env test caught it.
        String bootScript = "echo 'JAVA_OPTS=\"$JAVA_OPTS -javaagent:/opt/logaperture-agent.jar"
                + " -Dlogaperture.sweep.seconds=3"
                + " -Dlogaperture.handlerInstallDelaySeconds=3"
                // doc/specs/quieter-output.md: INFO so the handler-install messages this suite checks are
                // written (the default is WARN); a 1m summary so the drop-summary test needn't wait 10m.
                + " -Dlogaperture.diagnostics.level=INFO"
                + " -Dlogaperture.drop.summaryInterval=1m"
                + " -Dlogaperture.home=/opt/jboss/wildfly/standalone/tmp/logaperture\"'"
                + " >> \"$JBOSS_HOME/bin/standalone.conf\" && exec \"$JBOSS_HOME/bin/standalone.sh\" -b 0.0.0.0";

        wildfly = new GenericContainer<>(image)
                .withCopyFileToContainer(MountableFile.forHostPath(agentJar), "/opt/logaperture-agent.jar")
                .withCopyFileToContainer(MountableFile.forHostPath(cliJar), "/opt/logctl.jar")
                // The drop probe (issue #80) is a servlet driven over HTTP, so events can be fired on demand
                // after each reconfiguration and across a timed burst -- not only once at deploy time.
                .withExposedPorts(HTTP_PORT)
                .withCommand("sh", "-c", bootScript)
                .withLogConsumer(frame -> System.out.print("[wildfly] " + frame.getUtf8String()))
                .waitingFor(Wait.forLogMessage(".*WFLYSRV0025.*", 1).withStartupTimeout(Duration.ofMinutes(3)));
        wildfly.start();

        awaitControlPlane();
    }

    @AfterAll
    void stop() {
        if (wildfly != null) {
            wildfly.stop();
        }
    }

    // --- tests -------------------------------------------------------------------------------------

    @Test
    void wildFlyBootedCleanlyWithTheAgentAttached() throws Exception {
        String bootLog = exec("cat", SERVER_LOG).getStdout();
        assertTrue(bootLog.contains("WFLYSRV0025"), "WildFly reported a clean start");
        assertFalse(bootLog.contains("The LogManager was not properly installed"));
        assertFalse(bootLog.contains("The LogManager accessed before"));

        // The agent installs only if java.util.logging.manager is genuinely JBoss LogManager
        // by the time it runs (its readiness gate) -- so a working `logctl` implies a clean boot.
        assertTrue(logctl("list", "loggers", "org.jboss", "--show-all").stdout().contains(BOOT_LOGGER),
                "logctl lists the server's own loggers");
    }

    @Test
    void handlerLevelInstall_isDeferredAtBootThenCompletes() {
        // doc/specs/wildfly-deferred-handler-install.md D4: two INFO lines on the agent's own
        // output. The floor is 3s here (see startWildFly), so by the time any test runs the
        // second line is normally already out; polling covers a slow start.
        assertTrue(pollUntil(() -> wildfly.getLogs().contains("handler-level install deferred for 3s")),
                "the agent said it deferred the handler-level install");
        assertTrue(pollUntil(() -> wildfly.getLogs().contains("handler-level install complete")),
                "the agent said the deferred handler-level install completed");
    }

    @Test
    void handlerNameResolution_probesTheModelOnlyOnceLoggingSubsystemIsRegistered() throws Exception {
        // Issue #66: the resolver used to issue read-children-names under
        // /subsystem=logging on its first sweep, before that subsystem's
        // management resources exist -- WildFly itself logs WFLYCTL0013 at
        // ERROR for each one (7 handler resource types) on every boot. The
        // resolver now checks the root's child-type=subsystem names first
        // (always valid) and skips the per-handler-type reads until
        // "logging" appears there.
        String bootLog = exec("cat", SERVER_LOG).getStdout();
        assertFalse(bootLog.contains("WFLYCTL0013"), "no failed management-operation logging at boot:\n" + bootLog);

        // ... and resolution still succeeds once the subsystem is up.
        assertTrue(logctl("list", "handlers", "--show-all").stdout().contains("CONSOLE"),
                "CONSOLE is still resolved by name after the boot-time gate");
    }

    @Test
    void forOverride_raisesABootLoggerThenResetRestoresIt() {
        Logctl before = logctl("list", "loggers", BOOT_LOGGER, "--show-all");
        assertTrue(before.stdout().contains("INFO"), "org.jboss.as.server starts at INFO:\n" + before.stdout());

        Logctl raised = logctl("set", "logger", BOOT_LOGGER, "DEBUG", "for", "30m");
        assertEquals(0, raised.exitCode(), raised.stderr());
        assertTrue(logctl("list", "loggers", BOOT_LOGGER).stdout().contains("DEBUG"),
                "logctl list loggers shows the raised level");
        assertTrue(logctl("status").stdout().contains(BOOT_LOGGER),
                "logctl status shows the active override");

        assertEquals(0, logctl("reset", "logger", BOOT_LOGGER).exitCode());
        assertFalse(logctl("status").stdout().contains(BOOT_LOGGER),
                "the override is gone after reset");
    }

    @Test
    void standaloneXml_isByteIdenticalAfterASessionOfOverrides() throws Exception {
        String before = exec("md5sum", STANDALONE_XML).getStdout();

        logctl("set", "logger", "com.example.Probe", "DEBUG", "sticky");
        logctl("set", "logger", "org.hibernate.SQL", "TRACE");
        logctl("reset", "loggers", "--include-sticky");

        assertEquals(before, exec("md5sum", STANDALONE_XML).getStdout(),
                "the agent never writes standalone.xml");
    }

    @Test
    void deployedWarLogger_isVisibleUnderSystem_andSurvivesRedeploy() throws Exception {
        deployProbeWar();
        try {
            String levels = logctl("list", "loggers", "com.myapp.probe", "--show-all").stdout();
            assertTrue(levels.contains(APP_LOGGER),
                    "a deployed app's logger is visible:\n" + levels);
            assertFalse(levels.contains("CONTEXT"),
                    "stock WildFly routes the deployment to the one shared system context");

            assertEquals(0, logctl("set", "logger", APP_LOGGER, "DEBUG", "sticky").exitCode());
            assertTrue(logctl("list", "loggers", APP_LOGGER).stdout().contains("DEBUG"));

            redeployProbeWar();

            assertTrue(pollLogctl(out -> out.contains("DEBUG"), "list", "loggers", APP_LOGGER),
                    "the override is still in force after a redeploy");
        } finally {
            // --include-sticky: the override set above is sticky -- without the
            // flag this exact-name reset would refuse outright (doc/specs/
            // reset-command-surface.md, Decision #1), leaving it active for
            // whichever test runs next against this shared container.
            logctl("reset", "logger", APP_LOGGER, "--include-sticky");
            undeployProbeWar();
        }
    }

    @Test
    void managementCliLoggingChange_isCorrectedByTheVerificationSweep() throws Exception {
        assertEquals(0, logctl("set", "logger", BOOT_LOGGER, "DEBUG", "sticky").exitCode());
        assertTrue(logctl("list", "loggers", BOOT_LOGGER).stdout().contains("DEBUG"));

        // A /subsystem=logging change (as a management console does) clobbers the override.
        ExecResult added = exec(JBOSS_CLI, "--connect",
                "--command=/subsystem=logging/logger=" + BOOT_LOGGER + ":add(level=WARN)");
        assertEquals(0, added.getExitCode(), added.getStdout() + added.getStderr());
        try {
            assertTrue(pollLogctl(out -> out.contains("DEBUG"), "list", "loggers", BOOT_LOGGER),
                    "the verification sweep re-applied the override after the management change");
            assertTrue(wildfly.getLogs().lines().anyMatch(line ->
                            line.contains("source=verification-sweep")
                                    && line.contains("logger=" + BOOT_LOGGER)),
                    "a single verification-sweep audit entry names " + BOOT_LOGGER);
        } finally {
            // --include-sticky: same reasoning as deployedWarLogger's cleanup above.
            logctl("reset", "logger", BOOT_LOGGER, "--include-sticky");
            exec(JBOSS_CLI, "--connect", "--command=/subsystem=logging/logger=" + BOOT_LOGGER + ":remove");
        }
    }

    // --- pattern selection semantics (doc/specs/pattern-selection-semantics.md) --------------------

    @Test
    void trailingWildcardSet_isRejectedAsAUsageError_andMutatesNothing() {
        // Decision #5's exit criterion: a trailing-star target on setLogger
        // fails outright, in the same real process, and never reaches
        // matching/capability-checking/mutation.
        Logctl rejected = logctl("set", "logger", "org.jboss.as.*", "DEBUG");

        assertEquals(2, rejected.exitCode(), rejected.stdout() + rejected.stderr());
        assertTrue(rejected.stderr().contains("trailing wildcard isn't accepted"),
                "expected the Decision #5 usage error:\n" + rejected.stderr());
        assertTrue(rejected.stderr().contains("logctl set logger org.jboss.as DEBUG"),
                "expected the suggested fix naming the literal ancestor:\n" + rejected.stderr());
        assertFalse(logctl("status").stdout().contains("org.jboss.as"),
                "the rejected command must not have mutated anything");
    }

    @Test
    void ancestorSet_bringsAnAlreadyKnownDescendantToTheSameLevel_withNoOverrideOfItsOwn() throws Exception {
        // The exit criterion this spec replaced the old standing-rule/sweep
        // one with: setting the literal ancestor -- no star at all, since
        // setLogger now rejects a trailing one -- brings every descendant to
        // the same effective level purely through JBoss LogManager's own
        // inheritance, with no LogAperture override or audit record on the
        // descendant itself. BOOT_LOGGER (org.jboss.as.server) is a real,
        // already-known descendant of "org.jboss.as" on stock WildFly.
        try {
            assertEquals(0, logctl("set", "logger", "org.jboss.as", "DEBUG").exitCode());

            assertTrue(pollLogctl(out -> out.contains("DEBUG"), "list", "loggers", BOOT_LOGGER, "--show-all"),
                    "org.jboss.as.server inherits DEBUG from org.jboss.as with no override of its own");
            assertFalse(logctl("status").stdout().contains(BOOT_LOGGER),
                    "only 'org.jboss.as' itself was ever set -- its descendant carries no override");
        } finally {
            logctl("reset", "logger", "org.jboss.as");
        }
    }

    @Test
    void resetLevel_onATrailingStarTarget_revertsEveryCurrentlyOverriddenMatch_includingAnExactNameOne() {
        // Decision #5's asymmetry, proven end-to-end: unlike setLogger, reset
        // keeps full selection semantics for a trailing star -- it has to,
        // since a descendant can carry its own hand-set override (from an
        // exact-name set run directly against it) that only a real scan of
        // current matches finds (doc/specs/pattern-selection-semantics.md
        // "Operations").
        assertEquals(0, logctl("set", "logger", BOOT_LOGGER, "DEBUG", "sticky").exitCode()); // exact name, not the pattern
        assertTrue(logctl("list", "loggers", BOOT_LOGGER).stdout().contains("DEBUG"));

        // --include-sticky: the exact-name override above is sticky, and a
        // pattern reset leaves a sticky match in place by default (doc/specs/
        // reset-command-surface.md, Decision #1) -- this test is about
        // pattern-vs-exact-name selection, not sticky-skip, so it opts in.
        Logctl reverted = logctl("reset", "logger", "org.jboss.as.*", "--include-sticky");

        assertEquals(0, reverted.exitCode(), reverted.stderr());
        assertTrue(logctl("list", "loggers", BOOT_LOGGER, "--show-all").stdout().contains("INFO"),
                "the exact-name override under the trailing-star scope was reverted too");
        assertFalse(logctl("status").stdout().contains(BOOT_LOGGER));
    }

    // --- handler-floor control (doc/specs/handler-floor-control.md) --------------------------------

    @Test
    void handlerFloorWarning_namesTheConsoleHandlerByItsConfiguredName() {
        // Issue #14: WildFlyHandlerNameResolver reads the running server's own
        // /subsystem=logging model in-VM (via the ModelController, no socket,
        // no credentials) and recovers the configured name "CONSOLE". So the
        // blocking-handler warning names CONSOLE directly again -- per-handler
        // granularity, one actionable command -- instead of collapsing to the
        // reserved ALL_HANDLERS (issue #13, which stays the fallback while a
        // handler is still on an identity token). Confirmed against real
        // WildFly 26.1.3.Final.
        String freshLogger = "com.myapp.probe.HandlerWarningHarness";
        try {
            Logctl raised = logctl("set", "logger", freshLogger, "TRACE");
            assertEquals(0, raised.exitCode(), raised.stderr());
            assertTrue(raised.stdout().contains("handler CONSOLE is at"),
                    "the warning names the resolved handler, not a token or ALL_HANDLERS:\n" + raised.stdout());
            assertTrue(raised.stdout().contains("logctl set handler CONSOLE TRACE"),
                    "the warning's suggested command is directly copy-pasteable:\n" + raised.stdout());
        } finally {
            logctl("reset", "logger", freshLogger);
        }
    }

    @Test
    void handler_isAddressableByItsConfiguredName_andRevertsCleanly() {
        // Issue #14: `logctl set handler CONSOLE <level>` works by the name an
        // operator reads in standalone.xml -- no identity-hash token typed.
        try {
            Logctl raised = logctl("set", "handler", "CONSOLE", "DEBUG", "for", "30m");
            assertEquals(0, raised.exitCode(), raised.stderr());
            assertTrue(raised.stdout().contains("CONSOLE") && raised.stdout().contains("DEBUG"), raised.stdout());
            assertTrue(logctl("status").stdout().contains("CONSOLE"), "status shows the override under its real name");
        } finally {
            assertEquals(0, logctl("reset", "handler", "CONSOLE").exitCode());
        }
    }

    @Test
    void handlers_listsTheResolvedCatalog_withLevelsAndAnActiveOverride() {
        // Issue #15: `logctl list handlers` enumerates every addressable handler.
        // On real WildFly that is ALL_HANDLERS + the resolved CONSOLE / FILE.
        Logctl catalog = logctl("list", "handlers", "--show-all");
        assertEquals(0, catalog.exitCode(), catalog.stderr());
        String out = catalog.stdout();
        assertTrue(out.contains("HANDLER") && out.contains("LEVEL") && out.contains("TARGET"), out);
        assertTrue(out.contains("ALL_HANDLERS"), out);
        assertTrue(out.contains("CONSOLE"), out);
        assertTrue(out.contains("FILE") && out.contains("server.log"),
                "the FILE row shows its real rotating-log target:\n" + out);
        assertFalse(out.matches("(?s).*(PeriodicRotatingFileHandler|ConsoleHandler)@[0-9a-f]+.*"),
                "no identity-hash tokens once #14 resolution succeeds:\n" + out);

        try {
            assertEquals(0, logctl("set", "handler", "CONSOLE", "TRACE", "for", "10m").exitCode());
            String withOverride = logctl("list", "handlers").stdout();
            assertTrue(withOverride.contains("CONSOLE") && withOverride.contains("TRACE"),
                    "the CONSOLE row reflects the active override:\n" + withOverride);
        } finally {
            logctl("reset", "handler", "CONSOLE");
        }

        Logctl json = logctl("list", "handlers", "--show-all", "--json");
        assertEquals(0, json.exitCode(), json.stderr());
        assertTrue(json.stdout().contains("\"handlers\":[{") && json.stdout().contains("\"ref\":\"CONSOLE\""),
                json.stdout());
    }

    @Test
    void handlerLower_makesATraceLineReachTheConsole_thenResetStopsIt() throws Exception {
        String traceMarker = "probe trace marker";
        deployProbeWar();
        try {
            Logctl raised = logctl("set", "logger", APP_LOGGER, "TRACE");
            assertEquals(0, raised.exitCode(), raised.stderr());
            // Post issue #14 the blocking-handler warning names CONSOLE; the
            // lower/reset below still go through ALL_HANDLERS to keep the real
            // WildFly fan-out exercised (it now fans out over CONSOLE + FILE).
            assertTrue(raised.stdout().contains("CONSOLE"), raised.stdout());

            // Redeploy with the logger already at TRACE but the console
            // handler still at its default INFO floor: the marker line must
            // not reach the console yet.
            redeployProbeWar();
            assertFalse(wildfly.getLogs().contains(traceMarker),
                    "the console handler is still at INFO -- the TRACE marker must not reach it yet");

            Logctl lowered = logctl("set", "handler", "ALL_HANDLERS", "TRACE");
            assertEquals(0, lowered.exitCode(), lowered.stderr());
            assertTrue(lowered.stdout().contains("ALL_HANDLERS") && lowered.stdout().contains("TRACE"),
                    lowered.stdout());

            redeployProbeWar();
            assertTrue(pollUntil(() -> wildfly.getLogs().contains(traceMarker)),
                    "lowering every real handler to TRACE made the marker line actually reach the console");

            // Reset genuinely took effect, not just returned exit 0: a further
            // redeploy's marker must not add a new occurrence once every real
            // handler is back at its own default floor.
            assertEquals(0, logctl("reset", "handler", "ALL_HANDLERS").exitCode());
            long before = wildfly.getLogs().lines().filter(l -> l.contains(traceMarker)).count();
            redeployProbeWar();
            assertEquals(before, wildfly.getLogs().lines().filter(l -> l.contains(traceMarker)).count(),
                    "the console handler is back at INFO after reset -- no new marker line");
        } finally {
            logctl("reset", "handler", "ALL_HANDLERS"); // no-op if the test already reset it
            logctl("reset", "logger", APP_LOGGER);
            undeployProbeWar();
        }
    }

    // --- recipes (doc/specs/recipes.md) -----------------------------------------------------------

    private static final String PROBE_RECIPES = """
            schemaVersion: 1
            namespace: com.myapp.probe
            recipes:
              - name: watch
                summary: Watch the probe worker
                description: |
                  Everything the probe worker does.
                  # a line that is text, not a comment
                loggers:
                  - name: com.myapp.probe.Worker
                    level: DEBUG
                    reason: probe activity
            """;

    private static final String PROBE_LIB_RECIPES = """
            schemaVersion: 1
            namespace: com.myapp.lib
            recipes:
              - name: quiet
                summary: A library's recipe inside the war
                loggers:
                  - name: com.myapp.lib
                    level: DEBUG
            """;

    @Test
    void deployedWarsRecipe_isListedAndShown_fromItsClassPath() throws Exception {
        deployProbeWar();
        try {
            Logctl list = logctl("list", "recipes");
            assertEquals(0, list.exitCode(), list.stderr());
            assertTrue(list.stdout().contains("com.myapp.probe:watch"), "WEB-INF/classes' recipe:\n" + list.stdout());
            assertTrue(list.stdout().contains("probe.war!/WEB-INF/classes"), "its source names the deployment:\n"
                    + list.stdout());
            assertTrue(list.stdout().contains("com.myapp.lib:quiet"), "a WEB-INF/lib jar's recipe:\n" + list.stdout());
            assertTrue(list.stdout().contains("probe.war!/WEB-INF/lib/probe-recipes.jar"), list.stdout());

            Logctl show = logctl("show", "recipe", "com.myapp.probe:watch");
            assertEquals(0, show.exitCode(), show.stderr());
            assertTrue(show.stdout().contains("# a line that is text, not a comment"), show.stdout());
            assertTrue(show.stdout().contains("logger com.myapp.probe.Worker") && show.stdout().contains("-> DEBUG"),
                    show.stdout());
            assertFalse(logctl("status").stdout().contains("com.myapp.probe.Worker"),
                    "show recipe changes nothing");

            Logctl json = logctl("list", "recipes", "--json");
            assertTrue(json.stdout().contains("\"sourceKind\":\"LIBRARY\""), json.stdout());
            assertTrue(logctl("doctor").stdout().contains("recipe"), "doctor runs the recipe-files check");
        } finally {
            undeployProbeWar();
        }
    }

    /** doc/specs/recipes.md slice (b): apply a deployed war's recipe, see it carried, then reset it. */
    @Test
    void deployedWarsRecipe_appliesAndResets() throws Exception {
        deployProbeWar();
        try {
            Logctl applied = logctl("apply", "recipe", "com.myapp.probe:watch", "for", "10m", "--yes");
            assertEquals(0, applied.exitCode(), applied.stderr());
            assertTrue(applied.stdout().contains("Applied recipe com.myapp.probe:watch (1 change, FOR"),
                    applied.stdout());

            String loggers = logctl("list", "loggers", "com.myapp.probe.Worker").stdout();
            assertTrue(loggers.contains("DEBUG") && loggers.contains("RECIPE") && loggers.contains("com.myapp.probe:watch"),
                    "the override carries the recipe:\n" + loggers);
            assertTrue(logctl("list", "recipes").stdout().contains("(1 of 1)"), "APPLIED counts it");

            Logctl reset = logctl("reset", "recipe", "com.myapp.probe:watch");
            assertEquals(0, reset.exitCode(), reset.stderr());
            assertTrue(reset.stdout().contains("Reset recipe com.myapp.probe:watch (1 change)."), reset.stdout());
            assertFalse(logctl("status").stdout().contains("com.myapp.probe.Worker"), "nothing left in force");
        } finally {
            logctl("reset", "recipe", "com.myapp.probe:watch", "--include-sticky");
            undeployProbeWar();
        }
    }

    // --- doctor (doc/specs/doctor.md) ------------------------------------------------------------

    @Test
    void doctor_reportsStockWildFlyConfigAccurately() {
        // Confirmed against real WildFly 26.1.3.Final. Post issue #14, findings
        // name handlers by their configured name -- doctor inspects
        // adapter.realHandlers(), which now resolves "CONSOLE"/"FILE" from the
        // running server's own /subsystem=logging model instead of an
        // identity-hash token.
        Logctl result = logctl("doctor");
        assertEquals(0, result.exitCode(), result.stderr());
        String out = result.stdout();

        // The FILE-equivalent handler is a PeriodicRotatingFileHandler -- no
        // size-based rotation cap at all -- and is named "FILE" now.
        assertTrue(out.contains("has no size cap — writes are unbounded."),
                "expected an unbounded-growth finding:\n" + out);
        assertTrue(out.contains("FILE has no size cap") || out.contains("FILE has autoflush"),
                "issue #14: the finding names the FILE handler, not a token:\n" + out);
        assertFalse(out.matches("(?s).*(PeriodicRotatingFileHandler|ConsoleHandler)@[0-9a-f]+.*"),
                "issue #14: no identity-hash tokens once resolution succeeds:\n" + out);
        // Confirmed: stock WildFly's handlers report autoflush=true (isAutoFlush()
        // reflection against the real org.jboss.logmanager.ExtHandler base class).
        assertTrue(out.contains("has autoflush enabled — every record forces a flush."),
                "expected an autoflush finding:\n" + out);
        // Decision #3: a non-persistent handler (no targetPath -- the console
        // handler, concretely) never appears in a duplicate-output finding.
        // Stock WildFly has exactly one persistent handler, so this check
        // doesn't run at all.
        assertFalse(out.contains("duplicate"), "no duplicate-output finding should fire:\n" + out);
    }

    @Test
    void doctorJson_roundTripsWithFindingsAndChecksRun() {
        Logctl result = logctl("doctor", "--json");
        assertEquals(0, result.exitCode(), result.stderr());
        assertTrue(result.stdout().contains("\"findings\":["), result.stdout());
        assertTrue(result.stdout().contains("\"checksRun\":"), result.stdout());
    }

    // --- top (doc/specs/top.md) --------------------------------------------------------------------

    @Test
    void top_reportsByteVolumeFromRealBootLogging() {
        // No probe deployment needed to generate volume: the agent's premain
        // installs byte counting before WildFly's own main() ever starts, so
        // by the time this test runs, stock WildFly's own boot logging (which
        // is substantial -- dozens of subsystem-startup lines through the
        // real FILE-equivalent handler) has already been measured.
        Logctl result = logctl("top");
        assertEquals(0, result.exitCode(), result.stderr());
        String out = result.stdout();

        assertFalse(out.contains("No byte-volume measurements available yet."), out);
        assertTrue(out.contains("/h"), "expected at least one rendered rate:\n" + out);
        assertTrue(out.contains("stack traces"), out);
        assertTrue(out.contains("measured over"), out);
    }

    @Test
    void topJson_roundTripsWithLoggersAndMeasurementStartedAt() {
        Logctl result = logctl("top", "--json");
        assertEquals(0, result.exitCode(), result.stderr());
        String out = result.stdout();

        assertTrue(out.contains("\"loggers\":["), out);
        assertTrue(out.contains("\"measurementStartedAt\":\""), out);
        assertTrue(out.contains("\"trackedCount\":"), out);
        assertFalse(out.contains("\"measurementStartedAt\":null"), "measurement must already be running by now");
    }

    // --- storms (doc/specs/storm-detection.md) -----------------------------------------------------

    /**
     * The exit criterion: a probe deployment driven into two independent
     * tight exception-throwing loops is reported as two separate {@code
     * ONGOING} storms with a plausible count/rate, the right logger and
     * exception class, and a non-empty sample (issue #169: labelled with its
     * event number); {@code logctl
     * storms --json} round-trips with the documented shape.
     *
     * <p>NOTE: unverified in this environment (no Docker available to run
     * Testcontainers here) — written to the same pattern as every other test
     * in this class and intended to run in CI, where Docker is present.
     */
    @Test
    void storms_tightExceptionLoop_reportedAsOngoingWithPlausibleCountAndSample() throws Exception {
        enableStorms();
        deployStormProbeWar();
        try {
            assertTrue(pollLogctl(out -> out.contains("[ONGOING]") && out.contains("com.myapp.probe.StormA"),
                    "storms"), "expected StormA's loop to be reported ongoing: " + logctl("storms").stdout());

            Logctl result = logctl("storms");
            String out = result.stdout();
            assertTrue(out.contains("com.myapp.probe.StormA"), out);
            assertTrue(out.contains("com.myapp.probe.StormB"), out);
            assertTrue(out.contains("java.lang.RuntimeException"), out);
            assertTrue(out.contains("events"), out);
            assertTrue(out.contains("sample (event #"), out);
        } finally {
            undeployStormProbeWar();
            disableStorms();
        }
    }

    @Test
    void stormsJson_roundTripsWithTheDocumentedShape() throws Exception {
        enableStorms();
        deployStormProbeWar();
        try {
            assertTrue(pollLogctl(out -> out.contains("\"status\":\"ONGOING\""), "storms", "--json"),
                    "expected an ongoing storm in --json: " + logctl("storms", "--json").stdout());

            Logctl result = logctl("storms", "--json");
            assertEquals(0, result.exitCode(), result.stderr());
            String out = result.stdout();
            assertTrue(out.contains("\"storms\":["), out);
            assertTrue(out.contains("\"trackedCount\":"), out);
            assertTrue(out.contains("\"ongoingCount\":"), out);
            assertTrue(out.contains("\"loggerName\":\"com.myapp.probe.StormA\"")
                    || out.contains("\"loggerName\":\"com.myapp.probe.StormB\""), out);
            assertTrue(out.contains("\"detectionEnabled\":true"), out);
            assertTrue(out.contains("\"sampleEventNumber\":"), out);
        } finally {
            undeployStormProbeWar();
            disableStorms();
        }
    }

    /**
     * doc/specs/storm-detection-toggle.md: a bare {@code -javaagent} starts storm detection
     * disabled, so a storm loop is not tracked until it's enabled. Every storm test here leaves it
     * disabled again, so this holds whatever order the tests run in; a test that ran first leaves its
     * storms in the report, frozen (T6).
     */
    @Test
    void storms_disabledByDefault_tracksNothing_untilEnabled() throws Exception {
        Logctl status = logctl("status");
        assertTrue(status.stdout().contains("Storm detection: disabled"), status.stdout());

        // The probe fires its loops once, at deploy time. Deployed while disabled, the burst leaves
        // the report (empty, or frozen from an earlier test that enabled it, T6) unchanged.
        String before = logctl("storms", "--json").stdout();
        deployStormProbeWar();
        try {
            String after = logctl("storms", "--json").stdout();
            assertEquals(before, after, "nothing tracked while disabled");
            assertTrue(after.contains("\"detectionEnabled\":false"), after);
            Logctl disabled = logctl("storms");
            assertEquals(0, disabled.exitCode(), disabled.stderr());
            assertTrue(disabled.stdout().startsWith("Storm detection is disabled"), disabled.stdout());
        } finally {
            undeployStormProbeWar();
        }

        enableStorms();
        deployStormProbeWar();
        try {
            assertTrue(pollLogctl(out -> out.contains("[ONGOING]") && out.contains("com.myapp.probe.StormA"),
                    "storms"), "expected StormA once enabled: " + logctl("storms").stdout());
        } finally {
            undeployStormProbeWar();
            disableStorms();
        }
    }

    private void enableStorms() {
        Logctl result = logctl("enable", "storms", "--reason", "WildFlyContainerIT");
        assertEquals(0, result.exitCode(), result.stderr());
    }

    private void disableStorms() {
        Logctl result = logctl("disable", "storms");
        assertEquals(0, result.exitCode(), result.stderr());
    }

    private void deployStormProbeWar() throws Exception {
        Path war = buildStormProbeWar();
        wildfly.copyFileToContainer(MountableFile.forHostPath(war), DEPLOYMENTS + "/stormprobe.war");
        assertTrue(awaitFile(DEPLOYMENTS + "/stormprobe.war.deployed"), "stormprobe.war deployed");
    }

    private void undeployStormProbeWar() {
        exec("rm", "-f", DEPLOYMENTS + "/stormprobe.war");
        awaitFile(DEPLOYMENTS + "/stormprobe.war.undeployed");
    }

    /**
     * Two independent tight loops (distinct loggers, distinct messages), each
     * well past the default 1,000-event / 10s threshold, fired synchronously
     * at deploy time -- doc/specs/storm-detection.md's exit criterion's "two
     * independent loops are reported as two separate storms".
     */
    private Path buildStormProbeWar() throws IOException {
        String servletPackage = jakartaServletNamespace ? "jakarta.servlet" : "javax.servlet";
        String source = """
                package com.myapp.probe;
                import %s.ServletContextEvent;
                import %s.ServletContextListener;
                import %s.annotation.WebListener;
                import java.util.logging.Logger;
                @WebListener
                public class StormProbe implements ServletContextListener {
                    @Override public void contextInitialized(ServletContextEvent e) {
                        Logger a = Logger.getLogger("com.myapp.probe.StormA");
                        Logger b = Logger.getLogger("com.myapp.probe.StormB");
                        for (int i = 0; i < 1_200; i++) {
                            a.log(java.util.logging.Level.SEVERE, "storm A failed for order " + i,
                                    new RuntimeException("no capacity"));
                            b.log(java.util.logging.Level.SEVERE, "storm B connection reset " + i,
                                    new RuntimeException("peer reset"));
                        }
                    }
                }
                """.formatted(servletPackage, servletPackage, servletPackage);
        Path src = scratch.resolve("com/myapp/probe/StormProbe.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path classes = Files.createDirectories(scratch.resolve("storm-classes"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK (not JRE) is required to build the probe WAR");
        int rc = compiler.run(null, null, null,
                "--release", "17",
                "-classpath", probeCompileClasspath(),
                "-d", classes.toString(), src.toString());
        assertEquals(0, rc, "storm probe compile failed");

        Path war = scratch.resolve("stormprobe.war");
        Path probeClass = classes.resolve("com/myapp/probe/StormProbe.class");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(war))) {
            zip.putNextEntry(new ZipEntry("WEB-INF/classes/com/myapp/probe/StormProbe.class"));
            zip.write(Files.readAllBytes(probeClass));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("WEB-INF/beans.xml"));
            zip.write("<beans/>".getBytes());
            zip.closeEntry();
        }
        return war;
    }

    // --- trim (doc/specs/trim-rule.md) ---------------------------------------------------------

    /**
     * doc/specs/trim-rule.md's own exit criterion, the real-WildFly proof its
     * "Open decisions for sign-off" #5 committed to landing in this same
     * slice rather than deferring the way #72 ({@code drop}) had to: below
     * the keep-floor collapses to a one-liner plus marker (real {@code
     * ExtLogRecord} copy, via JBoss LogManager's own copy constructor,
     * reflection-reached), at/above it is spared, a cause chain
     * gets its own one-liner per level, an unrelated logger is untouched,
     * and {@code --frames N} keeps exactly N top frames. Restart-survival
     * (a {@code STICKY} trim resuming after a real restart) and
     * reconfiguration-survival (surviving a {@code pattern-formatter}/{@code
     * filter-spec}/new-handler/{@code :reload} change) are <em>not</em>
     * covered here -- both are exercised generically already ({@code
     * TrimRuleTest}'s persisted-payload round trip; the re-arm machinery is
     * the identical, already-proven mechanism {@code drop} established) and
     * scoping them out kept this pass tractable, the same kind of
     * proportionate reduction {@code drop-rule.md} itself recorded rather
     * than silently dropped.
     */
    @Test
    void trim_collapsesMatchingStackTraces_sparesEverythingElse() throws Exception {
        assertEquals(0, logctl("add", "rule", "trim", "com.myapp.probe.TrimWorker", "--below", "WARN").exitCode());
        assertEquals(0, logctl("add", "rule", "trim", "com.myapp.probe.FramesWorker", "--below", "WARN",
                "--frames", "1").exitCode());
        deployTrimProbeWar();
        try {
            assertTrue(pollUntil(() -> wildfly.getLogs().contains("trim probe frames")),
                    "expected the trim probe's deploy-time logging to complete");
            List<String> lines = wildfly.getLogs().lines().toList();

            assertHeaderThenMarkerThenNoFrame(lines, "boom below warn",
                    "below the WARN keep-floor collapses to a one-liner with the marker");
            assertHeaderThenFullTrace(lines, "boom at warn",
                    "at the WARN keep-floor itself is spared -- full trace, no marker");
            assertHeaderThenFullTrace(lines, "boom other logger",
                    "an unrelated logger with no rule attached is untouched -- full trace");

            int wrapperLine = lineContaining(lines, "wrapper");
            assertTrue(lines.get(wrapperLine).contains("[stack trace trimmed:"),
                    "the wrapping exception gets its own one-liner:\n" + lines.get(wrapperLine));
            int causedByLine = lineContaining(lines.subList(wrapperLine, lines.size()), "Caused by:") + wrapperLine;
            assertTrue(lines.get(causedByLine).contains("root cause")
                            && lines.get(causedByLine).contains("[stack trace trimmed:"),
                    "the cause gets its own one-liner too, not the full nested trace:\n"
                            + lines.get(causedByLine));

            int framesHeaderLine = lineContaining(lines, "boom frames");
            assertTrue(lines.get(framesHeaderLine).contains("[stack trace trimmed:"),
                    lines.get(framesHeaderLine));
            assertTrue(isFrameLine(lines.get(framesHeaderLine + 1)),
                    "--frames 1 keeps exactly one top frame:\n" + lines.get(framesHeaderLine + 1));
            assertFalse(isFrameLine(lines.get(framesHeaderLine + 2)),
                    "and no second frame:\n" + lines.get(framesHeaderLine + 2));
        } finally {
            undeployTrimProbeWar();
            logctl("reset", "rules"); // these rules are FOR-tier (the omitted-tier default), not STICKY
        }
    }

    private static boolean isFrameLine(String line) {
        return line.strip().startsWith("at ");
    }

    private static int lineContaining(List<String> lines, String text) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(text)) {
                return i;
            }
        }
        throw new AssertionError("no line containing '" + text + "' in:\n" + String.join("\n", lines));
    }

    /** The header line names {@code text} and carries the trim marker; the very next line is not a stack frame. */
    private static void assertHeaderThenMarkerThenNoFrame(List<String> lines, String text, String message) {
        int header = lineContaining(lines, text);
        assertTrue(lines.get(header).contains("[stack trace trimmed:"), message + ":\n" + lines.get(header));
        assertFalse(isFrameLine(lines.get(header + 1)), message + ":\n" + lines.get(header + 1));
    }

    /** The header line names {@code text}, carries no trim marker, and the very next line is a real stack frame. */
    private static void assertHeaderThenFullTrace(List<String> lines, String text, String message) {
        int header = lineContaining(lines, text);
        assertFalse(lines.get(header).contains("[stack trace trimmed:"), message + ":\n" + lines.get(header));
        assertTrue(isFrameLine(lines.get(header + 1)), message + ":\n" + lines.get(header + 1));
    }

    private void deployTrimProbeWar() throws Exception {
        Path war = buildTrimProbeWar();
        wildfly.copyFileToContainer(MountableFile.forHostPath(war), DEPLOYMENTS + "/trimprobe.war");
        assertTrue(awaitFile(DEPLOYMENTS + "/trimprobe.war.deployed"), "trimprobe.war deployed");
    }

    private void undeployTrimProbeWar() {
        exec("rm", "-f", DEPLOYMENTS + "/trimprobe.war");
        awaitFile(DEPLOYMENTS + "/trimprobe.war.undeployed");
    }

    /**
     * Five events fired synchronously at deploy time, each isolating one
     * behavior {@link #trim_collapsesMatchingStackTraces_sparesEverythingElse}
     * asserts on: below the keep-floor, at the keep-floor (spared), an
     * unrelated logger (untouched), a two-level cause chain, and a
     * {@code --frames 1} rule.
     */
    private Path buildTrimProbeWar() throws IOException {
        String servletPackage = jakartaServletNamespace ? "jakarta.servlet" : "javax.servlet";
        String source = """
                package com.myapp.probe;
                import %s.ServletContextEvent;
                import %s.ServletContextListener;
                import %s.annotation.WebListener;
                import java.util.logging.Level;
                import java.util.logging.Logger;
                @WebListener
                public class TrimProbe implements ServletContextListener {
                    @Override public void contextInitialized(ServletContextEvent e) {
                        Logger trimmed = Logger.getLogger("com.myapp.probe.TrimWorker");
                        Logger other = Logger.getLogger("com.myapp.probe.OtherWorker");
                        Logger framed = Logger.getLogger("com.myapp.probe.FramesWorker");

                        trimmed.log(Level.INFO, "trim probe below-warn",
                                new RuntimeException("boom below warn"));
                        trimmed.log(Level.WARNING, "trim probe at-warn spared",
                                new RuntimeException("boom at warn"));
                        Exception root = new IllegalStateException("root cause");
                        trimmed.log(Level.INFO, "trim probe cause chain",
                                new RuntimeException("wrapper", root));
                        other.log(Level.INFO, "trim probe other logger",
                                new RuntimeException("boom other logger"));
                        framed.log(Level.INFO, "trim probe frames",
                                new RuntimeException("boom frames"));
                    }
                }
                """.formatted(servletPackage, servletPackage, servletPackage);
        Path src = scratch.resolve("com/myapp/probe/TrimProbe.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path classes = Files.createDirectories(scratch.resolve("trim-classes"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK (not JRE) is required to build the probe WAR");
        int rc = compiler.run(null, null, null,
                "--release", "17",
                "-classpath", probeCompileClasspath(),
                "-d", classes.toString(), src.toString());
        assertEquals(0, rc, "trim probe compile failed");

        Path war = scratch.resolve("trimprobe.war");
        Path probeClass = classes.resolve("com/myapp/probe/TrimProbe.class");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(war))) {
            zip.putNextEntry(new ZipEntry("WEB-INF/classes/com/myapp/probe/TrimProbe.class"));
            zip.write(Files.readAllBytes(probeClass));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("WEB-INF/beans.xml"));
            zip.write("<beans/>".getBytes());
            zip.closeEntry();
        }
        return war;
    }

    // --- alter rule (doc/specs/alter-rule.md) ----------------------------------------------------

    /**
     * doc/specs/alter-rule.md "Testing": alter a live drop's {@code --message-contains} and see the
     * old message come back and the new one suppressed. The probe logs one "blue" and one "green"
     * line per deploy, so counting lines across two deploys shows which one each rule dropped.
     */
    @Test
    void alterRule_changesALiveDropsMatcher_inPlace() throws Exception {
        Logctl added = logctl("add", "rule", "drop", "com.myapp.probe.AlterWorker", "--message-contains", "blue",
                "--no-sample-full", "session");
        assertEquals(0, added.exitCode(), added.stderr());
        String id = added.stdout().strip().split("\\s+")[0];
        try {
            deployAlterProbeWar();
            assertTrue(pollUntil(() -> countLines("alter probe green") == 1), "first deploy logged");
            assertEquals(0, countLines("alter probe blue"), "blue is dropped");
            undeployAlterProbeWar();

            Logctl altered = logctl("alter", "rule", id, "--message-contains", "green");
            assertEquals(0, altered.exitCode(), altered.stderr());
            assertTrue(altered.stdout().contains("was: --message-contains blue"), altered.stdout());
            assertTrue(altered.stdout().contains("now: --message-contains green"), altered.stdout());
            assertTrue(logctl("list", "rules").stdout().lines().anyMatch(line -> line.strip().startsWith(id + " ")
                    && line.contains("SESSION")), "same id, same lifetime");

            deployAlterProbeWar();
            assertTrue(pollUntil(() -> countLines("alter probe blue") == 1), "blue comes back after the alter");
            assertEquals(1, countLines("alter probe green"), "green is now dropped: still only the first deploy's");
        } finally {
            undeployAlterProbeWar();
            logctl("reset", "rule", id);
        }
    }

    private long countLines(String text) {
        return wildfly.getLogs().lines().filter(line -> line.contains(text)).count();
    }

    private void deployAlterProbeWar() throws Exception {
        Path war = buildAlterProbeWar();
        exec("rm", "-f", DEPLOYMENTS + "/alterprobe.war.undeployed");
        wildfly.copyFileToContainer(MountableFile.forHostPath(war), DEPLOYMENTS + "/alterprobe.war");
        assertTrue(awaitFile(DEPLOYMENTS + "/alterprobe.war.deployed"), "alterprobe.war deployed");
    }

    private void undeployAlterProbeWar() {
        exec("rm", "-f", DEPLOYMENTS + "/alterprobe.war");
        awaitFile(DEPLOYMENTS + "/alterprobe.war.undeployed");
    }

    private Path buildAlterProbeWar() throws IOException {
        String servletPackage = jakartaServletNamespace ? "jakarta.servlet" : "javax.servlet";
        String source = """
                package com.myapp.probe;
                import %s.ServletContextEvent;
                import %s.ServletContextListener;
                import %s.annotation.WebListener;
                import java.util.logging.Logger;
                @WebListener
                public class AlterProbe implements ServletContextListener {
                    @Override public void contextInitialized(ServletContextEvent e) {
                        Logger worker = Logger.getLogger("com.myapp.probe.AlterWorker");
                        worker.info("alter probe blue");
                        worker.info("alter probe green");
                    }
                }
                """.formatted(servletPackage, servletPackage, servletPackage);
        Path src = scratch.resolve("com/myapp/probe/AlterProbe.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path classes = Files.createDirectories(scratch.resolve("alter-classes"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK (not JRE) is required to build the probe WAR");
        int rc = compiler.run(null, null, null,
                "--release", "17",
                "-classpath", probeCompileClasspath(),
                "-d", classes.toString(), src.toString());
        assertEquals(0, rc, "alter probe compile failed");

        Path war = scratch.resolve("alterprobe.war");
        Path probeClass = classes.resolve("com/myapp/probe/AlterProbe.class");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(war))) {
            zip.putNextEntry(new ZipEntry("WEB-INF/classes/com/myapp/probe/AlterProbe.class"));
            zip.write(Files.readAllBytes(probeClass));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("WEB-INF/beans.xml"));
            zip.write("<beans/>".getBytes());
            zip.closeEntry();
        }
        return war;
    }

    // --- drop (doc/specs/drop-rule.md, issue #80) ----------------------------------------------

    private static final String DROP_WORKER = "com.myapp.probe.DropWorker";
    private static final String DROP_OTHER = "com.myapp.probe.DropOther";
    private static final String DROP_TRIMMED = "com.myapp.probe.DropTrimmed";
    private static final String DROP_MATCH = "drop probe noisy";
    private static final String LOG_DIR = "/opt/jboss/wildfly/standalone/log";

    /** Makes every {@link #fireScenario} round's lines unique across the whole shared-container run. */
    private final AtomicInteger dropRounds = new AtomicInteger();

    /** One client for every {@link #fire} call, rather than one per request. */
    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * doc/specs/drop-rule.md "Testing" (cross-process), the epic's scenario restated for drop: an
     * INFO-level matching event is dropped; an ERROR-level one (at the keep-floor), a non-matching
     * message on the same logger and the same message on a different logger are all kept. Several
     * handlers (stock CONSOLE and FILE) see every event, and the hit count still climbs by one per
     * event, not one per handler (filtering-epic.md "Testing").
     */
    @Test
    void drop_deniesTheMatchingEventBelowTheFloor_keepsEverythingElse_countsOncePerEvent() throws Exception {
        String id = addDrop("--no-sample-full", "session");
        deployDropProbeWar();
        try {
            assertTrue(pollUntil(() -> roundDropped(fireScenario())), "the drop takes effect");

            String tag = fireScenario();
            assertTrue(roundDropped(tag), "the INFO-level matching event is dropped");
            assertEquals(1, countLines(tag + " error"), "an ERROR-level matching event is at the keep-floor, kept");
            assertEquals(1, countLines(tag + " quiet"), "a non-matching message on the same logger is kept");
            assertEquals(1, countLines(tag + " other"), "the same message on a different logger is kept");
            String serverLog = exec("cat", SERVER_LOG).getStdout();
            assertFalse(serverLog.contains(tag + " info"), "the FILE handler denies it too");
            assertTrue(serverLog.contains(tag + " quiet"), "and still writes what is kept");

            long before = hits(id);
            for (int i = 0; i < 3; i++) {
                assertTrue(roundDropped(fireScenario()));
            }
            assertEquals(before + 3, hits(id), "one hit per dropped event, however many handlers saw it");
        } finally {
            undeployDropProbeWar();
            logctl("reset", "rule", id);
        }
    }

    /**
     * doc/specs/drop-rule.md "Testing": the rule keeps denying after a {@code pattern-formatter}
     * change, a {@code filter-spec} write, a handler added at runtime and a {@code :reload} --
     * each one polled for, within the accepted re-arm gap (one verification-sweep tick), rather
     * than expected on the very first event after the change.
     */
    @Test
    void drop_keepsDenyingThroughWildFlyReconfiguration() throws Exception {
        String id = addDrop("--no-sample-full", "session");
        deployDropProbeWar();
        String originalPattern = readAttribute("/subsystem=logging/pattern-formatter=COLOR-PATTERN", "pattern");
        try {
            assertTrue(pollUntil(() -> roundDropped(fireScenario())), "the drop takes effect");

            cli("/subsystem=logging/pattern-formatter=COLOR-PATTERN:write-attribute(name=pattern,"
                    + "value=\"RECONF %-5p [%c] %s%e%n\")");
            assertTrue(pollUntil(() -> roundDroppedUnderPattern("RECONF")),
                    "still denying under the changed pattern-formatter");
            cli("/subsystem=logging/pattern-formatter=COLOR-PATTERN:write-attribute(name=pattern,"
                    + "value=\"" + originalPattern + "\")");

            cli("/subsystem=logging/console-handler=CONSOLE:write-attribute(name=filter-spec,value=accept)");
            assertTrue(pollUntil(() -> roundDropped(fireScenario())),
                    "still denying after WildFly replaced the handler's filter with its own");

            cli("/subsystem=logging/file-handler=LA_NEW:add(named-formatter=PATTERN,"
                    + "file={relative-to=jboss.server.log.dir,path=la-new.log})");
            cli("/subsystem=logging/root-logger=ROOT:add-handler(name=LA_NEW)");
            assertTrue(pollUntil(() -> roundDroppedIn("la-new.log")), "a handler added at runtime denies it too");

            reloadServer();
            assertTrue(pollUntil(() -> roundDropped(fireScenario())), "still denying after a :reload");
        } finally {
            exec(JBOSS_CLI, "--connect", "--command=/subsystem=logging/pattern-formatter=COLOR-PATTERN"
                    + ":write-attribute(name=pattern,value=\"" + originalPattern + "\")");
            exec(JBOSS_CLI, "--connect",
                    "--command=/subsystem=logging/console-handler=CONSOLE:undefine-attribute(name=filter-spec)");
            exec(JBOSS_CLI, "--connect", "--command=/subsystem=logging/root-logger=ROOT:remove-handler(name=LA_NEW)");
            exec(JBOSS_CLI, "--connect", "--command=/subsystem=logging/file-handler=LA_NEW:remove");
            undeployDropProbeWar();
            logctl("reset", "rule", id);
        }
    }

    /**
     * doc/specs/drop-rule.md "Testing": {@code sampleFull} and the periodic summary line, observed
     * for real. A burst of matching events over several {@code --sample-full} intervals lets the
     * first and then about one per interval through, and the summary line (on the JVM's stderr,
     * which WildFly logs) names the rule.
     */
    @Test
    void drop_sampleFull_letsOneThroughPerInterval_andTheSummaryLineAppears() throws Exception {
        String id = addDrop("--sample-full", "2s", "session");
        deployDropProbeWar();
        try {
            assertTrue(pollUntil(() -> fire("noisy", "warmup-" + dropRounds.incrementAndGet())), "probe reachable");
            String burst = "burst-" + dropRounds.incrementAndGet();
            int fired = 0;
            for (int i = 0; i < 16; i++) {
                if (fire("noisy", burst + "-" + i)) {
                    fired++;
                }
                sleep(500);
            }
            assertEquals(16, fired, "every burst request reached the probe");
            // doc/specs/quieter-output.md Q4/Q5: one consolidated line, through the server's own logging at
            // INFO under org.logaperture.drop -- never System.err, which WildFly files as ERROR [stderr].
            boolean summarised = false;
            for (int i = 0; i < 75 && !summarised; i++) {
                summarised = wildfly.getLogs().lines().anyMatch(line -> line.contains("INFO")
                        && line.contains("[org.logaperture.drop]") && line.contains("drop summary:")
                        && line.contains(id + " "));
                if (!summarised) {
                    sleep(1000);
                }
            }
            assertTrue(summarised, "the summary names the rule, at INFO under org.logaperture.drop");
            assertEquals(0, wildfly.getLogs().lines()
                    .filter(line -> line.contains("[stderr]") && line.contains("drop summary")).count(),
                    "no summary goes through System.err");
            long kept = countLines("[" + burst + "-");
            assertTrue(kept >= 2 && kept <= 6,
                    "about one full event per 2s interval across ~8s is let through, not all or none: " + kept);
        } finally {
            undeployDropProbeWar();
            logctl("reset", "rule", id);
        }
    }

    /**
     * filtering-epic.md "Testing": a structured formatter and an {@code AsyncHandler} on a real
     * server. Drop denies on both; trim collapses the trace behind the async handler (formatted on
     * its own thread) and leaves JSON untouched, as doc/specs/trim-rule.md "Text formatters only"
     * documents.
     */
    @Test
    void dropAndTrim_onAJsonHandlerAndBehindAnAsyncHandler() throws Exception {
        String dropId = addDrop("--no-sample-full", "session");
        Logctl trim = logctl("add", "rule", "trim", DROP_TRIMMED, "session");
        assertEquals(0, trim.exitCode(), trim.stderr());
        String trimId = trim.stdout().strip().split("\\s+")[0];
        deployDropProbeWar();
        try {
            cli("/subsystem=logging/json-formatter=LA_JSON:add");
            cli("/subsystem=logging/file-handler=LA_JSON_FILE:add(named-formatter=LA_JSON,"
                    + "file={relative-to=jboss.server.log.dir,path=la-json.log})");
            cli("/subsystem=logging/file-handler=LA_ASYNC_FILE:add(named-formatter=PATTERN,"
                    + "file={relative-to=jboss.server.log.dir,path=la-async.log})");
            cli("/subsystem=logging/async-handler=LA_ASYNC:add(queue-length=512,subhandlers=[LA_ASYNC_FILE])");
            cli("/subsystem=logging/logger=com.myapp.probe:add(handlers=[LA_JSON_FILE,LA_ASYNC])");

            assertTrue(pollUntil(() -> roundDroppedIn("la-json.log", "la-async.log")),
                    "the JSON handler and the async handler both deny the matching event");

            String tag = awaitTrimmedBehindTheAsyncHandler();
            List<String> async = exec("cat", LOG_DIR + "/la-async.log").getStdout().lines().toList();
            assertHeaderThenMarkerThenNoFrame(async, "boom " + tag,
                    "trimmed behind the async handler, formatted on its own thread");
            String jsonLine = exec("cat", LOG_DIR + "/la-json.log").getStdout().lines()
                    .filter(line -> line.contains("boom " + tag)).findFirst().orElseThrow();
            assertFalse(jsonLine.contains("[stack trace trimmed:"), "JSON is left untrimmed:\n" + jsonLine);
        } finally {
            exec(JBOSS_CLI, "--connect", "--command=/subsystem=logging/logger=com.myapp.probe:remove");
            exec(JBOSS_CLI, "--connect", "--command=/subsystem=logging/async-handler=LA_ASYNC:remove");
            exec(JBOSS_CLI, "--connect", "--command=/subsystem=logging/file-handler=LA_ASYNC_FILE:remove");
            exec(JBOSS_CLI, "--connect", "--command=/subsystem=logging/file-handler=LA_JSON_FILE:remove");
            exec(JBOSS_CLI, "--connect", "--command=/subsystem=logging/json-formatter=LA_JSON:remove");
            undeployDropProbeWar();
            logctl("reset", "rule", dropId);
            logctl("reset", "rule", trimId);
        }
    }

    /**
     * doc/specs/drop-rule.md "Testing": a {@code STICKY} drop survives a real restart and resumes
     * denying without a fresh {@code add rule drop} -- same id, still {@code STICKY}.
     */
    @Test
    void stickyDrop_survivesAContainerRestart() throws Exception {
        String id = addDrop("--no-sample-full", "sticky");
        deployDropProbeWar();
        try {
            assertTrue(pollUntil(() -> roundDropped(fireScenario())), "the drop takes effect");

            restartServer();

            assertTrue(pollUntil(() -> roundDropped(fireScenario())), "denying again after the restart");
            assertTrue(logctl("list", "rules").stdout().lines().anyMatch(line -> line.strip().startsWith(id + " ")
                    && line.contains("STICKY")), "the same rule, resumed rather than re-added");
        } finally {
            logctl("reset", "rule", id, "--include-sticky");
            undeployDropProbeWar();
        }
    }

    /** Fires a round and reports it dropped on the console, with the kept "quiet" line rendered starting with {@code prefix}. */
    private boolean roundDroppedUnderPattern(String prefix) {
        String tag = fireScenario();
        return roundDropped(tag) && wildfly.getLogs().lines()
                .anyMatch(line -> line.startsWith(prefix) && line.contains(tag + " quiet"));
    }

    /** Fires a round and reports it dropped on the console and in each of {@code files} (under the server log dir), which still carry its "quiet" line. */
    private boolean roundDroppedIn(String... files) {
        String tag = fireScenario();
        if (!roundDropped(tag)) {
            return false;
        }
        for (String file : files) {
            String content = exec("cat", LOG_DIR + "/" + file).getStdout();
            if (!content.contains(tag + " quiet") || content.contains(tag + " info")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Fires the {@code trim} event until one is rendered trimmed in {@code la-async.log} (the rule is
     * installed on the new handlers by a sweep tick, not at once); returns that event's {@code [tag]}.
     */
    private String awaitTrimmedBehindTheAsyncHandler() {
        for (int attempt = 0; attempt < 30; attempt++) {
            String bare = "trim-" + dropRounds.incrementAndGet();
            String tag = "[" + bare + "]";
            if (fire("trim", bare)) {
                sleep(1000); // the async handler writes on its own thread
                boolean trimmed = exec("cat", LOG_DIR + "/la-async.log").getStdout().lines()
                        .anyMatch(line -> line.contains("boom " + tag) && line.contains("[stack trace trimmed:"));
                if (trimmed) {
                    return tag;
                }
            } else {
                sleep(1000);
            }
        }
        throw new AssertionError("trim never collapsed the trace behind the async handler:\n"
                + exec("cat", LOG_DIR + "/la-async.log").getStdout());
    }

    /** {@code add rule drop} on {@link #DROP_WORKER} matching {@link #DROP_MATCH}; returns the new rule's id. */
    private String addDrop(String... options) {
        String[] args = new String[6 + options.length];
        args[0] = "add";
        args[1] = "rule";
        args[2] = "drop";
        args[3] = DROP_WORKER;
        args[4] = "--message-contains";
        args[5] = DROP_MATCH;
        System.arraycopy(options, 0, args, 6, options.length);
        Logctl added = logctl(args);
        assertEquals(0, added.exitCode(), added.stdout() + added.stderr());
        return added.stdout().strip().split("\\s+")[0];
    }

    /** The HITS column (last) of {@code list rules}' row for {@code id}. */
    private long hits(String id) {
        String row = logctl("list", "rules").stdout().lines()
                .filter(line -> line.strip().startsWith(id + " ")).findFirst()
                .orElseThrow(() -> new AssertionError("no list rules row for " + id));
        String[] cells = row.strip().split("\\s+");
        return Long.parseLong(cells[cells.length - 1]);
    }

    /**
     * One round of the scenario, each line tagged {@code [T]} -- "info" (matches, below the floor),
     * "error" (matches, at the floor), "quiet" (same logger, no match), "other" (match, other
     * logger). Returns the round's tag, or a tag no line will ever carry if the probe was
     * unreachable (mid-reload), so {@link #roundDropped} reports it as not-yet.
     */
    private String fireScenario() {
        String tag = "r" + dropRounds.incrementAndGet();
        return fire("scenario", tag) ? "[" + tag + "]" : "[unreached-" + tag + "]";
    }

    /**
     * Whether round {@code tag}'s INFO-level event was dropped -- read once its last line
     * ("other") has reached the console, since the four are logged in order on one thread.
     */
    private boolean roundDropped(String tag) {
        for (int i = 0; i < 10; i++) {
            if (countLines(tag + " other") > 0) {
                return countLines(tag + " info") == 0;
            }
            sleep(500);
        }
        return false;
    }

    /** GET the drop probe; false (not an exception) while the server is mid-reload or mid-restart. */
    private boolean fire(String set, String tag) {
        URI uri = URI.create("http://" + wildfly.getHost() + ":" + wildfly.getMappedPort(HTTP_PORT)
                + "/dropprobe/fire?set=" + set + "&tag=" + tag);
        try {
            HttpResponse<String> response = http.send(
                    HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Run one management operation, failing the test unless WildFly reports success. */
    private void cli(String operation) {
        ExecResult result = exec(JBOSS_CLI, "--connect", "--command=" + operation);
        assertTrue(result.getExitCode() == 0 && result.getStdout().contains("\"outcome\" => \"success\""),
                operation + "\n" + result.getStdout() + result.getStderr());
    }

    private String readAttribute(String address, String name) {
        ExecResult result = exec(JBOSS_CLI, "--connect", "--command=" + address + ":read-attribute(name=" + name + ")");
        Matcher value = Pattern.compile("\"result\" => \"(.*)\"").matcher(result.getStdout());
        assertTrue(value.find(), result.getStdout() + result.getStderr());
        return value.group(1);
    }

    /** {@code :reload}: same JVM and agent, WildFly's own services (logging subsystem included) rebuilt. */
    private void reloadServer() throws InterruptedException {
        awaitNextBoot("--command=:reload");
    }

    /** {@code :shutdown(restart=true)}: standalone.sh starts a fresh JVM, agent included. */
    private void restartServer() throws InterruptedException {
        awaitNextBoot("--command=:shutdown(restart=true)");
    }

    private void awaitNextBoot(String command) throws InterruptedException {
        long bootsBefore = countLines("WFLYSRV0025");
        exec(JBOSS_CLI, "--connect", command);
        for (int attempt = 0; attempt < 120; attempt++) {
            if (countLines("WFLYSRV0025") > bootsBefore) {
                awaitControlPlane();
                return;
            }
            Thread.sleep(1000);
        }
        fail("WildFly did not come back after " + command);
    }

    private void deployDropProbeWar() throws Exception {
        Path war = buildDropProbeWar();
        exec("rm", "-f", DEPLOYMENTS + "/dropprobe.war.undeployed");
        wildfly.copyFileToContainer(MountableFile.forHostPath(war), DEPLOYMENTS + "/dropprobe.war");
        assertTrue(awaitFile(DEPLOYMENTS + "/dropprobe.war.deployed"), "dropprobe.war deployed");
    }

    private void undeployDropProbeWar() {
        exec("rm", "-f", DEPLOYMENTS + "/dropprobe.war");
        awaitFile(DEPLOYMENTS + "/dropprobe.war.undeployed");
        exec("rm", "-f", DEPLOYMENTS + "/dropprobe.war.deployed");
    }

    /**
     * A servlet at {@code /dropprobe/fire?set=...&tag=...}: {@code scenario} logs the four
     * {@link #fireScenario} lines, {@code noisy} one matching INFO event, {@code trim} one INFO
     * event with an exception on {@link #DROP_TRIMMED}.
     */
    private Path buildDropProbeWar() throws IOException {
        String servletPackage = jakartaServletNamespace ? "jakarta.servlet" : "javax.servlet";
        String source = """
                package com.myapp.probe;
                import %s.annotation.WebServlet;
                import %s.http.HttpServlet;
                import %s.http.HttpServletRequest;
                import %s.http.HttpServletResponse;
                import java.io.IOException;
                import java.util.logging.Level;
                import java.util.logging.Logger;
                @WebServlet("/fire")
                public class DropProbe extends HttpServlet {
                    @Override protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
                        Logger worker = Logger.getLogger("%s");
                        String tag = "[" + req.getParameter("tag") + "]";
                        switch (req.getParameter("set")) {
                            case "scenario" -> {
                                worker.info("%s " + tag + " info");
                                worker.severe("%s " + tag + " error");
                                worker.info("drop probe quiet " + tag + " quiet");
                                Logger.getLogger("%s").info("%s " + tag + " other");
                            }
                            case "noisy" -> worker.info("%s " + tag + " info");
                            case "trim" -> Logger.getLogger("%s").log(Level.INFO, "drop probe trim " + tag,
                                    new RuntimeException("boom " + tag));
                            default -> resp.setStatus(400);
                        }
                        resp.getWriter().print("ok");
                    }
                }
                """.formatted(servletPackage, servletPackage, servletPackage, servletPackage,
                DROP_WORKER, DROP_MATCH, DROP_MATCH, DROP_OTHER, DROP_MATCH, DROP_MATCH, DROP_TRIMMED);
        Path src = scratch.resolve("com/myapp/probe/DropProbe.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path classes = Files.createDirectories(scratch.resolve("drop-classes"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK (not JRE) is required to build the probe WAR");
        int rc = compiler.run(null, null, null,
                "--release", "17",
                "-classpath", probeCompileClasspath(),
                "-d", classes.toString(), src.toString());
        assertEquals(0, rc, "drop probe compile failed");

        Path war = scratch.resolve("dropprobe.war");
        Path probeClass = classes.resolve("com/myapp/probe/DropProbe.class");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(war))) {
            zip.putNextEntry(new ZipEntry("WEB-INF/classes/com/myapp/probe/DropProbe.class"));
            zip.write(Files.readAllBytes(probeClass));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("WEB-INF/beans.xml"));
            zip.write("<beans/>".getBytes());
            zip.closeEntry();
        }
        return war;
    }

    // --- env (doc/specs/environment-report.md) -----------------------------------------------

    @Test
    void env_reportsTheRealJBossLogManagerAndWildFlyVersions() {
        Logctl result = logctl("env");
        assertEquals(0, result.exitCode(), result.stderr());
        String out = result.stdout();

        assertTrue(out.contains("LogAperture agent"), out);
        assertTrue(out.contains("Java"), out);
        assertTrue(out.contains("OS") && out.contains("Linux"), out);
        // Decision #3 (revised): the version source is the classic product
        // manifest (this image predates Galleon provisioning) plus the JBoss
        // LogManager root logger's own package version -- assert the actual
        // version numbers render, not just that the line/name is present.
        // The weaker "contains WildFly" form of this assertion is exactly
        // what let the original ($JBOSS_HOME/version.txt) implementation
        // ship broken: that file doesn't exist on this image, so the
        // container line rendered as bare "WildFly" with no version, and
        // this test still passed.
        assertTrue(out.contains("Logging backend") && out.matches("(?s).*JBoss LogManager \\d[\\w.]*Final.*"),
                "expected the real logging backend line with a version:\n" + out);
        assertTrue(out.contains("Framework/container") && out.contains("WildFly " + expectedContainerVersion),
                "expected the real container line with its actual version, not just the name:\n" + out);
        assertTrue(out.contains("Diagnostics level"), "shown either way, per Decision #5:\n" + out);
        // doc/specs/environment-report.md "State file" -- an absolute path
        // ending in .state.yaml, not the dash placeholder a degraded/no-op
        // store would render.
        assertTrue(out.matches("(?s).*State file\\s+/\\S*\\.state\\.yaml.*"),
                "expected a real, absolute state file path:\n" + out);
    }

    @Test
    void envJson_roundTripsWithAgentAndCliVersions() {
        Logctl result = logctl("env", "--json");
        assertEquals(0, result.exitCode(), result.stderr());
        String out = result.stdout();

        assertTrue(out.contains("\"agentVersion\":\""), out);
        assertTrue(out.contains("\"cliVersion\":\""), out);
        assertTrue(out.contains("\"backendName\":\"JBoss LogManager\""), out);
        assertFalse(out.contains("\"backendVersion\":null"), "expected a real backend version, not absent:\n" + out);
        assertTrue(out.contains("\"containerName\":\"WildFly\""), out);
        assertTrue(out.contains("\"containerVersion\":\"" + expectedContainerVersion + "\""),
                "expected the real container version, not absent:\n" + out);
        assertTrue(out.matches("(?s).*\"stateFilePath\":\"/\\S*\\.state\\.yaml\".*"),
                "expected a real, absolute state file path, not absent:\n" + out);
    }

    // --- probe WAR ------------------------------------------------------------------------------

    private void deployProbeWar() throws Exception {
        Path war = buildProbeWar();
        wildfly.copyFileToContainer(MountableFile.forHostPath(war), DEPLOYMENTS + "/probe.war");
        assertTrue(awaitFile(DEPLOYMENTS + "/probe.war.deployed"), "probe.war deployed");
        assertTrue(pollLogctl(out -> out.contains(APP_LOGGER), "list", "loggers", "com.myapp.probe", "--show-all"),
                "the app logger appeared after deploy");
    }

    private void redeployProbeWar() throws Exception {
        long initsBefore = probeInitCount();
        // A full undeploy -> deploy cycle: remove the archive, wait for the
        // scanner to undeploy, then copy it back. This is what the scanner does
        // internally for a changed archive anyway, and unlike a bare `touch` it
        // is picked up regardless of container-filesystem mtime granularity
        // (touch works locally but not on the CI runner). It also exercises the
        // adapter keeping the logger node (and its override) alive across the
        // deployment being gone entirely.
        exec("rm", "-f", DEPLOYMENTS + "/probe.war");
        assertTrue(awaitFile(DEPLOYMENTS + "/probe.war.undeployed"), "probe.war undeployed for redeploy");
        exec("rm", "-f", DEPLOYMENTS + "/probe.war.undeployed", DEPLOYMENTS + "/probe.war.deployed",
                DEPLOYMENTS + "/probe.war.failed");
        wildfly.copyFileToContainer(MountableFile.forHostPath(buildProbeWar()), DEPLOYMENTS + "/probe.war");
        assertTrue(awaitFile(DEPLOYMENTS + "/probe.war.deployed"), "probe.war redeployed");
        assertTrue(pollUntil(() -> probeInitCount() > initsBefore),
                "probe.war redeployed: contextInitialized ran a second time");
    }

    /** How many times the probe's {@code contextInitialized} has logged "probe deployed". */
    private long probeInitCount() {
        return wildfly.getLogs().lines()
                .filter(line -> line.contains(APP_LOGGER) && line.contains("probe deployed"))
                .count();
    }

    private void undeployProbeWar() {
        exec("rm", "-f", DEPLOYMENTS + "/probe.war");
        awaitFile(DEPLOYMENTS + "/probe.war.undeployed");
    }

    /** A jar holding only {@code META-INF/logaperture/recipes.yaml}. */
    private static byte[] recipesJar(String recipes) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream jar = new ZipOutputStream(bytes)) {
            jar.putNextEntry(new ZipEntry("META-INF/logaperture/recipes.yaml"));
            jar.write(recipes.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private Path buildProbeWar() throws IOException {
        String servletPackage = jakartaServletNamespace ? "jakarta.servlet" : "javax.servlet";
        String source = """
                package com.myapp.probe;
                import %s.ServletContextEvent;
                import %s.ServletContextListener;
                import %s.annotation.WebListener;
                import java.util.logging.Level;
                import java.util.logging.Logger;
                @WebListener
                public class Probe implements ServletContextListener {
                    // Held for as long as the deployment is: JBoss LogManager can drop an unreferenced,
                    // unconfigured logger once it's garbage-collected, so 'list loggers' may not see it (#69).
                    private static final Logger WORKER = Logger.getLogger("com.myapp.probe.Worker");
                    @Override public void contextInitialized(ServletContextEvent e) {
                        WORKER.info("probe deployed");
                        // FINEST == LogAperture TRACE (LevelMapper) -- the handler-floor-control.md
                        // exit criterion's marker line: only reaches the console once the CONSOLE
                        // handler itself has been lowered, however verbose the logger is.
                        Logger.getLogger("com.myapp.probe.Worker").log(Level.FINEST, "probe trace marker");
                    }
                }
                """.formatted(servletPackage, servletPackage, servletPackage);
        Path src = scratch.resolve("com/myapp/probe/Probe.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, source);
        Path classes = Files.createDirectories(scratch.resolve("classes"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "a JDK (not JRE) is required to build the probe WAR");
        int rc = compiler.run(null, null, null,
                "--release", "17", // class-file target only; every supported image runs JDK 17+
                "-classpath", probeCompileClasspath(),
                "-d", classes.toString(), src.toString());
        assertEquals(0, rc, "probe compile failed");

        Path war = scratch.resolve("probe.war");
        Path probeClass = classes.resolve("com/myapp/probe/Probe.class");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(war))) {
            zip.putNextEntry(new ZipEntry("WEB-INF/classes/com/myapp/probe/Probe.class"));
            zip.write(Files.readAllBytes(probeClass));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("WEB-INF/beans.xml"));
            zip.write("<beans/>".getBytes());
            zip.closeEntry();
            // doc/specs/recipes.md #3: a war's recipes sit on its class path -- under WEB-INF/classes,
            // or in a WEB-INF/lib jar (here one with no classes at all, only recipes).
            zip.putNextEntry(new ZipEntry("WEB-INF/classes/META-INF/logaperture/recipes.yaml"));
            zip.write(PROBE_RECIPES.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.putNextEntry(new ZipEntry("WEB-INF/lib/probe-recipes.jar"));
            zip.write(recipesJar(PROBE_LIB_RECIPES));
            zip.closeEntry();
        }
        return war;
    }

    /**
     * The servlet-api jar to compile the probe against, matching the
     * WildFly image under test (issue #65): {@code javax.servlet} through
     * WildFly 26, {@code jakarta.servlet} from 27 on. Maven's
     * dependency:properties goal sets {@code probe.compile.classpath.<ns>} to
     * that artifact's local path (see logaperture-it/pom.xml); fall back to
     * the forked JVM's full classpath only if that wiring is absent.
     */
    private String probeCompileClasspath() {
        String property = jakartaServletNamespace ? "probe.compile.classpath.jakarta" : "probe.compile.classpath.javax";
        String explicit = System.getProperty(property, "");
        if (!explicit.isBlank() && Files.isReadable(Path.of(explicit))) {
            return explicit;
        }
        return System.getProperty("java.class.path");
    }

    private boolean awaitFile(String path) {
        for (int i = 0; i < 60; i++) {
            if ("ok".equals(exec("sh", "-c", "test -f " + path + " && echo ok").getStdout().trim())) {
                return true;
            }
            sleep(1000);
        }
        return false;
    }

    // --- helpers ----------------------------------------------------------------------------------

    private record Logctl(int exitCode, String stdout, String stderr) {
    }

    private boolean pollLogctl(java.util.function.Predicate<String> until, String... args) {
        return pollUntil(() -> until.test(logctl(args).stdout()));
    }

    /** Poll a condition for up to 30s (1s between checks). */
    private boolean pollUntil(java.util.function.BooleanSupplier condition) {
        for (int i = 0; i < 30; i++) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(1000);
        }
        return false;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Logctl logctl(String... args) {
        String[] command = new String[args.length + 3];
        command[0] = "java";
        command[1] = "-jar";
        command[2] = "/opt/logctl.jar";
        System.arraycopy(args, 0, command, 3, args.length);
        ExecResult result = exec(command);
        return new Logctl(result.getExitCode(), result.getStdout(), result.getStderr());
    }

    private ExecResult exec(String... command) {
        try {
            return wildfly.execInContainer(command);
        } catch (Exception e) {
            throw new IllegalStateException("execInContainer " + String.join(" ", command) + " failed", e);
        }
    }

    private void awaitControlPlane() throws InterruptedException {
        for (int attempt = 0; attempt < 60; attempt++) {
            Logctl probe = logctl("list", "loggers", "org.jboss.as", "--show-all");
            if (probe.exitCode() == 0 && probe.stdout().contains("org.jboss.as")) {
                return;
            }
            Thread.sleep(1000);
        }
        fail("logctl never reached the agent's control plane inside the container");
    }

    private static String requireFile(String property, String value) {
        assertNotNull(value, "system property " + property + " must be set (reactor build order provides it)");
        assertTrue(Files.isRegularFile(Path.of(value)), property + " not found: " + value + " -- run `mvn package`");
        return value;
    }
}
