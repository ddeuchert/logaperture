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

import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the text of {@code logctl export vendor-defaults} from one context's live state --
 * doc/specs/vendor-defaults-export.md. Each service contributes its own section (it owns the
 * overrides and baselines involved); this class adds the header, renders the file with {@link
 * VendorDefaultsFile#write}, and parses the result back with the same validator the agent uses at
 * startup, failing closed (X1: a vendor must never be handed a file that won't load).
 */
final class VendorDefaultsExporter {

    private VendorDefaultsExporter() {
    }

    static String export(AggregateLevelControl.ContextControl context, VendorDefaults startedFrom,
            String agentVersion, Instant now) {
        List<String> header = List.of(
                "Exported by logctl export vendor-defaults, " + now.truncatedTo(ChronoUnit.SECONDS)
                        + ", agent " + agentVersion,
                "Started from: " + startedFromText(startedFrom));

        List<String> skipped = new ArrayList<>();
        List<VendorDefaults.LoggerDefault> loggers = new ArrayList<>();
        for (VendorDefaults.LoggerDefault logger : context.service().exportLoggers()) {
            VendorDefaults.LoggerDefault tidy = new VendorDefaults.LoggerDefault(logger.name(), logger.level(),
                    tidyReason(logger.reason()));
            keepIfWritable(tidy, List.of(tidy), List.of(), List.of(), "logger '" + logger.name() + "'", loggers,
                    skipped);
        }
        List<VendorDefaults.HandlerDefault> handlers = new ArrayList<>();
        for (VendorDefaults.HandlerDefault handler : context.handlerService().exportHandlers()) {
            VendorDefaults.HandlerDefault tidy = new VendorDefaults.HandlerDefault(handler.ref(), handler.level(),
                    handler.mode(), tidyReason(handler.reason()));
            keepIfWritable(tidy, List.of(), List.of(tidy), List.of(), "handler '" + handler.ref().value() + "'",
                    handlers, skipped);
        }
        RuleService.RuleExport exportedRules = context.ruleService().exportRules();
        List<VendorDefaults.RuleDefault> rules = new ArrayList<>();
        for (VendorDefaults.RuleDefault rule : exportedRules.rules()) {
            VendorDefaults.RuleDefault tidy = new VendorDefaults.RuleDefault(rule.id(), rule.action(),
                    rule.loggerName(), rule.matchers(), tidyReason(rule.reason()), rule.sampleFull(), rule.frames(),
                    rule.collapseCauses());
            String was = exportedRules.comments().get(rule.id());
            String what = rule.action() + " rule " + (was != null ? was.substring("was ".length())
                    : rule.id()) + " on '" + rule.loggerName() + "'";
            keepIfWritable(tidy, List.of(), List.of(), List.of(tidy), what, rules, skipped);
        }
        VendorDefaultsExport export = new VendorDefaultsExport(header, loggers, handlers,
                context.handlerService().exportDefaultHandlers(), rules, exportedRules.comments(), skipped);

        String text = VendorDefaultsFile.write(export);
        VendorDefaults check = VendorDefaultsFile.parse(text, Path.of("exported vendor defaults"), false);
        if (check.status() != VendorDefaults.Status.LOADED) {
            throw new IllegalStateException("the exported vendor defaults file would not load -- this is a "
                    + "LogAperture bug, please report it: " + String.join("; ", check.errors()));
        }
        return text;
    }

    /**
     * Adds {@code entry} to {@code kept} if a file holding just it loads; otherwise records a
     * "Not exported" comment naming it and why. Some live settings can't be written as a file
     * entry -- a rule on the root logger (the file has no name for it), an empty matcher, a logger
     * name with whitespace -- and one of them must not make the whole export fail.
     */
    private static <T> void keepIfWritable(T entry, List<VendorDefaults.LoggerDefault> asLoggers,
            List<VendorDefaults.HandlerDefault> asHandlers, List<VendorDefaults.RuleDefault> asRules, String what,
            List<T> kept, List<String> skipped) {
        VendorDefaultsExport alone = new VendorDefaultsExport(List.of(), asLoggers, asHandlers, null, asRules,
                java.util.Map.of(), List.of());
        VendorDefaults check = VendorDefaultsFile.parse(VendorDefaultsFile.write(alone), Path.of("entry"), false);
        if (check.status() == VendorDefaults.Status.LOADED) {
            kept.add(entry);
        } else {
            String why = check.errors().get(0).replaceFirst("^line \\d+: ", "");
            skipped.add("Not exported: " + what + " -- the file can't hold it (" + why.replace('\n', ' ') + ")");
        }
    }

    /** A reason is free text: blank means none, and a carriage return (which the file can't hold) becomes a newline. */
    private static String tidyReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.replace("\r\n", "\n").replace('\r', '\n');
    }

    private static String startedFromText(VendorDefaults file) {
        return switch (file.status()) {
            case NOT_CONFIGURED -> "no vendor defaults file";
            case LOADED -> file.path().orElseThrow().toString();
            case REJECTED -> file.path().orElseThrow() + " (rejected at startup, so none of it is included)";
        };
    }
}
