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

import java.util.List;
import java.util.Objects;

/**
 * One validated recipe -- doc/specs/recipes.md "The recipe file": a named, documented set of
 * settings for watching one thing. Its entries reuse the vendor defaults file's shapes; a rule's
 * {@link VendorDefaults.RuleDefault#id() id} is the entry's own label (or {@code rule-<n>} when
 * the entry has none), not a {@code vendor:} id.
 *
 * @param description multi-line text, or {@code null}
 */
public record Recipe(String namespace, String name, String summary, String description,
        List<VendorDefaults.LoggerDefault> loggers, List<VendorDefaults.HandlerDefault> handlers,
        List<VendorDefaults.RuleDefault> rules, RecipeSource source) {

    public Recipe {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(summary, "summary");
        loggers = List.copyOf(loggers);
        handlers = List.copyOf(handlers);
        rules = List.copyOf(rules);
        Objects.requireNonNull(source, "source");
    }

    /** {@code <namespace>:<name>}, e.g. {@code io.undertow:sessions} (epic #20). */
    public String id() {
        return namespace + ":" + name;
    }

    /**
     * Whether {@code other} says exactly the same thing, wherever it was read from -- two
     * deployments bundling the same library version list it once (epic #20).
     */
    public boolean sameContent(Recipe other) {
        return namespace.equals(other.namespace) && name.equals(other.name) && summary.equals(other.summary)
                && Objects.equals(description, other.description) && loggers.equals(other.loggers)
                && handlers.equals(other.handlers) && rules.equals(other.rules);
    }
}
