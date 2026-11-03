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

/**
 * The Log4j 2 version, read from the package of a class that is already loaded -- no Log4j code
 * runs (doc/specs/log4j2-adapter.md A10).
 */
final class Log4j2Version {

    /** The oldest Log4j 2 this adapter controls: the fluent API reaches the gate from here (spike I-F). */
    static final int MIN_MAJOR = 2;
    static final int MIN_MINOR = 20;

    private Log4j2Version() {
    }

    /**
     * The version of the Log4j that defined {@code contextClass}, or {@code null} if it cannot be
     * trusted. {@code getImplementationVersion()} reads the manifest of whichever jar holds the class,
     * and in a shaded or assembled jar that is the <em>application's</em> manifest (a version like
     * {@code 1.4.0}): so it is believed only when the same manifest's title says Log4j. No Log4j code
     * runs.
     */
    static String of(Class<?> contextClass) {
        Package pkg = contextClass.getPackage();
        if (pkg == null) {
            return null;
        }
        return believed(pkg.getImplementationTitle(), pkg.getImplementationVersion());
    }

    /** {@code version} if {@code title} names Log4j, else {@code null} (unknown, not "too old"). */
    static String believed(String title, String version) {
        if (title == null || version == null || !title.toLowerCase(java.util.Locale.ROOT).contains("log4j")) {
            return null;
        }
        return version;
    }

    /** {@code true} if {@code version} is older than 2.20.0. {@code null} or unparseable is not "older". */
    static boolean isTooOld(String version) {
        int[] parsed = parse(version);
        if (parsed == null) {
            return false;
        }
        return parsed[0] < MIN_MAJOR || (parsed[0] == MIN_MAJOR && parsed[1] < MIN_MINOR);
    }

    /** {@code [major, minor]}, or {@code null} if {@code version} does not start with {@code N.N}. */
    static int[] parse(String version) {
        if (version == null) {
            return null;
        }
        String[] parts = version.split("[.\\-]", 3);
        if (parts.length < 2) {
            return null;
        }
        try {
            return new int[] {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
