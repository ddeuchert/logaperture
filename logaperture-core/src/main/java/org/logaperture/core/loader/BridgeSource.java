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
package org.logaperture.core.loader;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Where a {@link BridgeClassLoader} reads the adapter's class files from
 * (doc/specs/class-loader-detector.md, C2).
 */
@FunctionalInterface
public interface BridgeSource {

    /** The class file bytes for {@code binaryName}, or {@code null} if this source does not hold it. */
    byte[] classBytes(String binaryName);

    /**
     * Class files stored under {@code prefix} (for example {@code META-INF/logaperture/log4j2/}) in
     * a jar, read through {@code reader}'s resource stream. The system class loader looks for
     * {@code org/logaperture/...} at the jar's root, never under the prefix, so it cannot load
     * these classes by accident; only a {@link BridgeClassLoader} fed from this source defines them.
     * Needs no temporary file and no second jar.
     */
    static BridgeSource underPrefix(ClassLoader reader, String prefix) {
        Objects.requireNonNull(reader, "reader");
        String slashed = prefix.endsWith("/") ? prefix : prefix + "/";
        return binaryName -> {
            String resource = slashed + binaryName.replace('.', '/') + ".class";
            try (InputStream in = reader.getResourceAsStream(resource)) {
                return in == null ? null : in.readAllBytes();
            } catch (IOException e) {
                return null;
            }
        };
    }
}
