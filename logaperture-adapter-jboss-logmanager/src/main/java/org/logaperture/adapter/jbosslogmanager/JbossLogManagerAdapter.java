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

    private final LogContext context;

    /**
     * The real {@code java.util.logging.Level} each logger carried when this
     * adapter first observed it — {@code Optional.empty()} means "observed,
     * had no explicit level". Lets {@code applyLevel} restore an exact
     * {@code FINER}/{@code CONFIG} baseline that read back only as an
     * approximation.
     */
    private final ConcurrentHashMap<String, Optional<java.util.logging.Level>> capturedOriginals =
            new ConcurrentHashMap<>();

    /** Package-visible: tests construct against a fresh, throwaway {@code LogContext.create()}. */
    JbossLogManagerAdapter(LogContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public List<String> knownLoggerNames() {
        return Collections.list(context.getLoggerNames());
    }

    @Override
    public Optional<Level> configuredLevel(String loggerName) {
        // getLogger(name) creates the Logger as a side effect if absent --
        // the "Known" state doc/specs/level-control.md §8.5 describes, and
        // JBoss LogManager's own native behaviour, same as the Logback adapter.
        Logger logger = context.getLogger(loggerName);
        captureOriginalIfAbsent(loggerName, logger);
        return Optional.ofNullable(LevelMapper.toApi(logger.getLevel()));
    }

    @Override
    public Level effectiveLevel(String loggerName) {
        Logger logger = context.getLogger(loggerName);
        for (Logger current = logger; current != null; current = current.getParent()) {
            java.util.logging.Level explicit = current.getLevel();
            if (explicit != null) {
                return LevelMapper.toApi(explicit);
            }
        }
        // No ancestor carried an explicit level (a real WildFly root always
        // does; this is the defensive path). getEffectiveLevel() is JBoss
        // LogManager's own already-resolved int.
        return LevelMapper.fromIntValue(logger.getEffectiveLevel());
    }

    @Override
    public void applyLevel(String loggerName, Level level) {
        Logger logger = context.getLogger(loggerName);
        captureOriginalIfAbsent(loggerName, logger);

        Level previousEffective = effectiveLevel(loggerName);
        logger.setLevel(resolveTarget(loggerName, level));
        warnIfHandlerFloorWouldSwallow(loggerName, level, previousEffective);
    }

    // onReset / clearResetListener: SPI no-op default -- see class doc.

    /**
     * The handlers on {@code loggerName}'s path to the root whose own level
     * floor is stricter than {@code target} — the second, independent gate
     * (doc/specs/wildfly-support.md, "Handler-level thresholds"). Not on the
     * {@link LoggingAdapter} SPI: it does not generalise to Logback.
     */
    public List<HandlerFloor> handlerFloorsBelow(String loggerName, Level target) {
        int targetValue = LevelMapper.toJul(target).intValue();
        List<HandlerFloor> floors = new ArrayList<>();
        for (Logger current = context.getLogger(loggerName); current != null; current = current.getParent()) {
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

    private java.util.logging.Level resolveTarget(String loggerName, Level level) {
        if (level == null) {
            return null; // back to inherited -- always exact
        }
        Optional<java.util.logging.Level> original = capturedOriginals.get(loggerName);
        if (original != null && original.isPresent() && LevelMapper.toApi(original.get()) == level) {
            // Asked to apply exactly the level the captured baseline read
            // back as -- restore the real object (e.g. FINER, not FINEST).
            // Display is identical either way; this only makes it exact.
            return original.get();
        }
        return LevelMapper.toJul(level);
    }

    private void captureOriginalIfAbsent(String loggerName, Logger logger) {
        capturedOriginals.computeIfAbsent(loggerName, name -> Optional.ofNullable(logger.getLevel()));
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
