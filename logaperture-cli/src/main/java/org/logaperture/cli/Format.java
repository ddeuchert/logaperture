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

import org.logaperture.core.ByteFormat;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Plain-text rendering helpers: column-aligned tables, and the wall-clock
 * plus relative-time forms doc/specs/cli-transport.md shows in the
 * confirmation and {@code status} output.
 */
final class Format {

    static final String NONE = "—";

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private Format() {
    }

    /** Left-aligned, two-space gutter, trailing padding trimmed. {@code rows} cells must be non-null. */
    static String table(List<String> headers, List<List<String>> rows) {
        int[] width = columnWidths(headers.size(), rows, headers);
        StringBuilder out = new StringBuilder();
        appendRow(out, headers, width);
        for (List<String> row : rows) {
            appendRow(out, row, width);
        }
        return out.toString().stripTrailing();
    }

    /**
     * Same left-aligned, gutter-padded rendering as {@link #table(List, List)},
     * without a header row -- {@code top}'s table (doc/specs/top.md) has none.
     * {@code rows} must be non-empty and every row the same width.
     */
    static String table(List<List<String>> rows) {
        int[] width = columnWidths(rows.get(0).size(), rows, null);
        StringBuilder out = new StringBuilder();
        for (List<String> row : rows) {
            appendRow(out, row, width);
        }
        return out.toString().stripTrailing();
    }

    private static int[] columnWidths(int columns, List<List<String>> rows, List<String> headers) {
        int[] width = new int[columns];
        if (headers != null) {
            for (int c = 0; c < columns; c++) {
                width[c] = headers.get(c).length();
            }
        }
        for (List<String> row : rows) {
            for (int c = 0; c < columns; c++) {
                width[c] = Math.max(width[c], row.get(c).length());
            }
        }
        return width;
    }

    private static void appendRow(StringBuilder out, List<String> cells, int[] width) {
        StringBuilder line = new StringBuilder();
        for (int c = 0; c < cells.size(); c++) {
            String cell = cells.get(c);
            line.append(cell);
            if (c < cells.size() - 1) {
                line.append(" ".repeat(width[c] - cell.length() + 2));
            }
        }
        out.append(line.toString().stripTrailing()).append('\n');
    }

    /** Local wall-clock time of an ISO-8601 instant, e.g. {@code 15:42:00}. */
    static String clock(String isoInstant) {
        return Instant.parse(isoInstant).atZone(ZoneId.systemDefault()).format(CLOCK);
    }

    /**
     * Human-scaled byte count, e.g. {@code 9.6 GB}, {@code 412 MB} --
     * doc/specs/top.md. Delegates to {@link ByteFormat} -- the same
     * threshold-and-round logic {@code doctor}'s disk-headroom summaries
     * use, kept in one place so the two never drift apart.
     */
    static String bytes(double byteCount) {
        return ByteFormat.humanReadable(byteCount);
    }

    /** A coarse "how long", e.g. {@code 6h 12m}, {@code 27m}, {@code under a minute} -- doc/specs/top.md's "measured over" line. */
    static String elapsed(Duration duration) {
        long totalMinutes = Math.max(0, duration.toMinutes());
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) {
            return hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        }
        if (minutes > 0) {
            return minutes + "m";
        }
        return "under a minute";
    }

    /** A coarse "how far from now", e.g. {@code in 27m}, {@code in 3h 59m}, {@code in 8s}, or {@code now}. */
    static String relative(String isoInstant) {
        return relative(Duration.between(Instant.now(), Instant.parse(isoInstant)));
    }

    static String relative(Duration until) {
        long seconds = until.getSeconds();
        if (seconds <= 0) {
            return "now";
        }
        long days = seconds / 86_400;
        long hours = (seconds % 86_400) / 3_600;
        long minutes = (seconds % 3_600) / 60;
        if (days > 0) {
            return "in " + days + "d" + (hours > 0 ? " " + hours + "h" : "");
        }
        if (hours > 0) {
            return "in " + hours + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        }
        if (minutes > 0) {
            return "in " + minutes + "m";
        }
        return "in " + seconds + "s";
    }
}
