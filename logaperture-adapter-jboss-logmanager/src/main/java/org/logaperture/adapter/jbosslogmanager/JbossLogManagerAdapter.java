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
package org.logaperture.adapter.jbosslogmanager;

import org.jboss.logmanager.LogContext;
import org.jboss.logmanager.Logger;
import org.logaperture.api.Level;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Handler;

/**
 * {@link LoggingAdapter} over a single {@link org.jboss.logmanager.LogContext}
 * — see doc/specs/wildfly-support.md, Slice 2. One instance per context
 * (§15.4); Slice 3's WildFly integration builds one per registered
 * {@code LogContext} via {@link JbossLogManagerAdapterFactory}.
 *
 * <p><b>Read methods create on observe.</b> {@code configuredLevel},
 * {@code effectiveLevel}, {@code applyLevel} and {@link #handlerFloorsBelow}
 * all resolve a logger via {@code LogContext.getLogger(name)}, which
 * materialises it if absent — the "Known" state doc/specs/level-control.md
 * §8.5 describes, and the same behaviour as the Logback adapter (which has
 * no non-creating accessor). {@code core}'s baseline capture creates the
 * logger first anyway, so this costs nothing on the real call paths.
 *
 * <p><b>Root logger.</b> JBoss LogManager names its root logger {@code ""}
 * (empty string), which {@code core}/{@code NameFilter} would read as
 * "match everything". This adapter surfaces and accepts it as {@code
 * "ROOT"} instead, matching the Logback adapter's convention so a
 * cross-backend {@code logctl levels ROOT} means the same thing.
 *
 * <p><b>Pinned loggers.</b> JBoss LogManager holds {@code Logger} facades
 * through weak/phantom references by default ({@code LogContext.create()}
 * included), so a logger nothing else strongly references can be reaped and
 * lose its applied level. This adapter keeps a strong reference to every
 * {@code Logger} it has touched, which pins the whole node chain to the
 * root — an override applied through here stays applied without depending
 * on Slice 3's sweep.
 *
 * <p>Level read-back is lossy-but-defined (see {@link LevelMapper}); to keep
 * {@code resetLevel} exact anyway, this adapter privately retains the real
 * {@code java.util.logging.Level} it first observed for each logger and
 * restores <em>that</em> object when asked to apply a level that maps back
 * to it.
 *
 * <p>No reconfiguration hook: JBoss LogManager has none (doc/logaperture-spec.md
 * §4.3). Re-application after a WildFly logging-subsystem change is the
 * container's periodic verification sweep (Slice 3), not an adapter event —
 * so {@link #onReset}/{@link #clearResetListener} keep the SPI's no-op default.
 */
public final class JbossLogManagerAdapter implements LoggingAdapter {

    /** How this adapter names the root logger externally (Logback's convention). */
    static final String ROOT_ALIAS = "ROOT";

    /** JBoss LogManager's / JUL's actual name for the root logger. */
    private static final String JBOSS_ROOT_NAME = "";

    private final LogContext context;

    /** Strong references to every {@code Logger} touched — see "Pinned loggers" in the class doc. Keyed by resolved name. */
    private final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();

    /**
     * The real {@code java.util.logging.Level} each logger carried when this
     * adapter first observed it — {@code Optional.empty()} means "observed,
     * had no explicit level". Lets {@code applyLevel} restore an exact
     * {@code FINER}/{@code CONFIG} baseline that read back only as an
     * approximation. Keyed by resolved name.
     */
    private final ConcurrentHashMap<String, Optional<java.util.logging.Level>> capturedOriginals =
            new ConcurrentHashMap<>();

