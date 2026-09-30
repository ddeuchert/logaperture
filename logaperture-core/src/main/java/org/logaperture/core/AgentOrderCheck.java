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

import org.logaperture.api.DoctorFinding;
import org.logaperture.api.Severity;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * {@code logctl doctor}'s {@code -javaagent} ordering checks, {@code agent.order} and {@code
 * agent.duplicate} -- doc/specs/doctor-agent-order.md. Process-wide, so {@link
 * AggregateLevelControl#diagnose()} runs it once, never per context, and its findings carry no
 * context. Reads the JVM's input arguments only when asked, never at {@code premain} time.
 */
final class AgentOrderCheck {

    static final String ORDER_CHECK = "agent.order";
    static final String DUPLICATE_CHECK = "agent.duplicate";

    private static final String JAVAAGENT_PREFIX = "-javaagent:";

    private final Supplier<List<String>> inputArguments;
    private final Optional<Path> ownJar;
    private final Path userDir;

    /**
     * @param inputArguments supplies the JVM's input arguments, in the order the JVM reports them
     * @param ownJar         the jar LogAperture's classes were loaded from, if known
     * @param userDir        what relative {@code -javaagent} paths resolve against
     */
    AgentOrderCheck(Supplier<List<String>> inputArguments, Optional<Path> ownJar, Path userDir) {
        this.inputArguments = Objects.requireNonNull(inputArguments, "inputArguments");
        this.ownJar = Objects.requireNonNull(ownJar, "ownJar");
        this.userDir = Objects.requireNonNull(userDir, "userDir");
    }

    /** The real inputs: this JVM's {@code RuntimeMXBean}, this class's own code source, {@code user.dir}. */
    static AgentOrderCheck forThisJvm() {
        Supplier<List<String>> runtimeArguments = () -> ManagementFactory.getRuntimeMXBean().getInputArguments();
        return new AgentOrderCheck(runtimeArguments, ownJarOf(AgentOrderCheck.class),
                Path.of(System.getProperty("user.dir", ".")));
    }

    /**
     * Every {@code agent.order} and {@code agent.duplicate} row for the current arguments; none at
     * all when the arguments can't be read (doc/specs/doctor.md "Failure handling").
     */
    List<DoctorFinding> findings() {
        try {
            List<String> arguments = inputArguments.get();
            return arguments == null ? List.of() : findings(arguments);
        } catch (RuntimeException e) {
            return List.of(); // arguments or a path unreadable (a SecurityException, say) -- skipped
        }
    }

    private List<DoctorFinding> findings(List<String> arguments) {
        List<AgentEntry> agents = agentEntries(arguments);
        List<DoctorFinding> findings = new ArrayList<>();
        int ownIndex = ownIndex(agents);
        if (ownIndex >= 0) {
            findings.add(orderFinding(agents, ownIndex));
        }
        findings.addAll(duplicateFindings(agents, ownIndex >= 0 ? agents.get(ownIndex).resolved() : null));
        return List.copyOf(findings);
    }

    // --- Reading the order ------------------------------------------------------------------------

    /** One {@code -javaagent:} entry: the path exactly as given, and resolved for comparison. */
    private record AgentEntry(String given, Path resolved) {
        String fileName() {
            Path name = resolved.getFileName();
            return name != null ? name.toString() : given;
        }
    }

    private List<AgentEntry> agentEntries(List<String> arguments) {
        List<AgentEntry> agents = new ArrayList<>();
        for (String argument : arguments) {
            if (argument == null || !argument.startsWith(JAVAAGENT_PREFIX)) {
                continue;
            }
            String spec = argument.substring(JAVAAGENT_PREFIX.length());
            int optionsAt = spec.indexOf('='); // the JVM's own split: the rest is that agent's options
            String given = optionsAt >= 0 ? spec.substring(0, optionsAt) : spec;
            if (given.isEmpty()) {
                continue;
            }
            resolve(given).ifPresent(resolved -> agents.add(new AgentEntry(given, resolved)));
        }
        return agents;
    }

    private Optional<Path> resolve(String given) {
        try {
            return Optional.of(canonical(userDir.resolve(given)));
        } catch (InvalidPathException e) {
            return Optional.empty();
        }
    }

    /** Absolute and normalised, then the real path where the file exists -- so a symlink compares equal. */
    private static Path canonical(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (Files.exists(normalized)) {
            try {
                return normalized.toRealPath();
            } catch (IOException | SecurityException e) {
                return normalized;
            }
        }
        return normalized;
    }

    /**
     * doc/specs/doctor-agent-order.md Decision #3: the entry matching our own code source; failing
     * that, the first whose file name looks like ours. {@code -1} when none is ours (dynamic attach).
     */
    private int ownIndex(List<AgentEntry> agents) {
        if (ownJar.isPresent()) {
            Path own = canonical(ownJar.get());
            for (int i = 0; i < agents.size(); i++) {
                if (agents.get(i).resolved().equals(own)) {
                    return i;
                }
            }
        }
        for (int i = 0; i < agents.size(); i++) {
            String name = agents.get(i).fileName();
            if (name.startsWith("logaperture-agent") && name.endsWith(".jar")) {
                return i;
            }
        }
        return -1;
    }

    // --- agent.order ------------------------------------------------------------------------------

    private static DoctorFinding orderFinding(List<AgentEntry> agents, int ownIndex) {
        AgentEntry own = agents.get(ownIndex);
        String subject = own.resolved().toString();
        if (ownIndex == 0) {
            return new DoctorFinding(ORDER_CHECK, Severity.OK, subject,
                    own.fileName() + " is the first -javaagent.", null, null);
        }
        List<AgentEntry> aheadEntries = agents.subList(0, ownIndex);
        List<String> ahead = aheadEntries.stream().map(AgentEntry::fileName).toList();
        String count = ahead.size() == 1 ? "1 agent is" : ahead.size() + " agents are";
        String detail = "an agent listed earlier runs its premain first, so anything it logs while starting up is "
                + "out of reach of drop/trim rules. Listing LogAperture first narrows that window; output an agent "
                + "writes outside the logging framework (its own console or file) stays out of reach either way.";
        // Two different jars with one file name read as the same agent in the summary -- name them in full.
        long distinctNames = aheadEntries.stream().map(AgentEntry::fileName).distinct().count();
        long distinctPaths = aheadEntries.stream().map(AgentEntry::resolved).distinct().count();
        if (distinctNames < distinctPaths) {
            detail += " Listed ahead, in order: "
                    + String.join(", ", aheadEntries.stream().map(e -> e.resolved().toString()).toList()) + ".";
        }
        // doc/specs/doctor-agent-order.md Decision #1: INFO -- it explains a missed rule, it isn't a
        // misconfiguration, and many sites are required to list another agent first.
        return new DoctorFinding(ORDER_CHECK, Severity.INFO, subject,
                count + " listed ahead of " + own.fileName() + ": " + String.join(", ", ahead) + ".",
                detail, "list -javaagent:" + own.given() + " before the other -javaagent entries.");
    }

    // --- agent.duplicate --------------------------------------------------------------------------

    private static List<DoctorFinding> duplicateFindings(List<AgentEntry> agents, Path ownResolved) {
        Map<Path, List<AgentEntry>> byPath = new LinkedHashMap<>();
        for (AgentEntry agent : agents) {
            byPath.computeIfAbsent(agent.resolved(), p -> new ArrayList<>()).add(agent);
        }
        List<DoctorFinding> findings = new ArrayList<>();
        for (Map.Entry<Path, List<AgentEntry>> entry : byPath.entrySet()) {
            List<AgentEntry> listed = entry.getValue();
            if (listed.size() < 2) {
                continue;
            }
            AgentEntry first = listed.get(0);
            boolean ours = entry.getKey().equals(ownResolved);
            // Decision #4: WARNING for our own jar -- the JVM runs premain once per entry; LogAperture starts
            // from the first and ignores the rest (issue #120), but writes a warning for each at startup.
            // INFO for any other agent's jar.
            Severity severity = ours ? Severity.WARNING : Severity.INFO;
            String detail = ours
                    ? "the JVM runs premain once per entry; LogAperture starts from the first and ignores the "
                            + "rest, writing a warning for each at startup."
                    : null;
            findings.add(new DoctorFinding(DUPLICATE_CHECK, severity, entry.getKey().toString(),
                    first.fileName() + " is listed " + listed.size() + " times as a -javaagent.", detail,
                    "remove all but one -javaagent:" + first.given() + " entry."));
        }
        return findings;
    }

    // --- Own jar ----------------------------------------------------------------------------------

    /** The jar {@code type} was loaded from; empty when that isn't a jar file (a classes dir, say). */
    static Optional<Path> ownJarOf(Class<?> type) {
        try {
            CodeSource source = type.getProtectionDomain().getCodeSource();
            if (source == null || source.getLocation() == null) {
                return Optional.empty();
            }
            Path location = Path.of(source.getLocation().toURI());
            return Files.isRegularFile(location) && location.toString().endsWith(".jar")
                    ? Optional.of(location)
                    : Optional.empty();
        } catch (URISyntaxException | RuntimeException e) {
            return Optional.empty();
        }
    }
}
