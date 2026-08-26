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

import javax.management.RuntimeMBeanException;
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
            return invocation.command().run(controlPlane.mbean(), out);
        } catch (CliError e) {
            err.println(e.getMessage());
            return e.exitCode();
        } catch (RuntimeMBeanException e) {
            Throwable target = e.getTargetException();
            if (target instanceof CapabilityDeniedException denied) {
                err.println("Refused: this JVM's policy does not grant " + denied.capability() + ".");
                return CliError.REFUSED;
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

    private static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v != null ? v : "dev";
    }
}
