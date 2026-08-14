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
import java.util.Collection;
import java.util.List;

/**
 * Dot-boundary descendant resolution for {@code includeChildren} fan-out
 * (doc/specs/level-control.md: "a level on {@code com.acme} affects {@code
 * com.acme.http} unless that logger has its own explicit level"). Pure
 * string logic over already-known names — the adapter never needs to know
 * about hierarchy at all.
 */
final class LoggerHierarchy {

    private LoggerHierarchy() {
    }

    /** Every name in {@code allNames} that is a strict dot-boundary descendant of {@code parent}. */
    static List<String> descendantsOf(String parent, Collection<String> allNames) {
        String dotPrefix = parent + ".";
        List<String> descendants = new ArrayList<>();
        for (String name : allNames) {
            if (name.startsWith(dotPrefix)) {
                descendants.add(name);
            }
        }
        return descendants;
    }
}
