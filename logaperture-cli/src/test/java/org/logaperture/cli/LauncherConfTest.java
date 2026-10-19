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
package org.logaperture.cli;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The {@code bin/logctl} launcher's conf file and Java selection (doc/specs/launcher-conf.md). Runs the real script
 * against a stub {@code java} that prints what it was given. The {@code logctl.cmd} equivalent is checked by hand on
 * Windows.
 */
class LauncherConfTest {

    private static final String OS = System.getProperty("os.name", "");

    @TempDir
    Path root;

    private Path bin;
    private Path pathDir;

    @BeforeEach
    void layout() throws IOException {
        assumeFalse(OS.startsWith("Windows"), "sh launcher");
        bin = Files.createDirectories(root.resolve("dist/bin"));
        Files.createDirectories(root.resolve("dist/lib"));
        Files.writeString(root.resolve("dist/lib/logaperture-cli.jar"), "");
        Files.copy(Path.of("bin/logctl"), bin.resolve("logctl"), StandardCopyOption.REPLACE_EXISTING);
        bin.resolve("logctl").toFile().setExecutable(true);
        pathDir = Files.createDirectories(root.resolve("pathbin"));
        stubJava(pathDir.resolve("java"), "PATH-java");
    }

    private static void stubJava(Path java, String name) throws IOException {
        Files.createDirectories(java.getParent());
        Files.writeString(java, "#!/bin/sh\necho \"java=" + name + "\"\necho \"args=$*\"\necho \"tool=${JAVA_TOOL_OPTIONS:-}\"\n");
        java.toFile().setExecutable(true);
    }

    private Result run(Path workDir, Map<String, String> env, String... args) throws Exception {
        String[] cmd = new String[args.length + 1];
        cmd[0] = bin.resolve("logctl").toString();
        System.arraycopy(args, 0, cmd, 1, args.length);
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(workDir.toFile());
        pb.environment().clear();
        pb.environment().put("PATH", pathDir + ":/usr/bin:/bin");
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
        return new Result(p.waitFor(), out, err);
    }

    private record Result(int exit, String out, String err) {
    }

    @Test
    void noConfAndNoJavaHomeUsesPath() throws Exception {
        Result r = run(root, Map.of(), "status");
        assertEquals(0, r.exit());
        assertTrue(r.out().contains("java=PATH-java"), r.out());
        assertTrue(r.out().contains("args=-jar " + root.resolve("dist/bin/../lib/logaperture-cli.jar") + " status")
                || r.out().contains("lib/logaperture-cli.jar status"), r.out());
    }

    @Test
    void ambientJavaHomeIsHonored() throws Exception {
        stubJava(root.resolve("ambient/bin/java"), "ambient");
        Result r = run(root, Map.of("JAVA_HOME", root.resolve("ambient").toString()));
        assertTrue(r.out().contains("java=ambient"), r.out());
    }

    @Test
    void confJavaHomeOverridesAmbientAndResolvesAgainstLauncher() throws Exception {
        stubJava(root.resolve("dist/jdk/bin/java"), "bundled");
        stubJava(root.resolve("ambient/bin/java"), "ambient");
        Files.writeString(bin.resolve("logctl.conf"), "JAVA_HOME=\"$here/../jdk\"\n");
        Path elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        Result r = run(elsewhere, Map.of("JAVA_HOME", root.resolve("ambient").toString()));
        assertEquals(0, r.exit(), r.err());
        assertTrue(r.out().contains("java=bundled"), r.out());
    }

    @Test
    void missingJavaHomeFailsWithoutFallingBackToPath() throws Exception {
        Result r = run(root, Map.of("JAVA_HOME", root.resolve("nowhere").toString()));
        assertEquals(1, r.exit());
        assertTrue(r.err().contains("logctl: JAVA_HOME is set to " + root.resolve("nowhere")
                + " but " + root.resolve("nowhere/bin/java") + " was not found."), r.err());
        assertFalse(r.out().contains("PATH-java"), r.out());
    }

    @Test
    void confCanClearInheritedVariables() throws Exception {
        Files.writeString(bin.resolve("logctl.conf"), "unset JAVA_TOOL_OPTIONS\nJAVA_OPTS=\n");
        Result r = run(root, Map.of("JAVA_TOOL_OPTIONS", "-Xfoo", "JAVA_OPTS", "-Dambient=1"));
        assertTrue(r.out().contains("tool=\n"), r.out());
        assertFalse(r.out().contains("-Dambient"), r.out());
    }

    @Test
    void javaOptsReachTheJvmBeforeJar() throws Exception {
        Files.writeString(bin.resolve("logctl.conf"), "JAVA_OPTS=\"-Xmx64m -Djdk.console=java.base\"\n");
        Result r = run(root, Map.of());
        assertTrue(r.out().contains("args=-Xmx64m -Djdk.console=java.base -jar "), r.out());
    }
}
