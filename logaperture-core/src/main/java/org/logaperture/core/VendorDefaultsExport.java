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

import org.logaperture.api.HandlerRef;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The settings {@code logctl export vendor-defaults} writes -- doc/specs/vendor-defaults-export.md
 * "What goes into the export": the vendor-file layer with the sticky changes folded in, before
 * it is rendered by {@link VendorDefaultsFile#write}.
 *
 * @param headerComments   comment lines (without the leading {@code #}) naming the tool, time,
 *                         agent version and the file the export started from
 * @param loggers          sorted by name
 * @param handlers         sorted by name
 * @param defaultHandlers  {@code null} to leave {@code defaultHandlers} out of the file
 * @param rules            vendor rules in file order, then operator rules in id order; each id
 *                         is the full {@code vendor:<name>}
 * @param ruleComments     per rule id, a comment written above its entry (e.g. {@code "was r7"})
 * @param skippedComments  one comment line per setting left out because the file can't hold it
 *                         (e.g. a rule on the root logger), written after the header
 */
public record VendorDefaultsExport(List<String> headerComments, List<VendorDefaults.LoggerDefault> loggers,
        List<VendorDefaults.HandlerDefault> handlers, List<HandlerRef> defaultHandlers,
        List<VendorDefaults.RuleDefault> rules, Map<String, String> ruleComments, List<String> skippedComments) {

    public VendorDefaultsExport {
        headerComments = List.copyOf(headerComments);
        loggers = List.copyOf(loggers);
        handlers = List.copyOf(handlers);
        defaultHandlers = defaultHandlers == null ? null : List.copyOf(defaultHandlers);
        rules = List.copyOf(rules);
        ruleComments = Map.copyOf(Objects.requireNonNull(ruleComments, "ruleComments"));
        skippedComments = List.copyOf(skippedComments);
    }

    /** {@code true} if the file carries no settings at all (doc/specs/vendor-defaults-export.md X9). */
    public boolean isEmpty() {
        return loggers.isEmpty() && handlers.isEmpty() && defaultHandlers == null && rules.isEmpty();
    }
}
