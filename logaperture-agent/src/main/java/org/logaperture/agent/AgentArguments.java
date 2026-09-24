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
package org.logaperture.agent;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The {@code -javaagent:logaperture-agent.jar=<args>} string -- doc/specs/vendor-defaults.md
 * "Agent arguments". Zero or more kebab-case {@code --name=value} options separated by commas;
 * a comma separates options only when it is followed by {@code --} (Decision M8), so a path
 * containing a plain comma needs no quoting.
 *
 * <p>Never throws: an unknown, repeated, or malformed option becomes a warning and is skipped
 * (fail-open, logaperture-spec.md §9).
 *
 * @param vendorDefaults the {@code --vendor-defaults} path, resolved to absolute against the
 *                       working directory
 * @param warnings       one line per problem, for the caller to report
 */
record AgentArguments(Optional<Path> vendorDefaults, List<String> warnings) {

    static final String VENDOR_DEFAULTS = "--vendor-defaults";

    AgentArguments {
        warnings = List.copyOf(warnings);
    }

    static AgentArguments parse(String args, Path workingDirectory) {
        List<String> warnings = new ArrayList<>();
        Path vendorDefaults = null;
        if (args == null || args.isBlank()) {
            return new AgentArguments(Optional.empty(), warnings);
        }
        for (String option : args.split(",(?=--)")) {
            String trimmed = option.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int equals = trimmed.indexOf('=');
            if (!trimmed.startsWith("--") || equals < 0) {
                warnings.add("ignoring agent argument '" + trimmed + "' -- expected --name=value");
                continue;
            }
            String name = trimmed.substring(0, equals);
            String value = trimmed.substring(equals + 1).strip();
            if (!name.equals(VENDOR_DEFAULTS)) {
                warnings.add("ignoring unknown agent argument '" + name + "'");
                continue;
            }
            if (value.isEmpty()) {
                warnings.add("ignoring " + VENDOR_DEFAULTS + " -- it needs a file path");
                continue;
            }
            if (vendorDefaults != null) {
                warnings.add(VENDOR_DEFAULTS + " is given more than once -- using the first, " + vendorDefaults);
                continue;
            }
            try {
                vendorDefaults = workingDirectory.resolve(value).toAbsolutePath().normalize();
            } catch (InvalidPathException e) {
                warnings.add("ignoring " + VENDOR_DEFAULTS + " -- '" + value + "' is not a valid path");
            }
        }
        return new AgentArguments(Optional.ofNullable(vendorDefaults), warnings);
    }
}
