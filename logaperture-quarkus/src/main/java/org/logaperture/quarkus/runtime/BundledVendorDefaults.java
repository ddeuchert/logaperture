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
package org.logaperture.quarkus.runtime;

import org.logaperture.core.VendorDefaults;
import org.logaperture.core.VendorDefaultsFile;

import java.util.ArrayList;
import java.util.List;

/**
 * The vendor defaults file bundled in the application (doc/specs/bundled-vendor-defaults.md): where it
 * is, how the build checks it, and how its text travels from the build to the running application.
 * Called by the deployment module at build time; this class is in the runtime module because the
 * engine it validates with is shaded into this jar.
 */
public final class BundledVendorDefaults {

    /** #1: the path in the application's resources. */
    public static final String RESOURCE = VendorDefaultsFile.BUNDLED_RESOURCE;

    /**
     * The recorder carries the text as string constants, and one constant is limited to 64 KB of
     * modified UTF-8 (3 bytes a character at worst), so longer text is split.
     */
    private static final int CHUNK_CHARS = 16_000;

    private BundledVendorDefaults() {
    }

    /**
     * #3: every error in {@code content}, each with its line, as the build reports them; empty if the
     * file is valid.
     */
    public static List<String> errors(String content) {
        VendorDefaults parsed = VendorDefaultsFile.parseBundled(content, RESOURCE);
        return parsed.status() == VendorDefaults.Status.REJECTED ? parsed.errors() : List.of();
    }

    /** The text split into chunks the recorder can carry. */
    public static List<String> chunks(String content) {
        List<String> chunks = new ArrayList<>();
        for (int start = 0; start < content.length(); start += CHUNK_CHARS) {
            chunks.add(content.substring(start, Math.min(content.length(), start + CHUNK_CHARS)));
        }
        return chunks;
    }

    /** The text again, from {@link #chunks}; empty for no bundled file. */
    static String join(List<String> chunks) {
        return String.join("", chunks);
    }
}
