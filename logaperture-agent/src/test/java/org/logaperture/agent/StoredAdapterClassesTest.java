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
package org.logaperture.agent;

import org.junit.jupiter.api.Test;
import org.logaperture.core.loader.BridgeSource;
import org.logaperture.core.loader.FrameworkDetection;
import org.logaperture.core.loader.KnownFrameworks;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The Log4j 2 adapter's class files are copied into this module's output by path at
 * {@code process-classes}, and a missing adapter build copies nothing without an error. This fails
 * a plain {@code mvn test} instead of leaving the gap to a failing IT, or to a jar that cannot bind
 * Log4j 2 (doc/specs/class-loader-detector.md C2).
 */
class StoredAdapterClassesTest {

    @Test
    void theEntryClassIsStoredUnderTheWatchsFolder() {
        BridgeSource source = BridgeSource.underPrefix(getClass().getClassLoader(),
                FrameworkDetection.BRIDGE_FOLDER + KnownFrameworks.LOG4J2.framework());
        assertNotNull(source.classBytes(KnownFrameworks.LOG4J2.entryClass()),
                "build the adapter first: mvn -pl logaperture-agent -am ... (see DEVELOPMENT.md)");
    }
}
