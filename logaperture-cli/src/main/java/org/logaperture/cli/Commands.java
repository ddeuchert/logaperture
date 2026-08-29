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

import org.logaperture.control.jmx.LevelOverrideData;
import org.logaperture.control.jmx.LoggerInfoData;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The four sub-commands, each a thin renderer over one (occasionally two)
 * {@link org.logaperture.control.jmx.LevelControlMXBean} calls — see
 * doc/specs/cli-transport.md "Command surface". {@code reset} reads
 * {@code listLoggers} before and after only because {@code resetLevel}/{@code
 * resetAll} return {@code void} — the "before" read is what lets it tell
 * "reverted a not-yet-instantiated logger" from "nothing to do".
 */
final class Commands {

    private Commands() {
    }

    static Command levels(String filter, boolean json) {
        return (mbean, out) -> {
            List<LoggerInfoData> rows = mbean.listLoggers(filter);
            if (json) {
                out.println(Json.loggers(rows));
                return CliError.OK;
            }
            if (rows.isEmpty()) {
                out.println(filter == null ? "No loggers known yet." : "No loggers match '" + filter + "'.");
                return CliError.OK;
            }
            boolean showContext = spansMultipleContexts(rows);
            List<List<String>> table = new ArrayList<>();
            for (LoggerInfoData row : rows) {
                List<String> cells = new ArrayList<>();
                if (showContext) {
                    cells.add(orDash(row.getContext()));
                }
                cells.add(orDash(row.getName()));
                cells.add(orDash(row.getConfiguredLevel()));
                cells.add(orDash(row.getEffectiveLevel()));
                cells.add(overrideCell(row));
                table.add(cells);
            }
            List<String> headers = new ArrayList<>();
            if (showContext) {
                headers.add("CONTEXT");
            }
            headers.addAll(List.of("LOGGER", "CONFIGURED", "EFFECTIVE", "OVERRIDE"));
            out.println(Format.table(headers, table));
            return CliError.OK;
        };
    }

    static Command status(boolean json) {
        return (mbean, out) -> {
            List<LoggerInfoData> all = mbean.listLoggers(null);
            List<LoggerInfoData> active = new ArrayList<>();
            for (LoggerInfoData row : all) {
                if (row.isOverrideActive()) {
                    active.add(row);
                }
            }
            active.sort(Comparator.comparing(Commands::revertSortKey).thenComparing(LoggerInfoData::getName));
            if (json) {
                out.println(Json.loggers(active));
                return CliError.OK;
            }
            if (active.isEmpty()) {
                out.println("No active overrides.");
                return CliError.OK;
            }
            boolean showContext = spansMultipleContexts(all);
            List<List<String>> table = new ArrayList<>();
            for (LoggerInfoData row : active) {
                List<String> cells = new ArrayList<>();
                if (showContext) {
                    cells.add(orDash(row.getContext()));
                }
                cells.add(orDash(row.getName()));
                cells.add(orDash(row.getEffectiveLevel()));
                cells.add(orDash(row.getTier()));
                cells.add(revertsCell(row));
                cells.add(row.getOverrideReason() == null ? Format.NONE : '"' + row.getOverrideReason() + '"');
                table.add(cells);
            }
            List<String> headers = new ArrayList<>();
            if (showContext) {
                headers.add("CONTEXT");
            }
            headers.addAll(List.of("LOGGER", "LEVEL", "TIER", "REVERTS", "REASON"));
            out.println(Format.table(headers, table));
            return CliError.OK;
        };
    }

    /**
     * The CONTEXT column shows only when the result actually spans more than
     * one logging context — a plain {@code java -jar} user, and a stock
     * standalone WildFly (one shared system context), never see it
     * (doc/specs/wildfly-support.md, Slice 3's "logctl changes").
     */
    private static boolean spansMultipleContexts(List<LoggerInfoData> rows) {
        return rows.stream()
                .map(LoggerInfoData::getContext)
                .filter(Objects::nonNull)
                .distinct()
                .limit(2)
                .count() > 1;
    }

