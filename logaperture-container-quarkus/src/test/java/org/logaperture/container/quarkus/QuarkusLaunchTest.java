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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The detection rules and the version lookup of doc/specs/quarkus-support.md, against synthetic
 * launches -- no Quarkus application is needed.
 */
class QuarkusLaunchTest {

    @TempDir
    Path dir;

    @Test
    void fastJarLaunch_isQuarkus() {
        assertTrue(QuarkusLaunch.isQuarkusLaunch(true, "/app/quarkus-app/quarkus-run.jar --x", "/app/quarkus-app/quarkus-run.jar"));
    }

    @Test
    void uberJarRunner_isQuarkus() {
        assertTrue(QuarkusLaunch.isQuarkusLaunch(true, "/app/demo-1.0-runner.jar", "/app/demo-1.0-runner.jar"));
        assertTrue(QuarkusLaunch.isQuarkusLaunch(true, "io.quarkus.runner.GeneratedMain", "/app/whatever.jar"));
    }

    @Test
    void entryPointResourceAlone_isNotEnough() {
        // quarkus-bootstrap-runner on a non-Quarkus classpath (spike finding 7)
        assertFalse(QuarkusLaunch.isQuarkusLaunch(true, "com.acme.Main", "/libs/bootstrap-runner.jar:/app"));
        assertFalse(QuarkusLaunch.isQuarkusLaunch(true, "/app/service.jar", "/app/service.jar"));
    }

    @Test
    void launchWithoutTheEntryPoint_isNotQuarkus() {
        assertFalse(QuarkusLaunch.isQuarkusLaunch(false, "/app/quarkus-app/quarkus-run.jar", "/app/quarkus-app/quarkus-run.jar"));
        assertFalse(QuarkusLaunch.isQuarkusLaunch(false, null, null));
    }

    @Test
    void applicationDatBesideTheLaunchJar_isQuarkus() throws IOException {
        Path jar = Files.createFile(dir.resolve("renamed.jar"));
        String command = jar + " arg";
        assertFalse(QuarkusLaunch.isQuarkusLaunch(true, command, jar.toString()));
        Files.createFile(dir.resolve("quarkus-application.dat"));
        assertTrue(QuarkusLaunch.isQuarkusLaunch(true, command, jar.toString()));
    }

    @Test
    void devModeFork_isQuarkusWithoutTheResource() {
        assertTrue(QuarkusLaunch.isQuarkusLaunch(false, "io.quarkus.deployment.dev.DevModeMain /x/y", "/x/y"));
    }

    @Test
    void launchJar_prefersTheCommandThenASingleClassPathJar() {
        assertEquals(Optional.of(Path.of("/a/b.jar")), QuarkusLaunch.launchJar("/a/b.jar x y", "/other.jar"));
        assertEquals(Optional.of(Path.of("/other.jar")), QuarkusLaunch.launchJar("com.acme.Main", "/other.jar"));
        assertEquals(Optional.empty(), QuarkusLaunch.launchJar("com.acme.Main", "/a.jar" + java.io.File.pathSeparator + "/b.jar"));
        assertEquals(Optional.empty(), QuarkusLaunch.launchJar(null, null));
    }

    @Test
    void version_fromTheFastJarLayout() throws IOException {
        Path lib = Files.createDirectories(dir.resolve("lib/main"));
        jarWithManifestVersion(lib.resolve("io.quarkus.quarkus-core-deployment-9.9.9.jar"), "9.9.9"); // not core
        jarWithManifestVersion(lib.resolve("io.quarkus.quarkus-core-3.39.5.jar"), "3.39.5");
        Path runJar = Files.createFile(dir.resolve("quarkus-run.jar"));

        assertEquals(Optional.of("3.39.5"), QuarkusLaunch.version(runJar + " --a", runJar.toString()));
    }

    @Test
    void version_fromAnUberJarsPomProperties() throws IOException {
        Path runner = dir.resolve("demo-1.0-runner.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(runner))) {
            out.putNextEntry(new JarEntry("META-INF/maven/io.quarkus/quarkus-core/pom.properties"));
            out.write("artifactId=quarkus-core\nversion=3.20.1\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        assertEquals(Optional.of("3.20.1"), QuarkusLaunch.version(runner.toString(), runner.toString()));
    }

    @Test
    void version_isEmptyWhenNothingIsReadable() throws IOException {
        Path runJar = Files.createFile(dir.resolve("quarkus-run.jar"));
        assertEquals(Optional.empty(), QuarkusLaunch.version(runJar.toString(), runJar.toString()));
        assertEquals(Optional.empty(), QuarkusLaunch.version(null, null));
        assertEquals(Optional.empty(), QuarkusLaunch.version("/no/such/quarkus-run.jar", "/no/such/quarkus-run.jar"));
    }

    private static void jarWithManifestVersion(Path jar, String version) throws IOException {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Implementation-Version", version);
        try (OutputStream file = Files.newOutputStream(jar); JarOutputStream out = new JarOutputStream(file, manifest)) {
            out.flush();
        }
    }
}
