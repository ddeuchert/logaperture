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
 * The §9.5 suppression floor — categories no rule may attach to — checked
 * at {@link RuleService#attach} time. §9.4's sealed-at-boot policy file
 * that would populate a real list doesn't exist anywhere in this codebase
 * yet (no capability here is audited on denial either — {@link
 * CapabilityDeniedException} simply throws, matching this precedent); this
 * interface exists so the check runs at the right seam, per doc/specs/
 * level-control.md's own precedent for {@link CapabilityPolicy#allowAll()}
 * — proving the check exists, not shipping a hardened default.
 */
@FunctionalInterface
public interface ProtectedCategories {

    boolean isProtected(String loggerName);

    /** The production default until a real policy source exists — nothing is protected. */
    static ProtectedCategories none() {
        return loggerName -> false;
    }
}
