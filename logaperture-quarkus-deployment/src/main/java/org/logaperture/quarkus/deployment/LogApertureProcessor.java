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
package org.logaperture.quarkus.deployment;

import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import org.logaperture.quarkus.runtime.LogApertureRecorder;

/** doc/specs/quarkus-extension.md "What has to be built", item 2. */
class LogApertureProcessor {

    private static final String FEATURE = "logaperture";

    @BuildStep
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    /**
     * At runtime init, after Quarkus's own logging setup ({@link LoggingSetupBuildItem}): JBoss
     * LogManager is installed and Quarkus's handlers are active, and the rest of startup has not run
     * yet, so restored overrides catch it (spike finding 3).
     */
    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    void start(LogApertureRecorder recorder, LoggingSetupBuildItem loggingSetup, ShutdownContextBuildItem shutdown) {
        recorder.start(shutdown);
    }
}
