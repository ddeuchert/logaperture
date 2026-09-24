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

import org.logaperture.api.CompiledMatchers;
import org.logaperture.api.HandlerLevelMode;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.SampleFullPolicy;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The vendor defaults file, parsed and validated once per JVM — doc/specs/vendor-defaults.md
 * "The baseline layer". Immutable; every logging context applies the same instance.
 *
 * <p>A {@link Status#REJECTED rejected} file carries its errors and <em>no</em> entries, so
 * every consumer can treat "rejected" and "not configured" identically when applying: all or
 * nothing (vendor-config-epic.md Decision #8).
 */
public final class VendorDefaults {

    /** Audit source for every entry this file applies (doc/specs/vendor-defaults.md "Audit"). */
    public static final String AUDIT_SOURCE = "vendor-defaults";

    /** Prefix every vendor rule id carries, e.g. {@code vendor:healthcheck-noise}. */
    public static final String RULE_ID_PREFIX = "vendor:";

    public enum Status {
        /** No {@code --vendor-defaults=} argument was given. */
        NOT_CONFIGURED,
        LOADED,
        /** Named, but missing, unreadable, or invalid -- nothing from it applies. */
        REJECTED
    }

    /** One {@code handlers:} entry; {@code level} is {@code null} iff {@code mode} is {@link HandlerLevelMode#AUTO}. */
    public record HandlerDefault(HandlerRef ref, Level level, HandlerLevelMode mode, String reason) {
        public HandlerDefault {
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(mode, "mode");
            if ((mode == HandlerLevelMode.AUTO) != (level == null)) {
                throw new IllegalArgumentException("level must be null iff mode is AUTO");
            }
        }
    }

    /** One {@code loggers:} entry. */
    public record LoggerDefault(String name, Level level, String reason) {
        public LoggerDefault {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(level, "level");
        }
    }

    /**
     * One {@code rules:} entry, already validated. {@code id} is the full id ({@code
     * vendor:<name>}). Action-specific fields not used by {@code action} hold their defaults.
     */
    public record RuleDefault(String id, String action, String loggerName, CompiledMatchers matchers,
            String reason, SampleFullPolicy sampleFull, int frames, boolean collapseCauses) {
        public RuleDefault {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(loggerName, "loggerName");
            Objects.requireNonNull(matchers, "matchers");
            Objects.requireNonNull(sampleFull, "sampleFull");
        }
    }

    private static final VendorDefaults NONE =
            new VendorDefaults(Status.NOT_CONFIGURED, null, List.of(), false, List.of(), List.of(), null, List.of());

    private final Status status;
    private final Path path;
    private final List<String> errors;
    private final boolean writable;
    private final Map<String, LoggerDefault> loggers;
    private final Map<HandlerRef, HandlerDefault> handlers;
    private final List<HandlerRef> defaultHandlers;
    private final List<RuleDefault> rules;

    private VendorDefaults(Status status, Path path, List<String> errors, boolean writable,
            List<LoggerDefault> loggers, List<HandlerDefault> handlers, List<HandlerRef> defaultHandlers,
            List<RuleDefault> rules) {
        this.status = status;
        this.path = path;
        this.errors = List.copyOf(errors);
        this.writable = writable;
        Map<String, LoggerDefault> loggerMap = new LinkedHashMap<>();
        for (LoggerDefault logger : loggers) {
            loggerMap.put(logger.name(), logger);
        }
        this.loggers = Collections.unmodifiableMap(loggerMap);
        Map<HandlerRef, HandlerDefault> handlerMap = new LinkedHashMap<>();
        for (HandlerDefault handler : handlers) {
            handlerMap.put(handler.ref(), handler);
        }
        this.handlers = Collections.unmodifiableMap(handlerMap);
        this.defaultHandlers = defaultHandlers == null ? null : List.copyOf(defaultHandlers);
        this.rules = List.copyOf(rules);
    }

    /** No vendor defaults file configured. */
    public static VendorDefaults none() {
        return NONE;
    }

    static VendorDefaults rejected(Path path, List<String> errors) {
        Objects.requireNonNull(path, "path");
        if (errors.isEmpty()) {
            throw new IllegalArgumentException("a rejected file needs at least one error");
        }
        return new VendorDefaults(Status.REJECTED, path, errors, false, List.of(), List.of(), null, List.of());
    }

    static VendorDefaults loaded(Path path, boolean writable, List<LoggerDefault> loggers,
            List<HandlerDefault> handlers, List<HandlerRef> defaultHandlers, List<RuleDefault> rules) {
        Objects.requireNonNull(path, "path");
        return new VendorDefaults(Status.LOADED, path, List.of(), writable, loggers, handlers, defaultHandlers,
                rules);
    }

    public Status status() {
        return status;
    }

    /** The file's resolved absolute path; empty only when {@link Status#NOT_CONFIGURED}. */
    public Optional<Path> path() {
        return Optional.ofNullable(path);
    }

    /** Every validation error, each prefixed with its line; empty unless {@link Status#REJECTED}. */
    public List<String> errors() {
        return errors;
    }

    /** The account the JVM runs as can write the file or its directory (doc/specs/vendor-defaults.md "Writable-file warning"). */
    public boolean writable() {
        return writable;
    }

    /** Logger defaults by exact name, in file order. */
    public Map<String, LoggerDefault> loggers() {
        return loggers;
    }

    /** Just the levels of {@link #loggers()}, for {@link BaselineRegistry}. */
    public Map<String, Level> loggerLevels() {
        Map<String, Level> levels = new LinkedHashMap<>();
        for (LoggerDefault logger : loggers.values()) {
            levels.put(logger.name(), logger.level());
        }
        return levels;
    }

    public Optional<Level> loggerLevel(String name) {
        LoggerDefault logger = loggers.get(name);
        return logger == null ? Optional.empty() : Optional.of(logger.level());
    }

    /** Handler defaults by name, in file order. */
    public Map<HandlerRef, HandlerDefault> handlers() {
        return handlers;
    }

    /** The file's {@code defaultHandlers} list; empty if the file doesn't set one. */
    public Optional<List<HandlerRef>> defaultHandlers() {
        return Optional.ofNullable(defaultHandlers);
    }

    public List<RuleDefault> rules() {
        return rules;
    }

    /** {@code true} if this file contributes nothing at all -- not configured, rejected, or an empty file. */
    public boolean isEmpty() {
        return loggers.isEmpty() && handlers.isEmpty() && defaultHandlers == null && rules.isEmpty();
    }

    /**
     * One line for {@code logctl status}/{@code env} (doc/specs/vendor-defaults.md "Surfaces"),
     * e.g. {@code "2 loggers, 2 handlers, default handlers, 2 rules"}; {@code null} when not
     * configured.
     */
    public String summary() {
        return switch (status) {
            case NOT_CONFIGURED -> null;
            case REJECTED -> "REJECTED (" + errors.size() + (errors.size() == 1 ? " error" : " errors")
                    + "), see logctl doctor";
            case LOADED -> {
                if (isEmpty()) {
                    yield "loaded, no settings";
                }
                List<String> parts = new ArrayList<>();
                if (!loggers.isEmpty()) {
                    parts.add(count(loggers.size(), "logger"));
                }
                if (!handlers.isEmpty()) {
                    parts.add(count(handlers.size(), "handler"));
                }
                if (defaultHandlers != null) {
                    parts.add("default handlers");
                }
                if (!rules.isEmpty()) {
                    parts.add(count(rules.size(), "rule"));
                }
                yield String.join(", ", parts);
            }
        };
    }

    private static String count(int n, String noun) {
        return n + " " + noun + (n == 1 ? "" : "s");
    }
}
