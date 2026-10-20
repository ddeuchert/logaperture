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
package org.logaperture.container.quarkus;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;

/**
 * What can be read about a Quarkus launch without touching {@code java.util.logging}
 * (doc/specs/quarkus-support.md, "What has to be built" 2): is this JVM running a built Quarkus
 * application, and which Quarkus version is it. Everything here is a system property, a path test
 * or a file read, so it is safe at {@code premain} time.
 *
 * <p>Pure functions over their inputs, so a test can lock the rules in without a Quarkus app.
 */
final class QuarkusLaunch {

    static final String ENTRY_POINT_RESOURCE = "io/quarkus/bootstrap/runner/QuarkusEntryPoint.class";

    private static final String FAST_JAR = "quarkus-run.jar";
    private static final String GENERATED_MAIN = "io.quarkus.runner.GeneratedMain";
    private static final String DEV_MODE_MAIN = "io.quarkus.deployment.dev.DevModeMain";
    private static final String APPLICATION_DAT = "quarkus-application.dat";
    private static final String RUNNER_SUFFIX = "-runner.jar";
    private static final String QUARKUS_CORE_MANIFEST_NAME = "quarkus-core-";
    private static final String QUARKUS_CORE_POM_PROPERTIES = "META-INF/maven/io.quarkus/quarkus-core/pom.properties";

    private QuarkusLaunch() {
    }

    /**
     * Is this a Quarkus launch? The {@code QuarkusEntryPoint} resource alone is not enough: the
     * {@code quarkus-bootstrap-runner} jar can sit on a non-Quarkus classpath. So it is required
     * <em>together with</em> a launch indicator: {@code quarkus-run.jar}, {@code
     * io.quarkus.runner.GeneratedMain}, a {@code *-runner.jar}, or a {@code quarkus-application.dat}
     * next to the launch jar. A dev-mode fork ({@code DevModeMain}) names itself and needs no
     * resource.
     *
     * @param entryPointPresent whether {@link #ENTRY_POINT_RESOURCE} resolves on the system loader
     * @param javaCommand       {@code sun.java.command}, or {@code null}
     * @param classPath         {@code java.class.path}, or {@code null}
     */
    static boolean isQuarkusLaunch(boolean entryPointPresent, String javaCommand, String classPath) {
        String command = javaCommand == null ? "" : javaCommand;
        if (command.startsWith(DEV_MODE_MAIN)) {
            return true;
        }
        if (!entryPointPresent) {
            return false;
        }
        if (command.contains(GENERATED_MAIN)) {
            return true;
        }
        Optional<Path> jar = launchJar(command, classPath);
        if (jar.isEmpty()) {
            return false;
        }
        String fileName = String.valueOf(jar.get().getFileName());
        return fileName.equals(FAST_JAR) || fileName.endsWith(RUNNER_SUFFIX)
                || Files.exists(jar.get().resolveSibling(APPLICATION_DAT));
    }

    /**
     * The jar the JVM was started with ({@code java -jar X.jar}): the first token of {@code
     * sun.java.command} when it ends in {@code .jar}, else the sole {@code java.class.path} entry.
     */
    static Optional<Path> launchJar(String javaCommand, String classPath) {
        if (javaCommand != null) {
            String first = javaCommand.trim().split("\\s+", 2)[0];
            if (first.endsWith(".jar")) {
                return Optional.of(Path.of(first));
            }
        }
        if (classPath != null && classPath.endsWith(".jar") && !classPath.contains(java.io.File.pathSeparator)) {
            return Optional.of(Path.of(classPath));
        }
        return Optional.empty();
    }

    /**
     * The Quarkus version, from the {@code quarkus-core} jar's manifest ({@code
     * Implementation-Version}). In a fast-jar the jar is {@code lib/main/io.quarkus.quarkus-core-<v>.jar}
     * beside {@code quarkus-run.jar}; in an uber-jar its {@code pom.properties} is inside the
     * runner jar. A file read only; empty when neither is found.
     */
    static Optional<String> version(String javaCommand, String classPath) {
        Optional<Path> launchJar = launchJar(javaCommand, classPath);
        if (launchJar.isEmpty()) {
            return Optional.empty();
        }
        Path jar = launchJar.get();
        Path dir = jar.toAbsolutePath().getParent();
        if (dir != null) {
            Optional<String> fastJar = versionFromFastJarLayout(dir);
            if (fastJar.isPresent()) {
                return fastJar;
            }
        }
        return versionFromUberJar(jar);
    }

    static Optional<String> versionFromFastJarLayout(Path applicationDir) {
        for (String lib : new String[] {"lib/main", "lib/boot"}) {
            Path libDir = applicationDir.resolve(lib);
            if (!Files.isDirectory(libDir)) {
                continue;
            }
            try (DirectoryStream<Path> cores = Files.newDirectoryStream(libDir, "*" + QUARKUS_CORE_MANIFEST_NAME + "*.jar")) {
                for (Path core : cores) {
                    if (!isQuarkusCoreFileName(String.valueOf(core.getFileName()))) {
                        continue;
                    }
                    try (JarFile jarFile = new JarFile(core.toFile())) {
                        Manifest manifest = jarFile.getManifest();
                        String version = manifest == null ? null
                                : manifest.getMainAttributes().getValue("Implementation-Version");
                        if (version != null) {
                            return Optional.of(version);
                        }
                    }
                }
            } catch (IOException | RuntimeException notReadable) {
                // best effort -- logctl env just omits the version
            }
        }
        return Optional.empty();
    }

    /** {@code quarkus-core-3.39.5.jar} or {@code io.quarkus.quarkus-core-3.39.5.jar}, but not {@code quarkus-core-deployment}. */
    private static boolean isQuarkusCoreFileName(String name) {
        int at = name.indexOf(QUARKUS_CORE_MANIFEST_NAME);
        if (at < 0 || !name.endsWith(".jar")) {
            return false;
        }
        String rest = name.substring(at + QUARKUS_CORE_MANIFEST_NAME.length());
        return !rest.isEmpty() && Character.isDigit(rest.charAt(0));
    }

    static Optional<String> versionFromUberJar(Path runnerJar) {
        try (JarFile jarFile = new JarFile(runnerJar.toFile())) {
            ZipEntry pomProperties = jarFile.getEntry(QUARKUS_CORE_POM_PROPERTIES);
            if (pomProperties == null) {
                return Optional.empty();
            }
            try (InputStream in = jarFile.getInputStream(pomProperties)) {
                Properties properties = new Properties();
                properties.load(in);
                return Optional.ofNullable(properties.getProperty("version"));
            }
        } catch (IOException | RuntimeException notReadable) {
            return Optional.empty();
        }
    }
}
