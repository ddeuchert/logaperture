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

import java.util.List;

/**
 * The {@code --help} text, and the command synopsis lines pulled out
 * separately so the phone-test check (doc/specs/cli-transport.md §6.2, "The
 * phone test, enforced") can assert every one of them is dictatable —
 * contains none of {@code : = ( ) /}.
 */
final class HelpText {

    /** One synopsis per invocation form. Kept punctuation-free on purpose. */
    static final List<String> SYNOPSES = List.of(
            "logctl levels [filter]",
            "logctl status",
            "logctl debug <logger> [session | for <duration> | sticky]",
            "logctl trace <logger> [session | for <duration> | sticky]",
            "logctl info <logger> [session | for <duration> | sticky]",
            "logctl warn <logger> [session | for <duration> | sticky]",
            "logctl error <logger> [session | for <duration> | sticky]",
            "logctl set <logger> <level> [session | for <duration> | sticky]",
            "logctl reset <logger>",
            "logctl reset --all");

    private HelpText() {
    }

    static String usage() {
        StringBuilder sb = new StringBuilder();
        sb.append("logctl — runtime logging control for a running JVM\n\n");
        sb.append("Usage:\n");
        for (String synopsis : SYNOPSES) {
            sb.append("  ").append(synopsis).append('\n');
        }
        sb.append("\nOptions:\n");
        sb.append("  --pid <n>            target this JVM instead of discovering one\n");
        sb.append("  --reason <text>      why — shown in status, kept in the audit trail\n");
        sb.append("  --include-children   also apply to loggers below this one\n");
        sb.append("  --json               machine-readable output\n");
        sb.append("  --version            print version and exit\n");
        sb.append("  -h, --help           this help\n");
        sb.append("\n");
        sb.append("A <duration> is <n>s, <n>m, <n>h or <n>d, for example: for 30m.\n");
        sb.append("A bare 'debug'/'set' with no tier defaults to 'for 4h' — a working\n");
        sb.append("session, gone by morning.\n");
        sb.append("\n");
        sb.append("logctl finds the target JVM on its own when exactly one is running with\n");
        sb.append("the agent attached; otherwise pass --pid. It works only for a JVM you\n");
        sb.append("could already attach a debugger to — authorization is the OS's.\n");
        sb.append("\n");
        sb.append("Requires a JDK, not just a JRE.\n");
        return sb.toString();
    }
}
