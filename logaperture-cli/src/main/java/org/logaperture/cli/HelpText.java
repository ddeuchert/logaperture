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
            "logctl handlers",
            "logctl status",
            "logctl doctor",
            "logctl env",
            "logctl top [--limit n]",
            "logctl debug <target> [session | for <duration> | sticky]",
            "logctl trace <target> [session | for <duration> | sticky]",
            "logctl info <target> [session | for <duration> | sticky]",
            "logctl warn <target> [session | for <duration> | sticky]",
            "logctl error <target> [session | for <duration> | sticky]",
            "logctl set <target> <level> [session | for <duration> | sticky]",
            "logctl reset logger <target> [--include-sticky]",
            "logctl reset loggers [--include-sticky]",
            "logctl reset handler <name> [--include-sticky]",
            "logctl reset handlers [--include-sticky]",
            "logctl reset --all [--include-sticky]",
            "logctl handler <name> <level> [session | for <duration> | sticky]",
            "logctl handler <name> AUTO [session | for <duration> | sticky]");

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
        sb.append("  --yes                skip the confirmation prompt when <target> is a pattern\n");
        sb.append("  --include-sticky     let a 'reset' form touch a sticky override or rule too\n");
        sb.append("  --limit <n>          for 'top' — worst N offenders, 0 for every one tracked\n");
        sb.append("  --json               machine-readable output\n");
        sb.append("  --version            print version and exit\n");
        sb.append("  -h, --help           this help\n");
        sb.append("\n");
        sb.append("A <duration> is <n>s, <n>m, <n>h or <n>d, for example: for 30m.\n");
        sb.append("A bare 'debug'/'set'/'handler' with no tier defaults to 'for 4h' — a\n");
        sb.append("working session, gone by morning.\n");
        sb.append("\n");
        sb.append("'handler' sets a handler's own level directly — the fix when raising a\n");
        sb.append("logger still won't show output because a handler is set stricter. A raise\n");
        sb.append("that hits this prints which handler and the exact command to lower it.\n");
        sb.append("'reset handler <name>' reverts it on its own. 'logctl handlers' lists\n");
        sb.append("every handler you can name, its level, and any active override — on\n");
        sb.append("WildFly the individual names (CONSOLE, FILE, …) appear once the server\n");
        sb.append("is up; before that, and always, ALL_HANDLERS means every handler at once.\n");
        sb.append("\n");
        sb.append("'handler <name> AUTO' puts a handler into a self-adjusting mode instead\n");
        sb.append("of a fixed level — it tracks the lowest currently active debug or trace\n");
        sb.append("override on its own, and reverts to its native level the moment none are\n");
        sb.append("left. Setting a fixed level, or resetting it, moves it back out of AUTO.\n");
        sb.append("\n");
        sb.append("A [filter] for 'levels' is a logger-name prefix, or a pattern with a\n");
        sb.append("leading and/or trailing * segment — so 'logctl levels *.infinispan'\n");
        sb.append("finds a logger when the log line shows only the short category name.\n");
        sb.append("\n");
        sb.append("A <target> for debug/trace/info/warn/error/set, or for 'reset logger', is\n");
        sb.append("an exact logger name, or that same * pattern — 'logctl debug org.apache.*'\n");
        sb.append("covers org.apache and everything under it in one command. A pattern is a\n");
        sb.append("standing rule: it also catches a logger discovered later that matches\n");
        sb.append("it (up to sweep-interval delay, default 30s, before a brand-new logger\n");
        sb.append("is caught — no instant hook on logger creation), previews its current\n");
        sb.append("matches and asks to confirm before applying (--yes skips the prompt).\n");
        sb.append("\n");
        sb.append("'reset logger' on that same pattern reverts its matches and retires the\n");
        sb.append("rule; a narrower target — one logger's exact name, or a narrower pattern\n");
        sb.append("under it — carves just that target out of the rule instead, leaving the\n");
        sb.append("rest of its reach untouched. Either way, you never need to know which\n");
        sb.append("command originally created the rule — point 'reset logger' at whatever\n");
        sb.append("'logctl status' shows.\n");
        sb.append("\n");
        sb.append("'reset loggers' reverts every logger override; 'reset handlers' reverts\n");
        sb.append("every handler override; 'reset --all' reverts both in one command. Every\n");
        sb.append("reset form leaves a sticky override alone by default -- add\n");
        sb.append("--include-sticky to revert one too, even one you named exactly.\n");
        sb.append("\n");
        sb.append("A trailing-wildcard pattern is worth reaching for even when an exact\n");
        sb.append("name would look the same today: it gives every matched logger, now and\n");
        sb.append("discovered later, its own audited override and its own capability check\n");
        sb.append("-- an exact name leaves descendant propagation to the underlying\n");
        sb.append("framework's own inheritance, with nothing of LogAperture's own to show\n");
        sb.append("for it. It's also the only way to reach every matching logger across\n");
        sb.append("more than one WildFly deployment context in one command.\n");
        sb.append("\n");
        sb.append("'doctor' is read-only — it never changes anything. It flags common\n");
        sb.append("misconfigurations (unbounded file handlers, DEBUG/TRACE left on,\n");
        sb.append("duplicate output, autoflush, low disk headroom) with a severity and,\n");
        sb.append("where there's an unambiguous one, the exact fix.\n");
        sb.append("\n");
        sb.append("'top' is also read-only. It shows which loggers have written the most\n");
        sb.append("bytes since the agent started, worst first, with a rate, a projected\n");
        sb.append("daily total, and what share of that volume is stack traces.\n");
        sb.append("\n");
        sb.append("'env' is also read-only. It prints one pasteable block of facts for a\n");
        sb.append("bug report -- agent and logctl versions, Java, OS, the detected logging\n");
        sb.append("backend, and the detected application framework or container.\n");
        sb.append("\n");
        sb.append("logctl finds the target JVM on its own when exactly one is running with\n");
        sb.append("the agent attached; otherwise pass --pid. It works only for a JVM you\n");
        sb.append("could already attach a debugger to — authorization is the OS's.\n");
        sb.append("\n");
        sb.append("Requires a JDK, not just a JRE.\n");
        return sb.toString();
    }
}
