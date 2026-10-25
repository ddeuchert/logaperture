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
import io.quarkus.deployment.builditem.ApplicationArchivesBuildItem;
import io.quarkus.deployment.builditem.HotDeploymentWatchedFileBuildItem;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.logging.LoggingSetupBuildItem;
import org.jboss.logging.Logger;
import org.logaperture.quarkus.runtime.BundledVendorDefaults;
import org.logaperture.quarkus.runtime.LogApertureRecorder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;

/** doc/specs/quarkus-extension.md "What has to be built", item 2. */
class LogApertureProcessor {

    private static final String FEATURE = "logaperture";
    private static final Logger LOG = Logger.getLogger(LogApertureProcessor.class);

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
    void start(LogApertureRecorder recorder, LoggingSetupBuildItem loggingSetup, ShutdownContextBuildItem shutdown,
            BundledVendorDefaultsBuildItem bundled) {
        recorder.start(shutdown, bundled.chunks());
    }

    /** doc/specs/bundled-vendor-defaults.md #10: an edit to the bundled file reloads the application in dev mode. */
    @BuildStep
    HotDeploymentWatchedFileBuildItem watchBundledVendorDefaults() {
        return new HotDeploymentWatchedFileBuildItem(BundledVendorDefaults.RESOURCE);
    }

    /**
     * doc/specs/bundled-vendor-defaults.md #1-#3: the vendor defaults file in the application's own
     * archive, checked now so a mistake fails the build. The same path in any other archive is
     * ignored, and named in the build log.
     */
    @BuildStep
    BundledVendorDefaultsBuildItem bundledVendorDefaults(ApplicationArchivesBuildItem archives) {
        Path own = archives.getRootArchive().getChildPath(BundledVendorDefaults.RESOURCE);
        warnAboutOtherArchives(own);
        if (own == null) {
            return new BundledVendorDefaultsBuildItem(List.of());
        }
        String content = read(own);
        List<String> errors = BundledVendorDefaults.errors(content);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("LogAperture: " + BundledVendorDefaults.RESOURCE
                    + " has errors, so the application was not built:\n  " + String.join("\n  ", errors));
        }
        return new BundledVendorDefaultsBuildItem(BundledVendorDefaults.chunks(content));
    }

    private static String read(Path file) {
        try {
            byte[] bytes = Files.readAllBytes(file);
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new IllegalStateException("LogAperture: " + BundledVendorDefaults.RESOURCE + " is not valid UTF-8", e);
        } catch (IOException e) {
            throw new UncheckedIOException("LogAperture: cannot read " + file, e);
        }
    }

    /** #2: a library must not set the application's baseline. Best effort: never fails the build. */
    private static void warnAboutOtherArchives(Path own) {
        try {
            URI ownUri = own == null ? null : own.toUri().normalize();
            Enumeration<URL> found = Thread.currentThread().getContextClassLoader()
                    .getResources(BundledVendorDefaults.RESOURCE);
            while (found.hasMoreElements()) {
                URL url = found.nextElement();
                if (ownUri != null && ownUri.equals(toUri(url))) {
                    continue;
                }
                LOG.warnf("Ignoring %s in %s: only the application's own file applies", BundledVendorDefaults.RESOURCE,
                        url);
            }
        } catch (IOException | RuntimeException e) {
            LOG.debugf(e, "could not look for %s in the application's dependencies", BundledVendorDefaults.RESOURCE);
        }
    }

    private static URI toUri(URL url) {
        try {
            return url.toURI().normalize();
        } catch (URISyntaxException e) {
            return null;
        }
    }
}