    /** Package-visible: tests construct against a fresh, throwaway {@code LogContext.create()}. */
    JbossLogManagerAdapter(LogContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public List<String> knownLoggerNames() {
        List<String> names = new ArrayList<>();
        boolean sawRoot = false;
        for (String name : Collections.list(context.getLoggerNames())) {
            if (name.equals(JBOSS_ROOT_NAME)) {
                names.add(ROOT_ALIAS);
                sawRoot = true;
            } else {
                names.add(name);
            }
        }
        if (!sawRoot) {
            names.add(0, ROOT_ALIAS); // the root always exists in a LogContext; surface it like Logback does
        }
        return List.copyOf(names);
    }

    @Override
    public Optional<Level> configuredLevel(String loggerName) {
        return Optional.ofNullable(LevelMapper.toApi(logger(loggerName).getLevel()));
    }

    @Override
    public Level effectiveLevel(String loggerName) {
        Logger start = logger(loggerName);
        for (Logger current = start; current != null; current = current.getParent()) {
            java.util.logging.Level explicit = current.getLevel();
            if (explicit != null) {
                return LevelMapper.toApi(explicit);
            }
        }
        // No ancestor carried an explicit level (a real WildFly root always
        // does; this is the defensive path). getEffectiveLevel() is JBoss
        // LogManager's own already-resolved int.
        return LevelMapper.fromIntValue(start.getEffectiveLevel());
    }

    @Override
    public void applyLevel(String loggerName, Level level) {
        String resolved = resolveName(loggerName);
        Logger logger = logger(loggerName);

        Level previousEffective = effectiveLevel(loggerName);
        logger.setLevel(resolveTarget(resolved, level));
        warnIfHandlerFloorWouldSwallow(loggerName, level, previousEffective);
    }

    // onReset / clearResetListener: SPI no-op default -- see class doc.

    /**
     * The handlers on {@code loggerName}'s path to the root whose own level
     * floor is stricter than {@code target} — the second, independent gate
     * (doc/specs/wildfly-support.md, "Handler-level thresholds"). Not on the
     * {@link LoggingAdapter} SPI: it does not generalise to Logback. A
     * {@code null} {@code target} ("back to inherited") is not a raise, so
     * it yields an empty list.
     */
    public List<HandlerFloor> handlerFloorsBelow(String loggerName, Level target) {
        if (target == null) {
            return List.of();
        }
        int targetValue = LevelMapper.toJul(target).intValue();
        List<HandlerFloor> floors = new ArrayList<>();
        for (Logger current = logger(loggerName); current != null; current = current.getParent()) {
            for (Handler handler : current.getHandlers()) {
                java.util.logging.Level handlerLevel = handler.getLevel();
                if (handlerLevel != null && handlerLevel.intValue() > targetValue) {
                    floors.add(new HandlerFloor(handler.getClass().getSimpleName(), LevelMapper.toApi(handlerLevel)));
                }
            }
            if (!current.getUseParentHandlers()) {
                break; // records stop propagating upward here (JUL semantics)
            }
        }
        return List.copyOf(floors);
    }

    /** Resolves the root alias, materialises the logger, and pins a strong reference to it. */
    private Logger logger(String requestedName) {
        return loggers.computeIfAbsent(resolveName(requestedName), name -> {
            Logger materialised = context.getLogger(name);
            capturedOriginals.computeIfAbsent(name, n -> Optional.ofNullable(materialised.getLevel()));
            return materialised;
        });
    }

    private static String resolveName(String loggerName) {
        return ROOT_ALIAS.equals(loggerName) ? JBOSS_ROOT_NAME : loggerName;
    }

    private java.util.logging.Level resolveTarget(String resolvedName, Level level) {
        if (level == null) {
            return null; // back to inherited -- always exact
        }
        Optional<java.util.logging.Level> original = capturedOriginals.get(resolvedName);
        if (original != null && original.isPresent() && LevelMapper.toApi(original.get()) == level) {
            // Asked to apply exactly the level the captured baseline read
            // back as -- restore the real object (e.g. FINER, not FINEST).
            // Display is identical either way; this only makes it exact.
            return original.get();
        }
        return LevelMapper.toJul(level);
    }

    private void warnIfHandlerFloorWouldSwallow(String loggerName, Level applied, Level previousEffective) {
        if (applied == null || !applied.isMoreVerboseThan(previousEffective)) {
            return; // not a raise -- nothing new to be swallowed
        }
        List<HandlerFloor> floors = handlerFloorsBelow(loggerName, applied);
        if (floors.isEmpty()) {
            return;
        }
        HandlerFloor first = floors.get(0);
        String more = floors.size() > 1 ? " (and " + (floors.size() - 1) + " more)" : "";
        Diagnostics.warn("logaperture: " + loggerName + " set to " + applied + ", but handler "
                + first.handlerName() + " has a level floor of " + first.floor() + more + " — " + applied
                + " records will not reach it. Lower the handler in standalone.xml, or accept that this "
                + "override only affects sinks without a stricter floor.");
    }
}
