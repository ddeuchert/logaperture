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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
                + " -Dlogaperture.home=/opt/jboss/wildfly/standalone/tmp/logaperture\"'"
                + " >> \"$JBOSS_HOME/bin/standalone.conf\" && exec \"$JBOSS_HOME/bin/standalone.sh\" -b 0.0.0.0";

        wildfly = new GenericContainer<>(image)
                .withCopyFileToContainer(MountableFile.forHostPath(agentJar), "/opt/logaperture-agent.jar")
                .withCopyFileToContainer(MountableFile.forHostPath(cliJar), "/opt/logctl.jar")
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
     * exception class, and a non-empty first occurrence; {@code logctl
     * storms --json} round-trips with the documented shape.
     *
     * <p>NOTE: unverified in this environment (no Docker available to run
     * Testcontainers here) — written to the same pattern as every other test
     * in this class and intended to run in CI, where Docker is present.
     */
    @Test
    void storms_tightExceptionLoop_reportedAsOngoingWithPlausibleCountAndFirstOccurrence() throws Exception {
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
            assertTrue(out.contains("first occurrence:"), out);
        } finally {
            undeployStormProbeWar();
        }
    }

    @Test
    void stormsJson_roundTripsWithTheDocumentedShape() throws Exception {
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
        } finally {
            undeployStormProbeWar();
        }
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
                    @Override public void contextInitialized(ServletContextEvent e) {
                        Logger.getLogger("com.myapp.probe.Worker").info("probe deployed");
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
