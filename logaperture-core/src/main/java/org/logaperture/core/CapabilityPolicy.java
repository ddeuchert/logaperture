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
 * Whether a {@link Capability} is granted. Full policy layering
 * (doc/logaperture-spec.md §9.2: agent build / vendor / customer / runtime
 * grant, intersected) is out of scope for this slice — {@link #allowAll()}
 * is the production default (doc/specs/level-control.md: "all three
 * granted, local-only... this slice exists to prove the check exists at
 * the right seam, not to ship a hardened default").
 */
@FunctionalInterface
public interface CapabilityPolicy {

    boolean isGranted(Capability capability);

    static CapabilityPolicy allowAll() {
        return capability -> true;
    }

    static CapabilityPolicy denyAll() {
        return capability -> false;
    }
}
