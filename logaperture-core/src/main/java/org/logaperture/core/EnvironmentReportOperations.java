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

import org.logaperture.api.EnvironmentReport;

/**
 * {@code logctl env}'s public contract — the {@link DoctorOperations} /
 * {@link TopOperations} counterpart for a read-only environment report
 * (doc/specs/environment-report.md). Every control surface is a client of
 * this interface, same as the others.
 */
public interface EnvironmentReportOperations {

    /**
     * Facts for a bug report: agent/JVM/OS versions, the detected logging
     * backend, and the detected application framework/container — never a
     * finding or a fix, {@code doctor}'s job. A fact this JVM doesn't
     * resolve is {@code null}, never a command failure.
     */
    EnvironmentReport environmentReport();
}
