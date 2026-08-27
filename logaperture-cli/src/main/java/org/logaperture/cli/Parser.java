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

    private static final Set<String> LEVEL_SUBCOMMANDS = Set.of("debug", "trace", "info", "warn", "error");

    private Parser() {
    }

    static Invocation parse(String[] argv) {
        List<String> positionals = new ArrayList<>();
        Long pid = null;
        boolean json = false;
        boolean includeChildren = false;
        boolean help = false;
        boolean version = false;
        boolean debug = false;
        boolean all = false;
        String reason = null;

        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            switch (arg) {
                case "-h", "--help" -> help = true;
                case "--version" -> version = true;
                case "--json" -> json = true;
                case "--debug" -> debug = true;
                case "--include-children" -> includeChildren = true;
                case "--all" -> all = true;
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
        boolean mutating = command.equals("set") || LEVEL_SUBCOMMANDS.contains(command);

        if (!mutating && (includeChildren || reason != null)) {
            throw usage("--reason and --include-children apply only to set/debug/trace/info/warn/error.");
        }
        if (all && !command.equals("reset")) {
            throw usage("--all applies only to 'reset'.");
        }

        Command resolved = switch (command) {
            case "levels" -> {
                if (rest.size() > 1) {
                    throw usage("'levels' takes at most one filter.");
                }
                yield Commands.levels(rest.isEmpty() ? null : rest.get(0), json);
            }
            case "status" -> {
                if (!rest.isEmpty()) {
                    throw usage("'status' takes no arguments.");
                }
                yield Commands.status(json);
            }
            case "reset" -> {
                if (all) {
                    if (!rest.isEmpty()) {
                        throw usage("'reset --all' takes no logger name.");
                    }
                    yield Commands.resetAll(json);
                }
                if (rest.size() != 1) {
                    throw usage("'reset' needs exactly one logger name, or --all.");
                }
                yield Commands.reset(rest.get(0), json);
            }
            case "set" -> {
                if (rest.size() < 2) {
                    throw usage("'set' needs <logger> <level> [session | for <duration> | sticky].");
                }
                TierChoice tier = resolveTier(rest.subList(2, rest.size()));
                yield Commands.setLevel(rest.get(0), parseLevel(rest.get(1)), includeChildren, reason,
                        tier.tierName(), tier.forSeconds(), json);
            }
            default -> {
                if (!LEVEL_SUBCOMMANDS.contains(command)) {
                    throw usage("Unknown command '" + command + "'.");
                }
                if (rest.isEmpty()) {
                    throw usage("'" + command + "' needs a <logger>.");
                }
                TierChoice tier = resolveTier(rest.subList(1, rest.size()));
                yield Commands.setLevel(rest.get(0), command.toUpperCase(Locale.ROOT), includeChildren, reason,
                        tier.tierName(), tier.forSeconds(), json);
            }
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

    private static CliError usage(String message) {
        return new CliError(CliError.USAGE, message);
    }

    /** ({@code "SESSION"}/{@code "FOR"}/{@code "STICKY"}, seconds) — {@code forSeconds} is ignored unless {@code FOR}. */
    record TierChoice(String tierName, long forSeconds) {
    }
}
