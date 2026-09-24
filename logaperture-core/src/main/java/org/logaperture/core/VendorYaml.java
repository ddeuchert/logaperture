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
package org.logaperture.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The YAML subset the vendor defaults file is written in — doc/specs/vendor-defaults.md
 * "Format": block maps, block lists (of maps or scalars), flow lists of scalars ({@code [A,
 * B]}), plain, single- and double-quoted scalars, and {@code #} comments. Nothing else: no
 * anchors, no multiple documents, no block scalars, no tabs in indentation. Hand-written for
 * the same reason {@link StateFileFormat} is — no third-party parser in the agent — but,
 * unlike that format, this one is authored by a person, so every node keeps its line number
 * for error messages.
 *
 * <p>A syntax error stops the parse ({@link SyntaxException}); structure the reader can't
 * follow leaves nothing reliable to validate. Schema checks (unknown keys, bad levels, …)
 * happen afterwards in {@link VendorDefaultsFile}, where they are collected, not stopped at
 * the first.
 */
final class VendorYaml {

    sealed interface Node permits MapNode, ListNode, ScalarNode {
        int line();
    }

    /** Keys keep file order; {@code keyLines} gives each key's own line. */
    record MapNode(Map<String, Node> entries, Map<String, Integer> keyLines, int line) implements Node {
    }

    record ListNode(List<Node> items, int line) implements Node {
    }

    /** {@code quoted} distinguishes {@code "true"} (a string) from {@code true} where it matters. */
    record ScalarNode(String value, boolean quoted, int line) implements Node {
    }

    static final class SyntaxException extends Exception {
        private final int line;

        SyntaxException(int line, String message) {
            super(message);
            this.line = line;
        }

        int line() {
            return line;
        }
    }

    /** One non-blank, comment-stripped source line. */
    private record Line(int number, int indent, String text) {
    }

    private final List<Line> lines;
    private int position;

    private VendorYaml(List<Line> lines) {
        this.lines = lines;
    }

    /**
     * @return the document's root map; an empty document is an empty map
     */
    static MapNode parse(String content) throws SyntaxException {
        VendorYaml reader = new VendorYaml(tokenize(content));
        if (reader.lines.isEmpty()) {
            return new MapNode(new LinkedHashMap<>(), new LinkedHashMap<>(), 1);
        }
        Line first = reader.lines.get(0);
        if (first.indent() != 0) {
            throw new SyntaxException(first.number(), "the document must start at column 1");
        }
        if (isListItem(first.text())) {
            throw new SyntaxException(first.number(), "the document must be a map of keys, not a list");
        }
        MapNode root = reader.parseMap(0);
        if (reader.position < reader.lines.size()) {
            Line extra = reader.lines.get(reader.position);
            throw new SyntaxException(extra.number(), "unexpected indentation");
        }
        return root;
    }

    private static List<Line> tokenize(String content) throws SyntaxException {
        List<Line> result = new ArrayList<>();
        String[] raw = content.split("\r\n|\n|\r", -1);
        for (int i = 0; i < raw.length; i++) {
            int number = i + 1;
            String line = raw[i];
            if (number == 1 && line.startsWith("﻿")) {
                line = line.substring(1); // tolerate a UTF-8 byte-order mark
            }
            String stripped = stripComment(line, number);
            if (stripped.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < stripped.length() && (stripped.charAt(indent) == ' ' || stripped.charAt(indent) == '\t')) {
                if (stripped.charAt(indent) == '\t') {
                    throw new SyntaxException(number, "tabs are not allowed for indentation -- use spaces");
                }
                indent++;
            }
            if (stripped.trim().equals("---") || stripped.trim().equals("...")) {
                throw new SyntaxException(number, "multiple documents are not supported");
            }
            result.add(new Line(number, indent, stripped.substring(indent).stripTrailing()));
        }
        return result;
    }

    /** Removes a {@code #} comment that is outside quotes and at line start or after whitespace. */
    private static String stripComment(String line, int number) throws SyntaxException {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == '\\' && quote == '"') {
                    i++;
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '#' && (i == 0 || Character.isWhitespace(line.charAt(i - 1)))) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static boolean isListItem(String text) {
        return text.equals("-") || text.startsWith("- ");
    }

    private MapNode parseMap(int indent) throws SyntaxException {
        Map<String, Node> entries = new LinkedHashMap<>();
        Map<String, Integer> keyLines = new LinkedHashMap<>();
        int mapLine = lines.get(position).number();
        while (position < lines.size()) {
            Line line = lines.get(position);
            if (line.indent() < indent) {
                break;
            }
            if (line.indent() > indent) {
                throw new SyntaxException(line.number(), "unexpected indentation");
            }
            if (isListItem(line.text())) {
                throw new SyntaxException(line.number(), "a list item where a 'key: value' was expected");
            }
            position++;
            parseMapEntry(line, line.text(), indent, entries, keyLines);
        }
        return new MapNode(entries, keyLines, mapLine);
    }

    /**
     * Parses {@code text} (one {@code key: value} or {@code key:}) into {@code entries}; a bare
     * {@code key:} takes the following, more-indented block (or a list at the same indent, as
     * YAML allows) as its value.
     */
    private void parseMapEntry(Line line, String text, int indent, Map<String, Node> entries,
            Map<String, Integer> keyLines) throws SyntaxException {
        int colon = keyColon(text, line.number());
        String key = text.substring(0, colon).trim();
        if (key.isEmpty()) {
            throw new SyntaxException(line.number(), "a key is missing before ':'");
        }
        if (key.startsWith("\"") || key.startsWith("'")) {
            throw new SyntaxException(line.number(), "quoted keys are not supported");
        }
        if (entries.containsKey(key)) {
            throw new SyntaxException(line.number(), "'" + key + "' appears twice");
        }
        String rest = text.substring(colon + 1).trim();
        Node value;
        if (!rest.isEmpty()) {
            if (rest.startsWith("|") || rest.startsWith(">")) {
                throw new SyntaxException(line.number(), "block scalars ('|' or '>') are not supported");
            }
            if (rest.startsWith("&") || rest.startsWith("*")) {
                throw new SyntaxException(line.number(), "anchors and aliases are not supported");
            }
            if (rest.startsWith("{")) {
                throw new SyntaxException(line.number(), "flow maps ('{ ... }') are not supported");
            }
            value = rest.startsWith("[") ? parseFlowList(rest, line.number()) : scalar(rest, line.number());
        } else if (position < lines.size() && lines.get(position).indent() > indent) {
            Line next = lines.get(position);
            value = isListItem(next.text()) ? parseList(next.indent()) : parseMap(next.indent());
        } else if (position < lines.size() && lines.get(position).indent() == indent
                && isListItem(lines.get(position).text())) {
            value = parseList(indent);
        } else {
            value = new ScalarNode("", false, line.number()); // "key:" with nothing after it
        }
        entries.put(key, value);
        keyLines.put(key, line.number());
    }

    /** The {@code :} ending the key -- outside quotes and followed by a space or end of line. */
    private static int keyColon(String text, int number) throws SyntaxException {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') {
                break; // a value never starts before the key's colon; quoted keys are rejected by the caller
            }
            if (c == ':' && (i + 1 == text.length() || text.charAt(i + 1) == ' ')) {
                return i;
            }
        }
        throw new SyntaxException(number, "expected 'key: value'");
    }

    private ListNode parseList(int indent) throws SyntaxException {
        List<Node> items = new ArrayList<>();
        int listLine = lines.get(position).number();
        while (position < lines.size()) {
            Line line = lines.get(position);
            if (line.indent() < indent) {
                break;
            }
            if (line.indent() > indent) {
                throw new SyntaxException(line.number(), "unexpected indentation");
            }
            if (!isListItem(line.text())) {
                break; // a sibling key of the map that owns this list
            }
            position++;
            String rest = line.text().equals("-") ? "" : line.text().substring(2);
            int itemIndent = indent + 2 + (rest.length() - rest.stripLeading().length());
            rest = rest.strip();
            if (rest.isEmpty()) {
                if (position < lines.size() && lines.get(position).indent() > indent) {
                    Line next = lines.get(position);
                    items.add(isListItem(next.text()) ? parseList(next.indent()) : parseMap(next.indent()));
                } else {
                    items.add(new ScalarNode("", false, line.number()));
                }
            } else if (looksLikeMapEntry(rest)) {
                // "- key: value": the item is a map whose first entry is on this line and whose
                // remaining entries line up under that first key.
                Map<String, Node> entries = new LinkedHashMap<>();
                Map<String, Integer> keyLines = new LinkedHashMap<>();
                parseMapEntry(line, rest, itemIndent, entries, keyLines);
                MapNode continuation = position < lines.size() && lines.get(position).indent() == itemIndent
                        && !isListItem(lines.get(position).text())
                        ? parseMap(itemIndent)
                        : null;
                if (continuation != null) {
                    for (Map.Entry<String, Node> entry : continuation.entries().entrySet()) {
                        if (entries.containsKey(entry.getKey())) {
                            throw new SyntaxException(continuation.keyLines().get(entry.getKey()),
                                    "'" + entry.getKey() + "' appears twice");
                        }
                        entries.put(entry.getKey(), entry.getValue());
                        keyLines.put(entry.getKey(), continuation.keyLines().get(entry.getKey()));
                    }
                }
                items.add(new MapNode(entries, keyLines, line.number()));
            } else if (rest.startsWith("[")) {
                items.add(parseFlowList(rest, line.number()));
            } else {
                items.add(scalar(rest, line.number()));
            }
        }
        return new ListNode(items, listLine);
    }

    private static boolean looksLikeMapEntry(String text) {
        if (text.startsWith("\"") || text.startsWith("'") || text.startsWith("[")) {
            return false;
        }
        int colon = text.indexOf(':');
        return colon > 0 && (colon + 1 == text.length() || text.charAt(colon + 1) == ' ');
    }

    private static ListNode parseFlowList(String text, int number) throws SyntaxException {
        if (!text.endsWith("]")) {
            throw new SyntaxException(number, "a '[' list must end with ']' on the same line");
        }
        String inner = text.substring(1, text.length() - 1).trim();
        List<Node> items = new ArrayList<>();
        if (inner.isEmpty()) {
            return new ListNode(items, number);
        }
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if (quote != 0) {
                current.append(c);
                if (c == '\\' && quote == '"' && i + 1 < inner.length()) {
                    current.append(inner.charAt(++i));
                } else if (c == quote) {
                    quote = 0;
                }
            } else if (c == '"' || c == '\'') {
                quote = c;
                current.append(c);
            } else if (c == '[' || c == ']' || c == '{' || c == '}') {
                throw new SyntaxException(number, "nested lists and maps are not supported inside '[ ... ]'");
            } else if (c == ',') {
                items.add(flowItem(current.toString(), number));
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        if (quote != 0) {
            throw new SyntaxException(number, "unterminated quoted string");
        }
        items.add(flowItem(current.toString(), number));
        return new ListNode(items, number);
    }

    private static ScalarNode flowItem(String raw, int number) throws SyntaxException {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            throw new SyntaxException(number, "empty item in '[ ... ]' list");
        }
        return scalar(trimmed, number);
    }

    private static ScalarNode scalar(String text, int number) throws SyntaxException {
        if (text.startsWith("\"")) {
            return new ScalarNode(unquoteDouble(text, number), true, number);
        }
        if (text.startsWith("'")) {
            if (text.length() < 2 || !text.endsWith("'")) {
                throw new SyntaxException(number, "unterminated quoted string");
            }
            String body = text.substring(1, text.length() - 1);
            if (body.replace("''", "").contains("'")) {
                throw new SyntaxException(number, "unexpected text after a quoted string");
            }
            return new ScalarNode(body.replace("''", "'"), true, number);
        }
        return new ScalarNode(text, false, number);
    }

    private static String unquoteDouble(String text, int number) throws SyntaxException {
        StringBuilder out = new StringBuilder();
        for (int i = 1; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                if (i != text.length() - 1) {
                    throw new SyntaxException(number, "unexpected text after a quoted string");
                }
                return out.toString();
            }
            if (c == '\\') {
                if (i + 1 >= text.length()) {
                    break;
                }
                char escaped = text.charAt(++i);
                switch (escaped) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case 'n' -> out.append('\n');
                    case 't' -> out.append('\t');
                    default -> throw new SyntaxException(number, "unsupported escape '\\" + escaped + "'");
                }
            } else {
                out.append(c);
            }
        }
        throw new SyntaxException(number, "unterminated quoted string");
    }
}
