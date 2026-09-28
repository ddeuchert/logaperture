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

import org.logaperture.api.HandlerRef;
import org.logaperture.api.RuleExpression;
import org.logaperture.control.jmx.EnvironmentReportData;
import org.logaperture.control.jmx.HandlerInfoData;
import org.logaperture.control.jmx.LevelControlMXBean;
import org.logaperture.control.jmx.LoggerInfoData;
import org.logaperture.control.jmx.RuleData;

import java.io.InputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code logctl reset} with nothing named, on a terminal (doc/specs/guided-commands.md #11–#15): lists
 * everything currently changed -- or, for {@code reset logger|handler|rule}, that kind only -- and asks
 * which to put back; asks once about sticky items and, with a vendor defaults file, whether to go back
 * to the vendor defaults or the native configuration; then prints one command per item and confirms.
 */
final class GuidedResetCommand implements Command {

    static final String LOGGER = "logger";
    static final String HANDLER = "handler";
    static final String RULE = "rule";
    private static final String DEFAULT_HANDLER = "default-handler";

    private static final String INDENT = "  ";

    private final String kind;
    private final boolean includeSticky;
    private final boolean toNative;

    /**
     * @param kind          {@link #LOGGER}, {@link #HANDLER}, {@link #RULE}, or {@code null} for everything
     * @param includeSticky {@code --include-sticky} was given: sticky items are reset without asking
     * @param toNative      {@code --to-native} was given: items with a vendor baseline go to native without asking
     */
    GuidedResetCommand(String kind, boolean includeSticky, boolean toNative) {
        this.kind = kind;
        this.includeSticky = includeSticky;
        this.toNative = toNative;
    }

    /**
     * One thing that can be reset.
     *
     * @param cells         what the list shows after its number
     * @param vendorBased   it has a vendor baseline, so {@code --to-native} means something for it (#14)
     * @param vendorAsIs    an unaltered vendor rule: resetting it to the vendor defaults changes nothing (#15)
     */
    private record Item(String kind, String name, List<String> cells, boolean sticky, boolean vendorBased,
            boolean vendorAsIs) {
    }

    @Override
    public int run(LevelControlMXBean mbean, PrintStream out, InputStream in, boolean interactive) {
        return run(mbean, out, out, in, interactive);
    }

    @Override
    public int run(LevelControlMXBean mbean, PrintStream out, PrintStream err, InputStream in, boolean interactive) {
        Prompter prompter = new Prompter(in, out);
        try {
            return reset(mbean, prompter, err, in, interactive);
        } catch (Prompter.Cancelled cancelled) {
            out.println("Not applied.");
            return CliError.OK;
        }
    }

    private int reset(LevelControlMXBean mbean, Prompter prompter, PrintStream err, InputStream in,
            boolean interactive) {
        PrintStream out = prompter.out();
        Map<String, List<Item>> groups = inventory(mbean);
        List<Item> items = new ArrayList<>();
        groups.values().forEach(items::addAll);
        if (items.isEmpty()) {
            out.println(nothingChanged());
            return CliError.OK;
        }

        out.println("Currently changed:");
        int width = Integer.toString(items.size()).length();
        int number = 0;
        for (Map.Entry<String, List<Item>> group : groups.entrySet()) {
            if (group.getValue().isEmpty()) {
                continue;
            }
            out.println(INDENT + group.getKey());
            for (String row : alignedRows(group.getValue())) {
                out.println(INDENT + INDENT + String.format("%" + width + "d", ++number) + "  " + row);
            }
        }
        List<Integer> chosen = Picker.askSelection(prompter, items.size());
        if (chosen == null) {
            throw new Prompter.Cancelled();
        }
        List<Item> picked = new ArrayList<>();
        for (int index : chosen) {
            picked.add(items.get(index - 1));
        }

        boolean resetSticky = includeSticky || askAboutSticky(prompter, picked);
        if (!resetSticky) {
            int before = picked.size();
            picked.removeIf(Item::sticky);
            int left = before - picked.size();
            if (left > 0) {
                out.println("Leaving " + left + (left == 1 ? " sticky item as it is." : " sticky items as they are."));
            }
            if (picked.isEmpty()) {
                throw new Prompter.Cancelled();
            }
        }

        boolean goNative = toNative || askVendorOrNative(mbean, prompter, picked);
        if (!goNative) {
            List<Item> asIs = picked.stream().filter(Item::vendorAsIs).toList();
            for (Item item : asIs) {
                out.println("rule " + item.name() + " is already the vendor's definition.");
            }
            picked.removeAll(asIs);
            if (picked.isEmpty()) {
                out.println("Nothing to reset.");
                return CliError.OK;
            }
        }

        // Rules first: resetting a logger also removes the rules attached to it, so a picked rule's own
        // line would otherwise report nothing to reset.
        picked.sort(java.util.Comparator.comparing(item -> !item.kind().equals(RULE)));

        out.println();
        out.println(picked.size() == 1 ? "This is the command:" : "These are the commands:");
        List<Command> commands = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (Item item : picked) {
            boolean sticky = resetSticky && item.sticky();
            boolean nativeHere = goNative && item.vendorBased();
            out.println(INDENT + commandLine(item, sticky, nativeHere));
            commands.add(command(item, sticky, nativeHere));
            names.add(item.name());
        }
        if (!prompter.askYesNo("", "Apply?", true)) {
            throw new Prompter.Cancelled();
        }
        return Commands.runEach(mbean, out, err, in, interactive, names, commands);
    }

    private String nothingChanged() {
        if (kind == null) {
            return "Nothing is changed; nothing to reset.";
        }
        return switch (kind) {
            case LOGGER -> "No logger is overridden; nothing to reset.";
            case HANDLER -> "No handler is overridden; nothing to reset.";
            default -> "No rules are attached; nothing to reset.";
        };
    }

    /** #13: one question for every sticky item picked; {@code false} when none is sticky. */
    private static boolean askAboutSticky(Prompter prompter, List<Item> picked) {
        long sticky = picked.stream().filter(Item::sticky).count();
        if (sticky == 0) {
            return false;
        }
        String question = picked.size() == 1 ? "This is sticky -- it is kept across restarts. Reset it anyway?"
                : sticky == 1 ? "1 of these is sticky -- it is kept across restarts. Reset it too?"
                : sticky + " of these are sticky -- they are kept across restarts. Reset them too?";
        return prompter.askYesNo("", question, false);
    }

    /** #14: asked only with a vendor defaults file and a picked item that has a vendor baseline. */
    private static boolean askVendorOrNative(LevelControlMXBean mbean, Prompter prompter, List<Item> picked) {
        if (picked.stream().noneMatch(Item::vendorBased)) {
            return false;
        }
        EnvironmentReportData report = mbean.environmentReport();
        if (report == null || report.getVendorDefaultsPath() == null) {
            return false;
        }
        while (true) {
            String answer = prompter.ask("", "Go back to the vendor defaults, or to the application's own "
                    + "configuration until restart? [vendor/native] (Enter: vendor)").toLowerCase(java.util.Locale.ROOT);
            switch (answer) {
                case "", "vendor", "v" -> {
                    return false;
                }
                case "native", "n" -> {
                    return true;
                }
                default -> prompter.out().println("Answer vendor or native.");
            }
        }
    }

    // --- what is changed (#11, #12, #15) --------------------------------------------------------

    private Map<String, List<Item>> inventory(LevelControlMXBean mbean) {
        Map<String, List<Item>> groups = new LinkedHashMap<>();
        if (kind == null || kind.equals(LOGGER)) {
            groups.put("Loggers", loggers(mbean));
        }
        List<HandlerInfoData> handlers = kind == null || kind.equals(HANDLER) ? handlerCatalog(mbean) : List.of();
        if (kind == null || kind.equals(HANDLER)) {
            groups.put("Handlers", handlers(handlers));
        }
        if (kind == null || kind.equals(RULE)) {
            groups.put("Rules", rules(mbean));
        }
        if (kind == null) {
            groups.put("Default handlers", defaultHandlers(handlers));
        }
        return groups;
    }

    private static List<Item> loggers(LevelControlMXBean mbean) {
        Map<String, LoggerInfoData> byName = new java.util.TreeMap<>();
        for (LoggerInfoData row : mbean.listLoggers(null)) {
            if (row.isOverrideActive() || row.isResetToNative()) {
                byName.putIfAbsent(row.getName(), row);
            }
        }
        List<Item> items = new ArrayList<>();
        for (LoggerInfoData row : byName.values()) {
            List<String> cells = row.isOverrideActive()
                    ? List.of(row.getName(), orDash(row.getEffectiveLevel()), lifetime(row.getTier(), row.getExpiresAt()))
                    : List.of(row.getName(), orDash(row.getEffectiveLevel()), "native default, until restart");
            items.add(new Item(LOGGER, row.getName(), cells, "STICKY".equals(row.getTier()),
                    row.getVendorDefaultLevel() != null, false));
        }
        return items;
    }

    private static List<HandlerInfoData> handlerCatalog(LevelControlMXBean mbean) {
        Map<String, HandlerInfoData> byRef = new LinkedHashMap<>();
        for (HandlerInfoData row : mbean.listHandlers()) {
            byRef.putIfAbsent(row.getRef(), row);
        }
        return new ArrayList<>(byRef.values());
    }

    private static List<Item> handlers(List<HandlerInfoData> catalog) {
        List<Item> items = new ArrayList<>();
        for (HandlerInfoData row : catalog) {
            if (!row.isOverrideActive() && !row.isResetToNative()) {
                continue;
            }
            String level = "AUTO".equals(row.getOverrideMode()) ? "AUTO" : orDash(row.getOverrideLevel());
            List<String> cells = row.isOverrideActive()
                    ? List.of(row.getRef(), level, lifetime(row.getOverrideTier(), row.getOverrideExpiresAt()))
                    : List.of(row.getRef(), orDash(row.getLevel()), "native level, until restart");
            items.add(new Item(HANDLER, row.getRef(), cells, "STICKY".equals(row.getOverrideTier()),
                    row.getVendorDefault() != null, false));
        }
        return items;
    }

    /** #15: every rule, a vendor rule too even when unaltered -- resetting one to native switches it off. */
    private static List<Item> rules(LevelControlMXBean mbean) {
        List<Item> items = new ArrayList<>();
        for (RuleData row : mbean.listRules()) {
            boolean vendor = row.getOrigin() != null;
            String lifetime = !vendor ? lifetime(row.getTier(), row.getExpiresAt())
                    : row.isToNative() ? "vendor, off until restart"
                    : row.isAltered() ? "vendor, altered, " + lifetime(row.getTier(), row.getExpiresAt())
                    : "vendor";
            boolean sticky = "STICKY".equals(row.getTier()) && (!vendor || row.isAltered());
            items.add(new Item(RULE, row.getId(), List.of(row.getId(), row.getAction() + " " + row.getLoggerName(),
                    lifetime), sticky, vendor, vendor && !row.isAltered() && !row.isToNative()));
        }
        return items;
    }

    /**
     * An explicitly assigned {@code DEFAULT_HANDLERS} membership: its summary is the plain names, where the
     * automatic pick and the vendor defaults' list read {@code (auto: …)} / {@code (vendor: …)}.
     */
    private static List<Item> defaultHandlers(List<HandlerInfoData> catalog) {
        for (HandlerInfoData row : catalog) {
            if (row.getRef().equals(HandlerRef.DEFAULT_HANDLERS.value()) && row.getMembersSummary() != null
                    && !row.getMembersSummary().startsWith("(")) {
                return List.of(new Item(DEFAULT_HANDLER, HandlerRef.DEFAULT_HANDLERS.value(),
                        List.of(row.getMembersSummary(), "kept across restarts"), false, false, false));
            }
        }
        return List.of();
    }

    private static String lifetime(String tier, String expiresAt) {
        if (tier == null) {
            return "";
        }
        return switch (tier) {
            case "SESSION" -> "session";
            case "STICKY" -> "sticky";
            case "FOR" -> expiresAt == null ? "for" : "reverts " + Format.relative(expiresAt);
            default -> tier.toLowerCase(java.util.Locale.ROOT);
        };
    }

    private static String orDash(String value) {
        return value == null ? Format.NONE : value;
    }

    private static List<String> alignedRows(List<Item> items) {
        int columns = items.get(0).cells().size();
        int[] widths = new int[columns];
        for (Item item : items) {
            for (int c = 0; c < columns; c++) {
                widths[c] = Math.max(widths[c], item.cells().get(c).length());
            }
        }
        List<String> rows = new ArrayList<>();
        for (Item item : items) {
            StringBuilder row = new StringBuilder();
            for (int c = 0; c < columns; c++) {
                String cell = item.cells().get(c);
                row.append(c == columns - 1 ? cell : String.format("%-" + widths[c] + "s  ", cell));
            }
            rows.add(row.toString().stripTrailing());
        }
        return rows;
    }

    // --- the commands (#1, #11) -----------------------------------------------------------------

    /** One line per item, never a bulk form: {@code reset loggers} would also reach whatever changes later. */
    static String commandLine(String kind, String name, boolean includeSticky, boolean toNative) {
        StringBuilder line = new StringBuilder("logctl reset ").append(kind);
        if (!kind.equals(DEFAULT_HANDLER)) {
            line.append(' ').append(RuleExpression.quote(name));
        }
        if (includeSticky) {
            line.append(" --include-sticky");
        }
        if (toNative) {
            line.append(" --to-native");
        }
        return line.toString();
    }

    private static String commandLine(Item item, boolean includeSticky, boolean toNative) {
        return commandLine(item.kind(), item.name(), includeSticky, toNative);
    }

    private static Command command(Item item, boolean includeSticky, boolean toNative) {
        return switch (item.kind()) {
            case LOGGER -> Commands.resetLogger(item.name(), includeSticky, toNative, false);
            case HANDLER -> Commands.resetHandler(item.name(), includeSticky, toNative, false);
            case RULE -> Commands.resetRule(item.name(), includeSticky, toNative, false);
            default -> Commands.resetDefaultHandler(toNative, false);
        };
    }

}
