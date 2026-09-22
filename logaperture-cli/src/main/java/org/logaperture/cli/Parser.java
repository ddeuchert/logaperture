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

import org.logaperture.api.Level;
import org.logaperture.api.SampleFullPolicy;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns {@code argv} into an {@link Invocation}. The command grammar is
 * doc/specs/cli-transport.md "Command surface"; every rejection here is a
 * {@link CliError#USAGE} (exit 2).
 */
final class Parser {

    /**
     * A bare {@code debug}/{@code set} with no tier token defaults to this —
     * a working session, gone by morning (doc/specs/cli-transport.md, "The
     * omitted case defaults to FOR 4h").
     */
    static final Duration DEFAULT_FOR = Duration.ofHours(4);

    /** doc/specs/top.md Decision #9: worst-10 by default; {@code --limit 0} shows every tracked logger. */
    static final int DEFAULT_TOP_LIMIT = 10;

    private static final Set<String> RETIRED_LEVEL_VERBS = Set.of("debug", "trace", "info", "warn", "error");

    private Parser() {
    }

    static Invocation parse(String[] argv) {
        List<String> positionals = new ArrayList<>();
        Long pid = null;
        boolean json = false;
        boolean yes = false;
        boolean help = false;
        boolean version = false;
        boolean debug = false;
        boolean includeSticky = false;
        boolean showAll = false;
        String reason = null;
        Integer limit = null;
        String messageContains = null;
        boolean messageIgnoreCase = false;
        String throwableType = null;
        String throwableMessageContains = null;
        boolean anyCause = false;
        String belowLevel = null;
        Long sampleFullEveryMillis = null;
        boolean noSampleFull = false;

        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            switch (arg) {
                case "-h", "--help" -> help = true;
                case "--version" -> version = true;
                case "--json" -> json = true;
                case "--debug" -> debug = true;
                case "--yes" -> yes = true;
                case "--include-sticky" -> includeSticky = true;
                case "--show-all" -> showAll = true;
                case "--any-cause" -> anyCause = true;
                case "--no-sample-full" -> noSampleFull = true;
                case "--message-contains" -> {
                    i++;
                    if (i >= argv.length) {
                        throw usage("--message-contains needs a value.");
                    }
                    if (messageContains != null) {
                        throw usage("only one of --message-contains / --message-contains-ignore-case.");
                    }
                    messageContains = argv[i];
                }
                case "--message-contains-ignore-case" -> {
                    i++;
                    if (i >= argv.length) {
                        throw usage("--message-contains-ignore-case needs a value.");
                    }
                    if (messageContains != null) {
                        throw usage("only one of --message-contains / --message-contains-ignore-case.");
                    }
                    messageContains = argv[i];
                    messageIgnoreCase = true;
                }
                case "--throwable" -> {
                    i++;
                    if (i >= argv.length) {
                        throw usage("--throwable needs a fully-qualified exception class name.");
                    }
                    throwableType = argv[i];
                }
                case "--throwable-message-contains" -> {
                    i++;
                    if (i >= argv.length) {
                        throw usage("--throwable-message-contains needs a value.");
                    }
                    throwableMessageContains = argv[i];
                }
                case "--below" -> {
                    i++;
                    if (i >= argv.length) {
                        throw usage("--below needs a level (or FATAL).");
                    }
                    belowLevel = parseBelowLevel(argv[i]);
                }
                case "--sample-full" -> {
                    i++;
                    if (i >= argv.length) {
                        throw usage("--sample-full needs a duration, e.g. --sample-full 5m.");
                    }
                    sampleFullEveryMillis = Durations.parse(argv[i]).toMillis();
                }
                case "--all" -> throw usage("'reset --all' no longer exists -- use 'reset loggers' and "
                        + "'reset handlers'.");
                case "--pid" -> {
                    i++;
                    if (i >= argv.length) {
                        throw usage("--pid needs a process id.");
                    }
                    try {
                        pid = Long.parseLong(argv[i]);
                    } catch (NumberFormatException e) {
                        throw usage("--pid value '" + argv[i] + "' is not a number.");
                    }
                    if (pid <= 0) {
                        throw usage("--pid must be a positive process id.");
                    }
                }
                case "--reason" -> {
                    i++;
                    if (i >= argv.length) {
                        throw usage("--reason needs a value.");
                    }
                    reason = argv[i];
                }
                case "--limit" -> {
                    i++;
                    if (i >= argv.length) {
                        throw usage("--limit needs a value.");
                    }
                    try {
                        limit = Integer.parseInt(argv[i]);
                    } catch (NumberFormatException e) {
                        throw usage("--limit value '" + argv[i] + "' is not a number.");
                    }
                }
                default -> {
                    if (arg.startsWith("--")) {
                        throw usage("Unknown option '" + arg + "'.");
                    }
                    positionals.add(arg);
                }
            }
        }

        if (help) {
            return Invocation.forHelp();
        }
        if (version) {
            return Invocation.forVersion();
        }
        if (positionals.isEmpty()) {
            throw usage("No command given.");
        }

        String command = positionals.get(0);
        List<String> rest = positionals.subList(1, positionals.size());

        if (RETIRED_LEVEL_VERBS.contains(command)) {
            String target = rest.isEmpty() ? "<target>" : rest.get(0);
            throw usage("'" + command + "' no longer exists -- use 'set logger " + target + " "
                    + command.toUpperCase(Locale.ROOT) + "'.");
        }
        if (command.equals("handler")) {
            String handlerRef = rest.isEmpty() ? "<name>" : rest.get(0);
            if (rest.size() >= 2 && rest.get(1).equals("reset")) {
                throw usage("'handler <name> reset' no longer exists -- use 'reset handler " + handlerRef + "'.");
            }
            String levelToken = rest.size() > 1 ? rest.get(1) : "<level>";
            throw usage("'handler' no longer exists -- use 'set handler " + handlerRef + " " + levelToken + "'.");
        }
        if (command.equals("levels")) {
            String filter = rest.isEmpty() ? "" : " " + rest.get(0);
            throw usage("'levels' no longer exists -- use 'list loggers" + filter + "'.");
        }
        if (command.equals("handlers")) {
            throw usage("'handlers' no longer exists -- use 'list handlers'.");
        }

        boolean isSetLogger = command.equals("set") && !rest.isEmpty() && rest.get(0).equals("logger");
        boolean isSetHandler = command.equals("set") && !rest.isEmpty() && rest.get(0).equals("handler");
        boolean isAddRuleDrop = command.equals("add") && rest.size() >= 2 && rest.get(0).equals("rule")
                && rest.get(1).equals("drop");

        if (yes && !isSetLogger) {
            throw usage("--yes applies only to 'set logger'.");
        }
        if (reason != null && !isSetLogger && !isSetHandler && !isAddRuleDrop) {
            throw usage("--reason applies only to 'set logger', 'set handler', or 'add rule drop'.");
        }
        if (includeSticky && !command.equals("reset")) {
            throw usage("--include-sticky applies only to 'reset'.");
        }
        if (limit != null && !command.equals("top") && !command.equals("storms")) {
            throw usage("--limit applies only to 'top' or 'storms'.");
        }
        if (showAll && !command.equals("list")) {
            throw usage("--show-all applies only to 'list loggers' or 'list handlers'.");
        }
        if (!isAddRuleDrop) {
            if (messageContains != null || throwableType != null || throwableMessageContains != null || anyCause
                    || belowLevel != null || sampleFullEveryMillis != null || noSampleFull) {
                throw usage("the drop-matcher options apply only to 'add rule drop'.");
            }
        }
        if (sampleFullEveryMillis != null && noSampleFull) {
            throw usage("only one of --sample-full / --no-sample-full.");
        }

        Command resolved = switch (command) {
            case "list" -> {
                if (rest.isEmpty()) {
                    throw usage("'list' needs 'loggers [filter]', 'handlers', or 'rules'.");
                }
                String noun = rest.get(0);
                List<String> nounRest = rest.subList(1, rest.size());
                yield switch (noun) {
                    case "loggers" -> {
                        if (nounRest.size() > 1) {
                            throw usage("'list loggers' takes at most one filter.");
                        }
                        yield Commands.listLoggers(nounRest.isEmpty() ? null : nounRest.get(0), showAll, json);
                    }
                    case "handlers" -> {
                        if (!nounRest.isEmpty()) {
                            throw usage("'list handlers' takes no arguments.");
                        }
                        yield Commands.listHandlers(showAll, json);
                    }
                    case "rules" -> {
                        if (!nounRest.isEmpty()) {
                            throw usage("'list rules' takes no arguments.");
                        }
                        if (showAll) {
                            // doc/specs/rule-pipeline-foundation.md Decision #4: no distinct
                            // "overrides-only vs. full catalog" split for rules in this slice --
                            // every attached rule already is the "overridden" state.
                            throw usage("--show-all does not apply to 'list rules'.");
                        }
                        yield Commands.listRules(json);
                    }
                    default -> throw usage(
                            "'list' needs 'loggers [filter]', 'handlers', or 'rules', got '" + noun + "'.");
                };
            }
            case "status" -> {
                if (!rest.isEmpty()) {
                    throw usage("'status' takes no arguments.");
                }
                yield Commands.status(json);
            }
            case "top" -> {
                if (!rest.isEmpty()) {
                    throw usage("'top' takes no arguments.");
                }
                yield Commands.top(limit == null ? DEFAULT_TOP_LIMIT : limit, json);
            }
            case "doctor" -> {
                if (!rest.isEmpty()) {
                    throw usage("'doctor' takes no arguments.");
                }
                yield Commands.doctor(json);
            }
            case "storms" -> {
                if (!rest.isEmpty()) {
                    throw usage("'storms' takes no arguments.");
                }
                yield Commands.storms(limit == null ? 0 : limit, json);
            }
            case "env" -> {
                if (!rest.isEmpty()) {
                    throw usage("'env' takes no arguments.");
                }
                yield Commands.env(json);
            }
            case "reset" -> {
                if (rest.isEmpty()) {
                    throw usage("'reset' needs 'logger <target>', 'loggers', 'handler <name>', 'handlers', "
                            + "'rule <id>', or 'rules'.");
                }
                String noun = rest.get(0);
                List<String> nounRest = rest.subList(1, rest.size());
                yield switch (noun) {
                    case "logger" -> {
                        if (nounRest.size() != 1) {
                            throw usage("'reset logger' needs exactly one target.");
                        }
                        yield Commands.resetLogger(nounRest.get(0), includeSticky, json);
                    }
                    case "loggers" -> {
                        if (!nounRest.isEmpty()) {
                            throw usage("'reset loggers' takes no arguments.");
                        }
                        yield Commands.resetAllLoggers(includeSticky, json);
                    }
                    case "handler" -> {
                        if (nounRest.size() != 1) {
                            throw usage("'reset handler' needs exactly one handler name.");
                        }
                        yield Commands.resetHandler(nounRest.get(0), includeSticky, json);
                    }
                    case "handlers" -> {
                        if (!nounRest.isEmpty()) {
                            throw usage("'reset handlers' takes no arguments.");
                        }
                        yield Commands.resetAllHandlers(includeSticky, json);
                    }
                    case "rule" -> {
                        if (nounRest.size() != 1) {
                            throw usage("'reset rule' needs exactly one id.");
                        }
                        yield Commands.resetRule(nounRest.get(0), includeSticky, json);
                    }
                    case "rules" -> {
                        if (!nounRest.isEmpty()) {
                            throw usage("'reset rules' takes no arguments.");
                        }
                        yield Commands.resetAllRules(includeSticky, json);
                    }
                    case "default-handler" -> {
                        if (!nounRest.isEmpty()) {
                            throw usage("'reset default-handler' takes no arguments.");
                        }
                        yield Commands.setDefaultHandlerMembers(List.of(), json);
                    }
                    default -> throw usage("'reset' needs 'logger <target>', 'loggers', 'handler <name>', "
                            + "'handlers', 'rule <id>', 'rules', or 'default-handler', got '" + noun + "'.");
                };
            }
            case "set" -> {
                if (rest.isEmpty()) {
                    throw usage("'set' needs 'logger <target> <level>', 'handler <name> <level>', "
                            + "or 'default-handler <name> ...'.");
                }
                String noun = rest.get(0);
                List<String> nounRest = rest.subList(1, rest.size());
                yield switch (noun) {
                    case "logger" -> {
                        if (nounRest.size() < 2) {
                            throw usage("'set logger' needs <target> <level> [session | for <duration> | sticky].");
                        }
                        TierChoice tier = resolveTier(nounRest.subList(2, nounRest.size()));
                        yield Commands.setLogger(nounRest.get(0), parseLevel(nounRest.get(1)), reason,
                                tier.tierName(), tier.forSeconds(), yes, json);
                    }
                    case "handler" -> {
                        if (nounRest.size() < 2) {
                            throw usage("'set handler' needs <name> <level> or <name> AUTO.");
                        }
                        String handlerRef = nounRest.get(0);
                        TierChoice tier = resolveTier(nounRest.subList(2, nounRest.size()));
                        if (nounRest.get(1).equalsIgnoreCase("auto")) {
                            yield Commands.setHandlerAuto(handlerRef, reason, tier.tierName(), tier.forSeconds(),
                                    json);
                        }
                        yield Commands.setHandlerLevel(handlerRef, parseLevel(nounRest.get(1)), reason,
                                tier.tierName(), tier.forSeconds(), json);
                    }
                    case "default-handler" -> {
                        if (nounRest.isEmpty()) {
                            throw usage("'set default-handler' needs one or more handler names -- "
                                    + "use 'reset default-handler' to clear it.");
                        }
                        // No tier token here (doc/specs/handler-floor-control.md "Default
                        // handler group": membership is a standing config value, always
                        // persisted, not a reverting override) -- everything after
                        // "default-handler" is a handler name.
                        yield Commands.setDefaultHandlerMembers(nounRest, json);
                    }
                    default -> throw usage("'set' needs 'logger <target> <level>', 'handler <name> <level>', "
                            + "or 'default-handler <name> ...', got '" + noun + "'.");
                };
            }
            case "add" -> {
                if (rest.size() < 2 || !rest.get(0).equals("rule")) {
                    throw usage("'add' needs 'rule drop <target> ...'.");
                }
                String action = rest.get(1);
                List<String> actionRest = rest.subList(2, rest.size());
                yield switch (action) {
                    case "drop" -> {
                        if (actionRest.isEmpty()) {
                            throw usage("'add rule drop' needs <target>, e.g. 'add rule drop com.acme.Worker "
                                    + "--message-contains \"...\"'.");
                        }
                        if (messageContains == null && throwableType == null && throwableMessageContains == null) {
                            throw usage("'add rule drop' needs at least one content matcher "
                                    + "(--message-contains, --message-contains-ignore-case, --throwable, or "
                                    + "--throwable-message-contains) -- use 'set logger' to change a logger's "
                                    + "level instead.");
                        }
                        String target = actionRest.get(0);
                        if (target.endsWith(".*")) {
                            throw usage("'add rule drop' rejects a trailing '.*' -- a bare name already reaches "
                                    + "every descendant.");
                        }
                        TierChoice tier = resolveTier(actionRest.subList(1, actionRest.size()));
                        boolean sampleFullEnabled = !noSampleFull;
                        long everyMillis = sampleFullEveryMillis != null ? sampleFullEveryMillis
                                : SampleFullPolicy.DEFAULT_INTERVAL.toMillis();
                        // Omitted --below defaults to the ERROR keep-floor (doc/specs/drop-rule.md
                        // "Safety set") -- resolved here, not left null, so a bare "add rule drop"
                        // never compiles into an unbounded matcher that would also drop ERROR and
                        // above (a code-review finding).
                        String belowLevelOrDefault = belowLevel != null ? belowLevel : parseBelowLevel("ERROR");
                        yield Commands.addRuleDrop(target, messageContains, messageIgnoreCase, throwableType,
                                throwableMessageContains, anyCause, belowLevelOrDefault, sampleFullEnabled,
                                everyMillis, reason, tier.tierName(), tier.forSeconds(), json);
                    }
                    default -> throw usage("'add rule' needs 'drop', got '" + action + "'.");
                };
            }
            default -> throw usage("Unknown command '" + command + "'.");
        };

        return new Invocation(false, false, debug, pid, resolved);
    }

    /** The trailing {@code [session | for <duration> | sticky]} token(s). Package-private for direct testing. */
    static TierChoice resolveTier(List<String> tokens) {
        if (tokens.isEmpty()) {
            return new TierChoice("FOR", DEFAULT_FOR.toSeconds());
        }
        if (tokens.size() == 1) {
            return switch (tokens.get(0)) {
                case "session" -> new TierChoice("SESSION", 0L);
                case "sticky" -> new TierChoice("STICKY", 0L);
                case "for" -> throw usage("'for' needs a duration, e.g. for 30m.");
                default -> throw usage("Expected 'session', 'sticky' or 'for <duration>', got '" + tokens.get(0) + "'.");
            };
        }
        if (tokens.size() == 2 && tokens.get(0).equals("for")) {
            return new TierChoice("FOR", Durations.parse(tokens.get(1)).toSeconds());
        }
        throw usage("Too many arguments after the level — expected 'session', 'sticky' or 'for <duration>'.");
    }

    private static String parseLevel(String token) {
        try {
            return Level.valueOf(token.toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException e) {
            String known = java.util.Arrays.stream(Level.values()).map(Enum::name).collect(Collectors.joining(", "));
            throw usage("Unknown level '" + token + "' — expected one of " + known + ".");
        }
    }

    /**
     * {@code --below LEVEL}, resolved to the {@link
     * org.logaperture.api.CompiledMatchers#levelAtMost()} bound that
     * excludes {@code LEVEL} itself -- doc/specs/drop-rule.md "Data model":
     * "below ERROR" means ERROR and above are spared, so the compiled bound
     * is the level one step more verbose than the one named. {@code FATAL}
     * is accepted as a pseudo-token even though {@link Level} has no such
     * member (this codebase's {@code Level} model tops out at {@code
     * ERROR}; see that spec's "Divergence from prior specs") — it resolves
     * to {@code ERROR} itself, since nothing more severe exists to spare.
     */
    private static String parseBelowLevel(String token) {
        if (token.equalsIgnoreCase("FATAL")) {
            return Level.ERROR.name();
        }
        Level level = Level.valueOf(parseLevel(token));
        if (level.ordinal() == 0) {
            throw usage("'--below " + token + "' leaves nothing more verbose to drop.");
        }
        return Level.values()[level.ordinal() - 1].name();
    }

    private static CliError usage(String message) {
        return new CliError(CliError.USAGE, message);
    }

    /** ({@code "SESSION"}/{@code "FOR"}/{@code "STICKY"}, seconds) — {@code forSeconds} is ignored unless {@code FOR}. */
    record TierChoice(String tierName, long forSeconds) {
    }
}
