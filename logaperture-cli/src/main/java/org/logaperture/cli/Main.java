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
package org.logaperture.cli;

import org.logaperture.core.CapabilityDeniedException;
import org.logaperture.core.ConfirmationRequiredException;

import javax.management.RuntimeMBeanException;
import java.io.InputStream;
import java.io.PrintStream;

/**
 * {@code logctl} — the attach-API + local-JMX client for the level-control
 * surface (doc/specs/cli-transport.md). {@link #main} is a thin shell over
 * {@link #run}, which is the testable seam: it returns the process exit
 * code and writes only to the streams it is handed.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * The testable / embeddable seam (doc/specs/cli-transport.md "Module
     * scope"): parse {@code args}, act, and return the process exit code,
     * writing only to the streams given. A later in-process renderer (the
     * TUI of §8.3) is a caller of this, exactly like {@link #main}.
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        return run(args, out, err, Connector.REAL);
    }

    static int run(String[] args, PrintStream out, PrintStream err, Connector connector) {
        return run(args, out, err, connector, System.in, System.console() != null);
    }

    /**
     * The seam a test uses to exercise the confirmation-prompt flow (doc/
     * specs/pattern-level-targeting.md "Confirmation and CLI behavior")
     * through {@code Main} itself, not just directly against {@link
     * Commands#setLogger} -- {@code interactive} is otherwise derived from
     * {@link System#console()}, which is {@code null} in every test/CI
     * environment (a code-review finding: without this seam, {@code
     * MainRunTest} could reach only the "reject, pass --yes" non-interactive
     * branch of that flow, never the interactive prompt-and-read branch
     * production actually exercises on an operator's terminal).
     */
    static int run(String[] args, PrintStream out, PrintStream err, Connector connector, InputStream in,
            boolean interactive) {
        Invocation invocation;
        try {
            invocation = Parser.parse(args);
        } catch (CliError e) {
            err.println(e.getMessage());
            if (e.exitCode() == CliError.USAGE) {
                err.println();
                err.print(HelpText.usage());
            }
            return e.exitCode();
        }

        if (invocation.help()) {
            out.print(HelpText.usage());
            return CliError.OK;
        }
        if (invocation.version()) {
            out.println("logctl " + version());
            return CliError.OK;
        }

        try (ControlPlane controlPlane = connector.connect(invocation.pid())) {
            return invocation.command().run(controlPlane.mbean(), out, in, interactive);
        } catch (CliError e) {
            err.println(e.getMessage());
            return e.exitCode();
        } catch (CapabilityDeniedException denied) {
            // The real transport: controlPlane.mbean() is a JMX.newMXBeanProxy
            // (AgentConnection), and MBeanServerInvocationHandler unwraps a
            // RuntimeMBeanException back to the original unchecked exception
            // before rethrowing it -- an unchecked exception the operation
            // throws arrives here directly, never wrapped. Caught ahead of
            // IllegalArgumentException since this isn't one.
            err.println("Refused: this JVM's policy does not grant " + denied.capability() + ".");
            return CliError.REFUSED;
        } catch (ConfirmationRequiredException e) {
            // logctl's own setLogger flow always resolves confirmed=true (a
            // typed "y" or --yes) before it ever calls the server for real
            // (doc/specs/pattern-level-targeting.md "Confirmation and CLI
            // behavior") -- reaching here means a race between the preview
            // and the apply call, or a bug, not a usage mistake. Printed
            // plainly rather than falling through to a raw stack trace.
            err.println("logctl: " + messageOf(e));
            return CliError.UNEXPECTED;
        } catch (IllegalArgumentException e) {
            // Bad-argument validation done server-side (e.g. NameFilter's
            // grammar) is still a usage error (doc/specs/cli-transport.md "is
            // a usage error naming the problem"), not an unexpected failure,
            // even though it only surfaces after a successful parse -- once
            // we're here the command itself was well-formed, so there's no
            // usage block to print alongside it.
            err.println("logctl: " + messageOf(e));
            return CliError.USAGE;
        } catch (RuntimeMBeanException e) {
            // Kept as a defensive fallback in case some invocation path (a
            // future non-proxy MBean access, a different JDK's unwrapping
            // behavior) does hand this back still wrapped, rather than
            // unwrapped as the two catches above assume.
            Throwable target = e.getTargetException();
            if (target instanceof CapabilityDeniedException denied) {
                err.println("Refused: this JVM's policy does not grant " + denied.capability() + ".");
                return CliError.REFUSED;
            }
            if (target instanceof ConfirmationRequiredException) {
                err.println("logctl: " + messageOf(target));
                return CliError.UNEXPECTED;
            }
            if (target instanceof IllegalArgumentException) {
                err.println("logctl: " + messageOf(target));
                return CliError.USAGE;
            }
            if (invocation.debug()) {
                e.printStackTrace(err);
            }
            err.println("logctl: " + messageOf(target));
            return CliError.UNEXPECTED;
        } catch (Exception e) {
            if (invocation.debug()) {
                e.printStackTrace(err);
            }
            err.println("logctl: " + messageOf(e));
            return CliError.UNEXPECTED;
        }
    }

    private static String messageOf(Throwable t) {
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    /** Package-visible: {@code Commands.env} stitches this into the environment report alongside the agent's own version. */
    static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
