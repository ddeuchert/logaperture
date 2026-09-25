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
 * {@code logctl export vendor-defaults}'s contract -- doc/specs/vendor-defaults-export.md.
 */
public interface VendorDefaultsExportOperations {

    /**
     * The vendor defaults file this JVM was started with, with every {@code sticky} change folded
     * in, as the text of a vendor defaults file. Read-only; requires {@link Capability#VIEW}. The
     * text has already been checked to load: a failure there is a bug, and throws rather than
     * returning a file that wouldn't load.
     *
     * @throws IllegalStateException if no context is registered, or the generated file doesn't load
     */
    String exportVendorDefaults();
}
