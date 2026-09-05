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

import org.logaperture.api.HandlerFloor;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.UnknownHandlerException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * A hand-written {@link LoggingAdapter} test double, with no Logback or
 * agent involvement — this is what proves {@code core} is genuinely
 * framework-agnostic, per the implementation plan's build sequencing.
 *
 * <p>Models a simple dot-hierarchy, mimicking Logback's own resolution:
 * {@code effectiveLevel} walks up from the queried name to {@code ROOT},
 * returning the nearest explicit level.
 */
final class FakeLoggingAdapter implements LoggingAdapter {

    private final Map<String, Level> explicitLevels = new LinkedHashMap<>();
    private final Set<String> knownNames = new LinkedHashSet<>();
    private String throwOnApplyFor;
    private String runOnEffectiveLevelFor;
    private Runnable runOnEffectiveLevel;

    // --- handler support (doc/specs/handler-floor-control.md) --------------------------------------
    private final Map<HandlerRef, Level> handlerLevels = new LinkedHashMap<>();
    private final Set<HandlerRef> registeredHandlers = new LinkedHashSet<>();
    private final Map<String, List<HandlerRef>> handlersOnPath = new LinkedHashMap<>();
    private final Set<HandlerRef> vanishedHandlers = new LinkedHashSet<>();
    private HandlerRef throwOnSetHandlerLevelFor;
    private boolean hasHandlerLevels = true; // this fake models a JUL-like framework by default

    FakeLoggingAdapter(Level rootLevel) {
        knownNames.add("ROOT");
        explicitLevels.put("ROOT", rootLevel);
    }

    void addKnownLogger(String name) {
        knownNames.add(name);
    }

    void setConfiguredLevel(String name, Level level) {
        knownNames.add(name);
        explicitLevels.put(name, level);
    }

    /** Makes the next {@link #applyLevel} call for this logger throw, to exercise chaos-case behavior. */
    void throwOnApply(String loggerName) {
        this.throwOnApplyFor = loggerName;
    }

    /**
     * Runs {@code action} once, the next time {@link #effectiveLevel} is
     * called for {@code loggerName} — a deterministic seam for simulating a
     * concurrent control-plane mutation landing mid-sweep.
     */
    void runOnEffectiveLevel(String loggerName, Runnable action) {
        this.runOnEffectiveLevelFor = loggerName;
        this.runOnEffectiveLevel = action;
    }

    @Override
    public List<String> knownLoggerNames() {
        return List.copyOf(knownNames);
    }

    @Override
    public Optional<Level> configuredLevel(String loggerName) {
        knownNames.add(loggerName); // matches Logback's getLogger()-creates-as-side-effect behavior
        return Optional.ofNullable(explicitLevels.get(loggerName));
    }

    @Override
    public Level effectiveLevel(String loggerName) {
        if (loggerName.equals(runOnEffectiveLevelFor)) {
            Runnable action = runOnEffectiveLevel;
            runOnEffectiveLevelFor = null; // one-shot
            runOnEffectiveLevel = null;
            action.run();
        }
        String name = loggerName;
        while (true) {
            Level explicit = explicitLevels.get(name);
            if (explicit != null) {
                return explicit;
            }
            int dot = name.lastIndexOf('.');
            if (dot < 0) {
                break;
            }
            name = name.substring(0, dot);
        }
        return explicitLevels.get("ROOT");
    }

    @Override
    public void applyLevel(String loggerName, Level level) {
        knownNames.add(loggerName);
        if (loggerName.equals(throwOnApplyFor)) {
            throwOnApplyFor = null; // one-shot
            throw new RuntimeException("simulated adapter failure for " + loggerName);
        }
        if (level == null) {
            explicitLevels.remove(loggerName);
        } else {
            explicitLevels.put(loggerName, level);
        }
    }

    // --- handler support (doc/specs/handler-floor-control.md) --------------------------------------

    /** Registers {@code ref} at {@code level}, on the path of every logger listed. */
    void addHandler(HandlerRef ref, Level level, String... loggerNamesOnItsPath) {
        handlerLevels.put(ref, level);
        registeredHandlers.add(ref);
        for (String loggerName : loggerNamesOnItsPath) {
            handlersOnPath.computeIfAbsent(loggerName, n -> new ArrayList<>()).add(ref);
        }
    }

    /** Makes the next {@link #setHandlerLevel} call for this ref throw, to exercise chaos-case behavior. */
    void throwOnSetHandlerLevel(HandlerRef ref) {
        this.throwOnSetHandlerLevelFor = ref;
    }

    /** Models a framework whose handlers have no level of their own (Logback, {@code none}). */
    void disableHandlerLevels() {
        this.hasHandlerLevels = false;
    }

    @Override
    public boolean hasHandlerLevels() {
        return hasHandlerLevels;
    }

    @Override
    public List<HandlerFloor> handlerFloorsBelow(String loggerName, Level target) {
        if (target == null) {
            return List.of();
        }
        List<HandlerFloor> floors = new ArrayList<>();
        for (HandlerRef ref : handlersOnPath.getOrDefault(loggerName, List.of())) {
            Level current = handlerLevels.get(ref);
            if (current != null && current.compareTo(target) > 0) {
                floors.add(new HandlerFloor(ref, current));
            }
        }
        return List.copyOf(floors);
    }

    /**
     * Simulates a handler that no longer exists (context torn down, config
     * dropped it) -- persistently, unlike {@link #throwOnSetHandlerLevel},
     * since a genuinely vanished handler doesn't come back on the next call.
     */
    void vanishHandler(HandlerRef ref) {
        vanishedHandlers.add(ref);
        handlerLevels.remove(ref);
    }

    @Override
    public Optional<Level> handlerLevel(HandlerRef ref) {
        // Mirrors JulLoggingAdapter's real contract: handlerLevel never
        // throws for an unresolvable ref (vanished, or never registered) --
        // it just returns empty, same as "don't know". Only setHandlerLevel
        // throws UnknownHandlerException for that case.
        if (!isResolvable(ref)) {
            return Optional.empty();
        }
        return Optional.ofNullable(handlerLevels.get(ref));
    }

    @Override
    public Optional<Level> setHandlerLevel(HandlerRef ref, Level level) {
        if (!isResolvable(ref)) {
            throw new UnknownHandlerException(ref);
        }
        if (ref.equals(throwOnSetHandlerLevelFor)) {
            throwOnSetHandlerLevelFor = null; // one-shot
            throw new RuntimeException("simulated adapter failure for " + ref);
        }
        Level previous = handlerLevels.get(ref);
        if (level == null) {
            handlerLevels.remove(ref);
        } else {
            handlerLevels.put(ref, level);
        }
        return Optional.ofNullable(previous);
    }

    @Override
    public List<HandlerRef> knownHandlers() {
        List<HandlerRef> known = new ArrayList<>(registeredHandlers);
        known.removeAll(vanishedHandlers);
        return List.copyOf(known);
    }

    /** Whether {@code ref} resolves to a live handler in this fake -- registered, and never vanished. */
    private boolean isResolvable(HandlerRef ref) {
        return registeredHandlers.contains(ref) && !vanishedHandlers.contains(ref);
    }
}
