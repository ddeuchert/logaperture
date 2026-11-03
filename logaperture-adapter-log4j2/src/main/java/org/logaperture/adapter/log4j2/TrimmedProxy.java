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
package org.logaperture.adapter.log4j2;

import org.apache.logging.log4j.core.impl.ThrowableProxy;
import org.apache.logging.log4j.core.pattern.TextRenderer;

import java.util.List;

/**
 * The {@code ThrowableProxy} of a trimmed event (doc/specs/log4j2-adapter.md A4, spike "Trim rendering
 * across versions"). From 2.20.0 to 2.24.x, {@code %xEx} (and Spring Boot's {@code %xwEx}) renders from
 * the event's proxy, and a proxy built from the synthetic throwable names the synthetic class. This one
 * reads the synthetic throwable's own {@code toString()} (the original's, plus the marker) as its name, at
 * every level of the cause chain, which is what {@code %ex} prints from the throwable itself.
 *
 * <p>From 2.25.4 the converters ignore it and print correctly anyway; from 2.25.0 to 2.25.3 they print
 * the synthetic class whatever the proxy says, which {@link TrimProbe} detects and turns trim off for.
 */
final class TrimmedProxy extends ThrowableProxy {

    private static final String NULL_MESSAGE_SUFFIX = ": null";

    private final Throwable trimmed;
    private TrimmedProxy cause;
    private boolean causeBuilt;

    TrimmedProxy(Throwable trimmed) {
        super(trimmed);
        this.trimmed = trimmed;
    }

    @Override
    public String getName() {
        return trimmed.toString();
    }

    @Override
    public String getMessage() {
        return null; // the message is part of the name above
    }

    @Override
    public String getLocalizedMessage() {
        return null;
    }

    @Override
    public synchronized ThrowableProxy getCauseProxy() {
        if (!causeBuilt) {
            // Lazily, and once: only a converter that walks the proxy (%xEx) asks, so %ex never pays for it.
            Throwable next = trimmed.getCause();
            cause = next == null ? null : new TrimmedProxy(next);
            causeBuilt = true;
        }
        return cause;
    }

    /**
     * The top level is written by a path that appends {@code ": " + message} even for a null message on
     * 2.20 to 2.24 (a cause is not); take that tail off, right after the name. The name is the whole
     * trimmed text, which spans several lines when the exception's message does, so it is looked for as
     * the known text and not as "the first line".
     */
    @Override
    public void formatExtendedStackTraceTo(StringBuilder output, List<String> ignorePackages,
            TextRenderer textRenderer, String suffix, String lineSeparator) {
        StringBuilder rendered = new StringBuilder();
        super.formatExtendedStackTraceTo(rendered, ignorePackages, textRenderer, suffix, lineSeparator);
        String name = getName();
        if (rendered.indexOf(name + NULL_MESSAGE_SUFFIX) == 0) {
            rendered.delete(name.length(), name.length() + NULL_MESSAGE_SUFFIX.length());
        }
        output.append(rendered);
    }
}
