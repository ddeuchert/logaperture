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

        RuleService.RuleExport rules = context.ruleService().exportRules();
        VendorDefaultsExport export = new VendorDefaultsExport(header, context.service().exportLoggers(),
                context.handlerService().exportHandlers(), context.handlerService().exportDefaultHandlers(),
                rules.rules(), rules.comments());

        String text = VendorDefaultsFile.write(export);
        VendorDefaults check = VendorDefaultsFile.parse(text, Path.of("exported vendor defaults"), false);
        if (check.status() != VendorDefaults.Status.LOADED) {
            throw new IllegalStateException("the exported vendor defaults file would not load -- this is a "
                    + "LogAperture bug, please report it: " + String.join("; ", check.errors()));
        }
        return text;
    }

    private static String startedFromText(VendorDefaults file) {
        return switch (file.status()) {
            case NOT_CONFIGURED -> "no vendor defaults file";
            case LOADED -> file.path().orElseThrow().toString();
            case REJECTED -> file.path().orElseThrow() + " (rejected at startup, so none of it is included)";
        };
    }
}
