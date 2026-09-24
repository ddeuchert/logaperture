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
package org.logaperture.control.jmx;

import org.logaperture.api.EnvironmentReport;

import java.beans.ConstructorProperties;

/**
 * MXBean-friendly mirror of {@link EnvironmentReport} — same reasoning as
 * {@link DoctorFindingData}: a plain class with a {@link
 * ConstructorProperties} constructor and JavaBean getters rather than the
 * {@code api} record directly, since record-to-{@code CompositeType} MXBean
 * support only landed around JDK 20 and this project is pinned to Java 17.
 */
public final class EnvironmentReportData {

    private final String agentVersion;
    private final String javaVersion;
    private final String javaVendor;
    private final String osName;
    private final String osVersion;
    private final String osArch;
    private final String backendName;
    private final String backendVersion;
    private final String containerName;
    private final String containerVersion;
    private final String diagnosticsLevel;
    private final String stateFilePath;
    private final String vendorDefaultsPath;
    private final String vendorDefaultsStatus;

    /**
     * Every field, including the vendor defaults pair (doc/specs/vendor-defaults.md "Surfaces");
     * the narrower constructor below stays annotated for older clients (logaperture-spec.md §11.1).
     */
    @ConstructorProperties({"agentVersion", "javaVersion", "javaVendor", "osName", "osVersion", "osArch",
            "backendName", "backendVersion", "containerName", "containerVersion", "diagnosticsLevel",
            "stateFilePath", "vendorDefaultsPath", "vendorDefaultsStatus"})
    public EnvironmentReportData(String agentVersion, String javaVersion, String javaVendor, String osName,
            String osVersion, String osArch, String backendName, String backendVersion, String containerName,
            String containerVersion, String diagnosticsLevel, String stateFilePath, String vendorDefaultsPath,
            String vendorDefaultsStatus) {
        this.vendorDefaultsPath = vendorDefaultsPath;
        this.vendorDefaultsStatus = vendorDefaultsStatus;
        this.agentVersion = agentVersion;
        this.javaVersion = javaVersion;
        this.javaVendor = javaVendor;
        this.osName = osName;
        this.osVersion = osVersion;
        this.osArch = osArch;
        this.backendName = backendName;
        this.backendVersion = backendVersion;
        this.containerName = containerName;
        this.containerVersion = containerVersion;
        this.diagnosticsLevel = diagnosticsLevel;
        this.stateFilePath = stateFilePath;
    }

    @ConstructorProperties({"agentVersion", "javaVersion", "javaVendor", "osName", "osVersion", "osArch",
            "backendName", "backendVersion", "containerName", "containerVersion", "diagnosticsLevel",
            "stateFilePath"})
    public EnvironmentReportData(String agentVersion, String javaVersion, String javaVendor, String osName,
            String osVersion, String osArch, String backendName, String backendVersion, String containerName,
            String containerVersion, String diagnosticsLevel, String stateFilePath) {
        this(agentVersion, javaVersion, javaVendor, osName, osVersion, osArch, backendName, backendVersion,
                containerName, containerVersion, diagnosticsLevel, stateFilePath, null, null);
    }

    /** The vendor defaults file's absolute path, or {@code null} if none was configured. */
    public String getVendorDefaultsPath() {
        return vendorDefaultsPath;
    }

    /** {@code "not configured"}, {@code "loaded (...)"} or {@code "rejected (N errors)"}; {@code null} from an older agent. */
    public String getVendorDefaultsStatus() {
        return vendorDefaultsStatus;
    }

    public static EnvironmentReportData from(EnvironmentReport report) {
        return new EnvironmentReportData(
                report.agentVersion(),
                report.javaVersion(),
                report.javaVendor(),
                report.osName(),
                report.osVersion(),
                report.osArch(),
                report.backendName(),
                report.backendVersion(),
                report.containerName(),
                report.containerVersion(),
                report.diagnosticsLevel(),
                report.stateFilePath(),
                report.vendorDefaultsPath(),
                report.vendorDefaultsStatus());
    }

    public String getAgentVersion() {
        return agentVersion;
    }

    public String getJavaVersion() {
        return javaVersion;
    }

    public String getJavaVendor() {
        return javaVendor;
    }

    public String getOsName() {
        return osName;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public String getOsArch() {
        return osArch;
    }

    public String getBackendName() {
        return backendName;
    }

    public String getBackendVersion() {
        return backendVersion;
    }

    public String getContainerName() {
        return containerName;
    }

    public String getContainerVersion() {
        return containerVersion;
    }

    public String getDiagnosticsLevel() {
        return diagnosticsLevel;
    }

    public String getStateFilePath() {
        return stateFilePath;
    }
}
