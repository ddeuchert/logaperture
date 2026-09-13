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
package org.logaperture.api;

/**
 * {@code logctl env}'s output — see doc/specs/environment-report.md "Data
 * model". Framework-independent (lives in {@code api}, alongside {@link
 * DoctorFinding} and {@link HandlerDiagnostics}), so both {@code core} (to
 * assemble it) and every control surface (to render it) depend on it
 * without depending on any one adapter.
 *
 * <p>Deliberately no {@code cliVersion} field here — {@code logctl} already
 * knows its own version locally and stitches it into the rendered/JSON
 * output alongside these agent-reported facts, the same "logctl-known vs.
 * agent-reported" split {@code cli-transport.md} draws elsewhere.
 *
 * @param agentVersion      the LogAperture agent's own version
 * @param javaVersion       {@code java.version} of the target JVM
 * @param javaVendor        {@code java.vendor} of the target JVM
 * @param osName            {@code os.name}
 * @param osVersion         {@code os.version}
 * @param osArch            {@code os.arch}
 * @param backendName       the detected logging backend's name, e.g.
 *                          {@code "JBoss LogManager"} — {@code null} if
 *                          undetected
 * @param backendVersion    the detected logging backend's version, or
 *                          {@code null} if not resolvable
 * @param containerName     the detected application framework/container's
 *                          name, e.g. {@code "WildFly"} — {@code null} for
 *                          the {@code none} baseline (no container at all)
 * @param containerVersion  the detected container's version, or {@code
 *                          null} if not resolvable
 * @param diagnosticsLevel  the agent's own {@code
 *                          -Dlogaperture.diagnostics.level} setting, or
 *                          {@code null} if not set
 * @param stateFilePath     the fully-qualified path of the file this JVM
 *                          persists {@code --for}/{@code --sticky}
 *                          overrides to, or {@code null} if persistence is
 *                          degraded to session-only (doc/specs/
 *                          persistence.md "The same-working-directory
 *                          collision") or this store has no single
 *                          filesystem location to name
 */
public record EnvironmentReport(
        String agentVersion,
        String javaVersion,
        String javaVendor,
        String osName,
        String osVersion,
        String osArch,
        String backendName,
        String backendVersion,
        String containerName,
        String containerVersion,
        String diagnosticsLevel,
        String stateFilePath) {

    public EnvironmentReport {
        if (agentVersion == null || agentVersion.isEmpty()) {
            throw new IllegalArgumentException("agentVersion must not be null or empty");
        }
        if (javaVersion == null || javaVersion.isEmpty()) {
            throw new IllegalArgumentException("javaVersion must not be null or empty");
        }
        if (javaVendor == null || javaVendor.isEmpty()) {
            throw new IllegalArgumentException("javaVendor must not be null or empty");
        }
        if (osName == null || osName.isEmpty()) {
            throw new IllegalArgumentException("osName must not be null or empty");
        }
        if (osVersion == null || osVersion.isEmpty()) {
            throw new IllegalArgumentException("osVersion must not be null or empty");
        }
        if (osArch == null || osArch.isEmpty()) {
            throw new IllegalArgumentException("osArch must not be null or empty");
        }
    }
}