    static Command setLevel(String logger, String level, boolean includeChildren, String reason,
            String tierName, long forSeconds, boolean json) {
        return (mbean, out) -> {
            LevelOverrideData result = mbean.setLevel(logger, level, includeChildren, reason, tierName, forSeconds);
            if (json) {
                out.println(Json.override(result));
                return CliError.OK;
            }
            out.println(logger + " → " + result.getLevel() + "   (" + tierDetail(result) + ")");
            return CliError.OK;
        };
    }

    static Command reset(String logger, boolean json) {
        return (mbean, out) -> {
            LoggerInfoData before = findLogger(mbean.listLoggers(logger), logger);
            boolean wasOverridden = before != null && before.isOverrideActive();
            mbean.resetLevel(logger);
            LoggerInfoData after = findLogger(mbean.listLoggers(logger), logger);
            if (json) {
                out.println(after != null ? Json.logger(after) : Json.reset(logger, wasOverridden));
                return CliError.OK;
            }
            if (after != null) {
                out.println(logger + " → " + after.getEffectiveLevel() + " (baseline)");
            } else if (wasOverridden) {
                out.println(logger + " → baseline (not yet instantiated, so no level to show)");
            } else {
                out.println(logger + " — nothing was overridden.");
            }
            return CliError.OK;
        };
    }

    static Command resetAll(boolean json) {
        return (mbean, out) -> {
            long activeBefore = mbean.listLoggers(null).stream().filter(LoggerInfoData::isOverrideActive).count();
            mbean.resetAll();
            if (json) {
                out.println(Json.revertedCount(activeBefore));
            } else {
                out.println("Reverted " + activeBefore + " override(s).");
            }
            return CliError.OK;
        };
    }

    /** Sorts soonest-revert first; {@code FOR} entries by their deadline, {@code STICKY}/{@code SESSION} (no deadline) last. */
    private static Instant revertSortKey(LoggerInfoData row) {
        return row.getExpiresAt() == null ? Instant.MAX : Instant.parse(row.getExpiresAt());
    }

    private static LoggerInfoData findLogger(List<LoggerInfoData> rows, String name) {
        return rows.stream().filter(r -> r.getName().equals(name)).findFirst().orElse(null);
    }

    private static String orDash(String value) {
        return value == null ? Format.NONE : value;
    }

    private static String overrideCell(LoggerInfoData row) {
        if (!row.isOverrideActive()) {
            return Format.NONE;
        }
        StringBuilder cell = new StringBuilder(orDash(row.getTier()));
        if ("FOR".equals(row.getTier()) && row.getExpiresAt() != null) {
            cell.append(", reverts ").append(Format.clock(row.getExpiresAt()))
                    .append(" (").append(Format.relative(row.getExpiresAt())).append(')');
        }
        if (row.getOverrideReason() != null) {
            cell.append(" — \"").append(row.getOverrideReason()).append('"');
        }
        return cell.toString();
    }

    private static String revertsCell(LoggerInfoData row) {
        if ("FOR".equals(row.getTier()) && row.getExpiresAt() != null) {
            return Format.clock(row.getExpiresAt()) + " (" + Format.relative(row.getExpiresAt()) + ")";
        }
        if ("SESSION".equals(row.getTier())) {
            return "until restart";
        }
        return "until reset";
    }

    private static String tierDetail(LevelOverrideData result) {
        return switch (result.getTier()) {
            case "SESSION" -> "SESSION — until the JVM stops";
            case "STICKY" -> "STICKY — until reset";
            case "FOR" -> result.getExpiresAt() == null
                    ? "FOR"
                    : "FOR, reverts " + Format.clock(result.getExpiresAt()) + " local — "
                            + Format.relative(result.getExpiresAt());
            default -> result.getTier();
        };
    }
}
