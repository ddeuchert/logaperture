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

/**
 * The result of {@code logctl alter rule <id>} -- doc/specs/alter-rule.md "Command surface": the
 * rule before and after, both tagged with the context they came from.
 *
 * @param changed {@code false} for an alter that changed nothing (A4): {@code before} and {@code
 *                after} are then the same row, and nothing was audited or written
 */
public record RuleAlteration(RuleView before, RuleView after, boolean changed) {

    /** Both rows stamped with their owning context's stable key. */
    public RuleAlteration withContext(String context) {
        return new RuleAlteration(before.withContext(context), after.withContext(context), changed);
    }
}
