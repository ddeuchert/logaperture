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

import org.logaperture.core.PrivateDirectory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Chooses the agent jar {@code logctl attach} loads into a JVM (doc/specs/logctl-attach.md #2).
 * In order: {@code --agent-jar} exactly as given; a {@code logaperture-agent.jar} beside the CLI
 * jar (or in {@code LOGCTL_JAR_HOME}) with the same major version as the embedded copy and a newer
 * one; else the copy the CLI jar carries.
 *
 * <p>The embedded copy has to be a real file for {@code VirtualMachine.loadAgent}, and the target
 * JVM keeps that file on its class path until it exits, so it is written once to a stable
 * directory and <b>never deleted</b>. The directory is private to this user when it sits under the shared
 * temp directory, and an existing file is reused only if its bytes are what would be written. The file name carries a digest of the content, so a rebuilt
 * jar with the same version string never overwrites one a running JVM still has open.
 */
final class AgentJar {

    /** Where the build puts the shaded agent jar inside the CLI jar. */
    static final String EMBEDDED_RESOURCE = "/agent/logaperture-agent.jar";

    /** The agent jar's file name beside the CLI jar. */
    static final String FILE_NAME = "logaperture-agent.jar";

    /** Which of the three the choice came from. */
    enum Source {
        EXPLICIT, BESIDE_CLI, EMBEDDED
    }

    /**
     * @param path    the jar to pass to {@code loadAgent}
     * @param version its {@code Implementation-Version}, or {@code null} when it has none
     * @param reason  one line for the operator on why this jar
     */
    record Choice(Path path, String version, Source source, String reason) {
    }

    private AgentJar() {
    }

    /** The real environment: this CLI jar's own resource, its own directory, the operator's home. */
    static Choice choose(Path explicit) {
        return choose(explicit, besideCliDirectory(), embeddedBytes(), extractionDirectories());
    }

    /**
     * {@code ${logaperture.home}/agent} (home defaulting to {@code ~/.logaperture}, as the agent's state
     * store resolves it), then a per-user directory under the temp directory for a home that cannot be
     * written (doc/specs/logctl-attach.md #8).
     */
    static List<Path> extractionDirectories() {
        String configured = System.getProperty("logaperture.home");
        Path home = configured != null && !configured.isBlank()
                ? Path.of(configured)
                : Path.of(System.getProperty("user.home"), ".logaperture");
        Path temp = Path.of(System.getProperty("java.io.tmpdir"), "logaperture-" + System.getProperty("user.name"));
        return List.of(home.resolve("agent"), temp.resolve("agent"));
    }

    /**
     * @param explicit       {@code --agent-jar}, or {@code null}
     * @param besideDir      where a newer jar may sit, or {@code null}
     * @param embedded       the embedded jar's bytes, or {@code null} when this build carries none
     * @param extractionDirs directories to try, in order, for writing the embedded copy
     */
    static Choice choose(Path explicit, Path besideDir, byte[] embedded, List<Path> extractionDirs) {
        if (explicit != null) {
            if (!Files.isRegularFile(explicit) || !Files.isReadable(explicit)) {
                throw new CliError(CliError.USAGE, "--agent-jar " + explicit + " is not a readable file.");
            }
            return new Choice(explicit.toAbsolutePath(), versionOf(explicit), Source.EXPLICIT,
                    "the jar named with --agent-jar");
        }

        String embeddedVersion = embedded == null ? null : versionOf(embedded);
        Choice beside = candidateBeside(besideDir);
        if (beside != null) {
            if (embedded == null) {
                return beside;
            }
            Version offered = Version.parse(beside.version());
            Version own = Version.parse(embeddedVersion);
            if (offered != null && own != null && offered.major() == own.major() && offered.compareTo(own) > 0) {
                return beside;
            }
        }
        if (embedded == null) {
            throw new CliError(CliError.USAGE, "This logctl does not carry an agent jar and none was found next to it. "
                    + "Pass one with --agent-jar <file>.");
        }
        Path written = extract(embedded, embeddedVersion, extractionDirs);
        return new Choice(written, embeddedVersion, Source.EMBEDDED, "the agent jar carried inside this logctl");
    }

    private static Choice candidateBeside(Path besideDir) {
        if (besideDir == null) {
            return null;
        }
        Path jar = besideDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(jar) || !Files.isReadable(jar)) {
            return null;
        }
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            if (manifest == null || manifest.getMainAttributes().getValue("Agent-Class") == null) {
                return null;
            }
            String version = manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            return new Choice(jar.toAbsolutePath(), version, Source.BESIDE_CLI,
                    "a newer agent jar next to this logctl");
        } catch (IOException notAJar) {
            return null;
        }
    }

    private static String versionOf(Path jar) {
        try (JarFile file = new JarFile(jar.toFile())) {
            Manifest manifest = file.getManifest();
            return manifest == null ? null
                    : manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        } catch (IOException notAJar) {
            return null;
        }
    }

    private static String versionOf(byte[] jar) {
        try (JarInputStream in = new JarInputStream(new ByteArrayInputStream(jar))) {
            Manifest manifest = in.getManifest();
            return manifest == null ? null
                    : manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    /** The directory holding the CLI jar, or {@code LOGCTL_JAR_HOME} when the launcher set it. */
    private static Path besideCliDirectory() {
        String override = System.getenv("LOGCTL_JAR_HOME");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        try {
            Path location = Path.of(AgentJar.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            return Files.isRegularFile(location) ? location.getParent() : null;
        } catch (Exception unknown) {
            return null;
        }
    }

    private static byte[] embeddedBytes() {
        try (InputStream in = AgentJar.class.getResourceAsStream(EMBEDDED_RESOURCE)) {
            return in == null ? null : in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Writes {@code bytes} to the first directory that takes it and returns the file; reuses a file
     * already there. Never deletes anything.
     */
    static Path extract(byte[] bytes, String version, List<Path> dirs) {
        String name = "logaperture-agent-" + (version == null ? "unknown" : version.replaceAll("[^0-9A-Za-z._-]", "_"))
                + "-" + digest(bytes) + ".jar";
        List<String> failures = new ArrayList<>();
        for (Path dir : dirs) {
            try {
                // Under the temp directory, which other users can write to, the directory must be ours alone:
                // the jar written here is loaded into another process.
                PrivateDirectory.ensure(dir, Path.of(System.getProperty("java.io.tmpdir")));
                Path target = dir.resolve(name);
                if (Files.isSymbolicLink(target)) {
                    throw new IOException(target + " is a symbolic link");
                }
                if (Files.isRegularFile(target) && Arrays.equals(Files.readAllBytes(target), bytes)) {
                    return target.toAbsolutePath(); // the content is what we would write: reuse it
                }
                Path partial = Files.createTempFile(dir, "logaperture-agent-", ".part");
                try {
                    Files.write(partial, bytes);
                    try {
                        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
                    } catch (AtomicMoveNotSupportedException e) {
                        Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(partial);
                }
                return target.toAbsolutePath();
            } catch (IOException e) {
                failures.add(dir + " (" + e.getClass().getSimpleName() + ")");
            }
        }
        throw new CliError(CliError.UNEXPECTED, "Could not write the agent jar to any of: " + String.join(", ", failures)
                + ". Pass one with --agent-jar <file>.");
    }

    private static String digest(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@code major.minor.patch[-prerelease]}, ordered as Semantic Versioning orders them. */
    record Version(int major, int minor, int patch, String pre) implements Comparable<Version> {

        private static final Pattern FORM = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)(?:-([0-9A-Za-z.-]+))?(?:\\+.*)?");

        /** {@code null} for anything that is not of that form. */
        static Version parse(String text) {
            if (text == null) {
                return null;
            }
            Matcher m = FORM.matcher(text.strip());
            if (!m.matches()) {
                return null;
            }
            try {
                return new Version(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                        Integer.parseInt(m.group(3)), m.group(4));
            } catch (NumberFormatException tooLarge) {
                return null;
            }
        }

        @Override
        public int compareTo(Version other) {
            int c = Integer.compare(major, other.major);
            if (c == 0) {
                c = Integer.compare(minor, other.minor);
            }
            if (c == 0) {
                c = Integer.compare(patch, other.patch);
            }
            if (c != 0) {
                return c;
            }
            if (pre == null || other.pre == null) {
                return pre == null ? (other.pre == null ? 0 : 1) : -1; // a release outranks its pre-releases
            }
            String[] mine = pre.split("\\.");
            String[] theirs = other.pre.split("\\.");
            for (int i = 0; i < Math.min(mine.length, theirs.length); i++) {
                boolean mineNumeric = mine[i].chars().allMatch(Character::isDigit);
                boolean theirsNumeric = theirs[i].chars().allMatch(Character::isDigit);
                int part;
                if (mineNumeric && theirsNumeric) {
                    part = Long.compare(Long.parseLong(mine[i]), Long.parseLong(theirs[i]));
                } else if (mineNumeric != theirsNumeric) {
                    part = mineNumeric ? -1 : 1; // numeric identifiers rank below alphanumeric ones
                } else {
                    part = mine[i].compareTo(theirs[i]);
                }
                if (part != 0) {
                    return part;
                }
            }
            return Integer.compare(mine.length, theirs.length);
        }
    }
}
