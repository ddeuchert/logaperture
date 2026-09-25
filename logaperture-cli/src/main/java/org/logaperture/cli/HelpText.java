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
            "logctl list loggers [filter] [--show-all]",
            "logctl list handlers [--show-all]",
            "logctl status",
            "logctl doctor",
            "logctl env",
            "logctl top [--limit n]",
            "logctl set logger <target> <level> [session | for <duration> | sticky]",
            "logctl set handler <name> <level> [session | for <duration> | sticky]",
            "logctl set handler <name> AUTO [session | for <duration> | sticky]",
            "logctl set default-handler <name> ...",
            "logctl reset logger <target> [--include-sticky] [--to-native] [--include-vendor-defaults]",
            "logctl reset loggers [--include-sticky] [--to-native]",
            "logctl reset handler <name> [--include-sticky] [--to-native]",
            "logctl reset handlers [--include-sticky] [--to-native]",
            "logctl reset default-handler [--to-native]",
            "logctl add rule drop <target> [matchers] [--below level] [--sample-full duration | --no-sample-full] "
                    + "[session | for <duration> | sticky]",
            "logctl add rule trim <target> [matchers] [--below level] [--frames n] [--collapse-causes] "
                    + "[session | for <duration> | sticky]",
            "logctl list rules [--verbose]",
            "logctl reset rule <id> [--include-sticky] [--include-vendor-defaults]",
            "logctl reset rules [--include-sticky] [--include-vendor-defaults]");

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
        sb.append("  --include-sticky     for 'reset' -- also revert a sticky override, not just skip it\n");
        sb.append("  --to-native          for 'reset' of loggers/handlers/default-handler -- land on the\n");
        sb.append("                       native configuration, ignoring the vendor defaults until restart\n");
        sb.append("  --include-vendor-defaults  for 'reset rule'/'reset rules'/'reset logger' -- also switch\n");
        sb.append("                       off vendor default rules, until the application restarts\n");
        sb.append("  --show-all           for 'list' -- every known logger or handler, not just overridden ones\n");
        sb.append("  --verbose            for 'list rules' -- add each rule's defining options (EXPRESSION)\n");
        sb.append("  --limit <n>          for 'top' — worst N offenders, 0 for every one tracked\n");
        sb.append("  --message-contains <text>\n");
        sb.append("                       for 'add rule' -- match a log message substring\n");
        sb.append("  --message-contains-ignore-case <text>\n");
        sb.append("                       for 'add rule' -- same, case-insensitive\n");
        sb.append("  --throwable <class>  for 'add rule' -- match a thrown exception's exact type\n");
        sb.append("  --throwable-message-contains <text>\n");
        sb.append("                       for 'add rule' -- match a substring of the thrown exception's message\n");
        sb.append("  --any-cause          for 'add rule' -- widen a throwable match to any cause in the chain\n");
        sb.append("  --below <level>       for 'add rule' -- only events strictly below this level, default ERROR\n");
        sb.append("  --sample-full <duration>   for 'add rule drop' -- let one full event through periodically\n");
        sb.append("  --no-sample-full      for 'add rule drop' -- never let a full event through\n");
        sb.append("  --frames <n>          for 'add rule trim' -- stack frames to keep, default 0\n");
        sb.append("  --collapse-causes     for 'add rule trim' -- fold the cause chain into one summary line\n");
        sb.append("  --json               machine-readable output\n");
        sb.append("  --version            print version and exit\n");
        sb.append("  -h, --help           this help\n");
        sb.append("\n");
        sb.append("A <duration> is <n>s, <n>m, <n>h or <n>d, for example: for 30m.\n");
        sb.append("A bare 'set logger'/'set handler' with no tier defaults to 'for 4h' — a\n");
        sb.append("working session, gone by morning.\n");
        sb.append("\n");
        sb.append("'set handler' sets a handler's own level directly — the fix when raising a\n");
        sb.append("logger still won't show output because a handler is set stricter. A raise\n");
        sb.append("that hits this prints which handler and the exact command to lower it.\n");
        sb.append("'reset handler <name>' reverts it on its own. 'logctl list handlers' lists\n");
        sb.append("every handler you can name, its level, and any active override — on\n");
        sb.append("WildFly the individual names (CONSOLE, FILE, …) appear once the server\n");
        sb.append("is up; before that, and always, ALL_HANDLERS means every handler at once.\n");
        sb.append("\n");
        sb.append("'set handler <name> AUTO' puts a handler into a self-adjusting mode instead\n");
        sb.append("of a fixed level — it tracks the lowest currently active debug or trace\n");
        sb.append("override on its own, and reverts to its native level the moment none are\n");
        sb.append("left. Setting a fixed level, or resetting it, moves it back out of AUTO.\n");
        sb.append("\n");
        sb.append("DEFAULT_HANDLERS is a handler name usable anywhere ALL_HANDLERS is, but its\n");
        sb.append("membership isn't 'every handler' — before it's ever assigned, a\n");
        sb.append("deterministic rule picks the one obvious handler (usually the console) so it\n");
        sb.append("already means something sensible. 'set default-handler FILE CONSOLE' assigns\n");
        sb.append("an explicit membership instead, persisted like a sticky override;\n");
        sb.append("'reset default-handler' clears it, reverting to the rule.\n");
        sb.append("\n");
        sb.append("A [filter] for 'list loggers' is a logger-name prefix, or a pattern with a\n");
        sb.append("leading and/or trailing * segment — so 'logctl list loggers *.infinispan\n");
        sb.append("--show-all' finds a logger when the log line shows only the short category\n");
        sb.append("name. Without --show-all, 'list loggers' and 'list handlers' show only\n");
        sb.append("rows with an active override.\n");
        sb.append("\n");
        sb.append("A <target> for 'set logger' is an exact logger name,\n");
        sb.append("or a leading-* pattern like '*.Worker' — a one-time selection applied\n");
        sb.append("to every currently-known match, nothing more: it previews those matches\n");
        sb.append("and asks to confirm before applying (--yes skips the prompt), but a\n");
        sb.append("logger created afterward that would also match is never touched. A\n");
        sb.append("trailing-* target ('org.apache.*') is rejected for this command --\n");
        sb.append("every descendant already inherits a set ancestor's level from the\n");
        sb.append("logging framework itself, so 'logctl set logger org.apache DEBUG' alone\n");
        sb.append("covers org.apache and everything under it, present and future, with nothing\n");
        sb.append("of LogAperture's own to show for the descendants.\n");
        sb.append("\n");
        sb.append("'reset logger <target>' takes either wildcard shape too -- it reverts\n");
        sb.append("whatever is currently overridden under that scope, including a logger\n");
        sb.append("set by its own exact name. 'reset loggers' and 'reset handlers' revert\n");
        sb.append("every currently-overridden logger, or handler, at once. Every reset form\n");
        sb.append("skips a sticky override by default -- naming one sticky target by its\n");
        sb.append("exact name refuses outright rather than silently doing nothing; a\n");
        sb.append("pattern or a bulk reset instead leaves it in place and reports it.\n");
        sb.append("--include-sticky reverts it anyway.\n");
        sb.append("\n");
        sb.append("'add rule drop <target>' and 'add rule trim <target>' attach a rule that\n");
        sb.append("acts on events from that logger before they're written, rather than\n");
        sb.append("changing the logger's level -- drop discards a matching event outright;\n");
        sb.append("trim keeps it but shortens its stack trace. Both take the same <target>\n");
        sb.append("shape as 'set logger', plus a content matcher (--message-contains,\n");
        sb.append("--throwable, etc.) and --below to bound which levels the rule applies to.\n");
        sb.append("'add rule drop' needs at least one content matcher; 'add rule trim'\n");
        sb.append("doesn't -- a bare level-bounded trim is a normal case. 'logctl list\n");
        sb.append("rules' lists every attached rule and its id;\n");
        sb.append("'reset rule <id>' removes one, 'reset rules' removes every currently\n");
        sb.append("attached rule.\n");
        sb.append("\n");
        sb.append("A vendor defaults file (-javaagent:logaperture-agent.jar=--vendor-defaults=<file>)\n");
        sb.append("sets the baseline: its logger and handler levels are what 'reset' returns\n");
        sb.append("to, and 'list' shows them in a VENDOR column. 'reset ... --to-native' goes\n");
        sb.append("back to the application's own logging configuration instead, until restart;\n");
        sb.append("a plain 'reset' undoes it. Its rules have 'vendor:' ids;\n");
        sb.append("reset leaves them in place unless --include-vendor-defaults is given, which\n");
        sb.append("switches them off until the application restarts.\n");
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
        sb.append("'status' shows active overrides, plus a line naming the vendor defaults\n");
        sb.append("file when there is one; 'env' is the separate, pasteable\n");
        sb.append("block for a bug report — see below.\n");
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
