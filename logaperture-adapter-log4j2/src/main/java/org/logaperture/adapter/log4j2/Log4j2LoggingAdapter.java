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
package org.logaperture.adapter.log4j2;

import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractOutputStreamAppender;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.appender.FileAppender;
import org.apache.logging.log4j.core.appender.MemoryMappedFileAppender;
import org.apache.logging.log4j.core.appender.RandomAccessFileAppender;
import org.apache.logging.log4j.core.appender.RollingFileAppender;
import org.apache.logging.log4j.core.appender.RollingRandomAccessFileAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.logaperture.api.BackendInfo;
import org.logaperture.api.DoctorFinding;
import org.logaperture.api.HandlerDiagnostics;
import org.logaperture.api.HandlerFloor;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.Severity;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.RuleGate;
import org.logaperture.core.StormObserver;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.UnknownHandlerException;



import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Levels on one Log4j 2 {@link LoggerContext} (doc/specs/log4j2-adapter.md A2, A8). Works on the
 * context it holds, never through {@code Configurator}'s static helpers, which find their context
 * from the caller's class loader (spike SB3).
 *
 * <p>A native level the API cannot name ({@code FATAL}, a custom level) reads back as the nearest
 * one, so a reset that writes the captured baseline back turns a declared {@code fatal} into
 * {@code error}. The adapter cannot do better alone: a reset and a deliberate {@code set logger x
 * error} reach {@link #applyLevel} as the same call. Exact restoration needs the baseline to carry
 * the native level (#312).
 *
 * <p>Reconfiguration (spec "Scope" 3). Log4j fires a {@code "config"} property event from the context
 * on a configuration swap ({@code old != new}) and from <em>every</em> {@code updateLoggers()}
 * ({@code old == new}, spike R0). A swap wipes levels, so it runs the {@link #onReset} listener (a full
 * re-apply); an {@code old == new} event from anyone else's {@code updateLoggers()} is a native level
 * change (Spring's {@code logging.level}, Actuator) and runs the {@link #onNativeChange} listener, which
 * compares active overrides with the levels now in force. Events this adapter causes itself, and events
 * once the context is stopping (SB10), are ignored, so nothing recurses. No assumption is made that the
 * swap event comes before the {@code old == new} one that precedes it (SB5): both listeners are
 * idempotent.
 *
 * <p>Handler levels (A5, Slice 6) are the {@code AppenderRef} level of every reference to an appender, in
 * every {@code LoggerConfig}; see {@link HandlerLevels} for why the adapter keeps them.
 */
final class Log4j2LoggingAdapter implements LoggingAdapter {

    static final String ROOT_ALIAS = "ROOT";
    private static final String LOG4J_ROOT = "";

    private final LoggerContext context;
    private final String version;
    /**
     * The {@code LoggerConfig}s {@link #applyLevel} created where none was (A8), by name. Held as the
     * object, not just the name: after a reconfiguration the name may belong to a config the
     * application declared, and that one is never removed.
     */
    private final Map<String, LoggerConfig> createdByLogAperture = new ConcurrentHashMap<>();
    /**
     * Names LogAperture created a {@code LoggerConfig} for on a configuration that has since been
     * replaced. The name may now be a config the application declared, and a reset must leave that
     * alone, not blank its level.
     */
    private final Set<String> createdOnAGoneConfiguration = ConcurrentHashMap.newKeySet();

    /**
     * The configuration the created-config map belongs to. A swap is {@code old != new}, but the first
     * event after one is an {@code old == new} event already on the new configuration (SB5), where
     * {@code onNativeChange} may create {@code LoggerConfig}s before the swap event arrives. Keying the
     * map by configuration identity, not by "a swap event was seen", keeps those.
     */
    private volatile Configuration trackedConfiguration;
    /**
     * For a name in {@link #createdOnAGoneConfiguration} that an override has since been written onto
     * (the application declares it in the new configuration): the level the application declared there,
     * which a reset restores. Without it the reset would leave the override's level in force.
     */
    private final Map<String, Optional<org.apache.logging.log4j.Level>> declaredBeforeOverride =
            new ConcurrentHashMap<>();
    private final TrimAppender.Shared trimShared = new TrimAppender.Shared();
    private final HandlerLevels handlerLevels = new HandlerLevels();
    private String trimProblem;
    private boolean trimProbed;
    /** Asked once per adapter; replaceable so a test can count the asks. */
    java.util.function.Function<Configuration, String> trimProbe = TrimProbe::problem;
    /** The gate: one filter for drop rules and storm detection, attached to the live configuration. */
    private final GateFilter gateFilter = new GateFilter();
    /** The configuration {@link #gateFilter} is attached to; a swap leaves it behind and it is attached again. */
    private volatile WeakReference<Configuration> gateAttachedTo;
    private final AtomicReference<Runnable> resetListener = new AtomicReference<>();
    private final AtomicReference<Runnable> nativeChangeListener = new AtomicReference<>();
    private final PropertyChangeListener configListener = this::onConfigEvent;
    private boolean listening;
    /** Set on the thread that is inside this adapter's own {@code updateLoggers()} or a listener it called. */
    private final ThreadLocal<Boolean> inside = new ThreadLocal<>();

    Log4j2LoggingAdapter(LoggerContext context, String version) {
        this.context = context;
        this.version = version;
        this.trackedConfiguration = context.getConfiguration();
    }

    @Override
    public synchronized void onReset(Runnable listener) {
        resetListener.set(listener);
        startListening();
    }

    @Override
    public synchronized void clearResetListener() {
        resetListener.set(null);
        stopListeningIfIdle();
    }

    @Override
    public synchronized void onNativeChange(Runnable listener) {
        nativeChangeListener.set(listener);
        startListening();
    }

    @Override
    public synchronized void clearNativeChangeListener() {
        nativeChangeListener.set(null);
        stopListeningIfIdle();
    }

    @Override
    public void installRulePipeline(RuleGate gate) {
        gateFilter.setGate(gate);
        attachGate();
    }

    /**
     * Trim wrapping (A4). Off, with the reason kept, when this Log4j cannot render a trimmed exception
     * ({@link TrimProbe}); otherwise a wrapper goes into every text appender's slot, on the live
     * configuration, again after each swap (core calls this from its reset hook) and idempotently.
     */
    @Override
    public synchronized void installTrimRendering(RuleGate gate) {
        trimShared.gate = gate;
        if (!trimProbed) {
            trimProblem = trimProbe.apply(context.getConfiguration());
            trimProbed = true;
            if (trimProblem != null) {
                Diagnostics.warn("LogAperture cannot trim stack traces on this Log4j (" + trimProblem
                        + "); trim rules are counted but not applied");
            }
        }
        if (trimProbed && trimProblem != null) {
            return;
        }
        TrimSlots.wrapAll(context.getConfiguration(), trimShared, handlerLevels);
    }

    /** Why trim is off on this Log4j, or empty if it is on or has not been installed yet. */
    synchronized Optional<String> trimDisabledReason() {
        return trimProbed ? Optional.ofNullable(trimProblem) : Optional.empty();
    }

    @Override
    public void installStormDetection(StormObserver detector) {
        gateFilter.setStorm(detector);
        attachGate();
    }

    /**
     * Puts the gate on the live configuration if it is not there: a reconfiguration drops it with the
     * rest of the old configuration, and core calls the installs again from its reset hook. Idempotent.
     */
    private synchronized void attachGate() {
        Configuration live = context.getConfiguration();
        WeakReference<Configuration> attached = gateAttachedTo;
        if (attached != null && attached.get() == live) {
            return;
        }
        gateFilter.start();
        live.removeFilter(gateFilter); // idempotent: this configuration may already carry it (A to B and back to A)
        live.addFilter(gateFilter);
        gateAttachedTo = new WeakReference<>(live); // weak: a replaced configuration must not be pinned
    }

    /** Whether this adapter controls {@code other}. */
    boolean isFor(org.apache.logging.log4j.spi.LoggerContext other) {
        return context == other;
    }

    /** Lets go of the context: removes the event listener and both callbacks (spec "Scope" 7). */
    synchronized void detach() {
        gateFilter.release();
        trimShared.gate = null; // a wrapper still in flight passes its event straight through
        if (context.isStarted()) {
            try {
                TrimSlots.unwrapAll(context.getConfiguration(), trimShared, handlerLevels); // a stopping context takes them with it
            } catch (RuntimeException e) {
                Diagnostics.warn("LogAperture could not take its trim wrappers out of the Log4j configuration: " + e);
            }
        }
        WeakReference<Configuration> attached = gateAttachedTo;
        Configuration old = attached == null ? null : attached.get();
        if (old != null) {
            old.removeFilter(gateFilter);
        }
        context.getConfiguration().removeFilter(gateFilter); // and wherever it was put since
        gateAttachedTo = null;
        resetListener.set(null);
        nativeChangeListener.set(null);
        stopListeningIfIdle();
    }

    /**
     * The created {@code LoggerConfig}s belong to one configuration; when it is no longer the live one
     * they are gone. Their names move to "created on a gone configuration", pruned to the names the live
     * configuration declares (only those can need protecting). Returns whether the live configuration is
     * a different one from the one last seen.
     */
    private synchronized boolean forgetCreatedConfigsOfAGoneConfiguration() {
        Configuration live = context.getConfiguration();
        if (trackedConfiguration == live) {
            return false;
        }
        createdOnAGoneConfiguration.addAll(createdByLogAperture.keySet());
        createdByLogAperture.clear();
        Map<String, LoggerConfig> declared = live.getLoggers();
        createdOnAGoneConfiguration.removeIf(name -> !declared.containsKey(name));
        declaredBeforeOverride.clear(); // re-recorded against this configuration when an override is written
        trackedConfiguration = live;
        return true;
    }

    private void startListening() {
        if (!listening) {
            forgetCreatedConfigsOfAGoneConfiguration(); // "last seen" starts at the configuration now live
            context.addPropertyChangeListener(configListener);
            listening = true;
        }
    }

    private void stopListeningIfIdle() {
        if (listening && resetListener.get() == null && nativeChangeListener.get() == null) {
            context.removePropertyChangeListener(configListener);
            listening = false;
        }
    }

    private void onConfigEvent(PropertyChangeEvent event) {
        if (!LoggerContext.PROPERTY_CONFIG.equals(event.getPropertyName())) {
            return;
        }
        if (inside.get() != null || !context.isStarted()) {
            return; // our own updateLoggers() or a listener we ran (R0), or a context that is stopping (SB10)
        }
        boolean swap = event.getOldValue() != event.getNewValue();
        boolean configurationChanged = forgetCreatedConfigsOfAGoneConfiguration();
        if (!swap && configurationChanged) {
            // SB5: the first event after a swap is an old == new one, on the new configuration, fired
            // before the swap event. The swap event runs the full re-apply; running the override check
            // here as well would put every override back twice and audit a reload as native drift.
            return;
        }
        Runnable listener = swap ? resetListener.get() : nativeChangeListener.get();
        if (listener == null) {
            return;
        }
        inside.set(Boolean.TRUE);
        try {
            listener.run();
        } catch (RuntimeException t) {
            // Never into Log4j's own reconfiguration (§9): a failed re-apply is a line, not a crash.
            // An Error (out of memory, stack overflow) is not ours to swallow.
            Diagnostics.warn("LogAperture could not re-apply after a Log4j "
                    + (swap ? "reconfiguration" : "level change") + ", continuing", t);
        } finally {
            inside.remove();
        }
    }

    @Override
    public List<String> knownLoggerNames() {
        // Two different sets, both needed (spike L1): instantiated loggers, and declared LoggerConfigs.
        Set<String> names = new LinkedHashSet<>();
        names.add(ROOT_ALIAS);
        for (org.apache.logging.log4j.core.Logger logger : context.getLoggers()) {
            names.add(alias(logger.getName()));
        }
        names.addAll(aliasAll(context.getConfiguration().getLoggers().keySet()));
        return new ArrayList<>(names);
    }

    @Override
    public Optional<Level> configuredLevel(String loggerName) {
        Configuration config = context.getConfiguration();
        String name = nativeName(loggerName);
        LoggerConfig own = config.getLoggers().get(name);
        if (own == null && !name.isEmpty()) {
            return Optional.empty(); // no LoggerConfig of its own: inherits (spike L2)
        }
        LoggerConfig target = own != null ? own : config.getRootLogger();
        return Optional.ofNullable(LevelMapper.toApi(target.getExplicitLevel()));
    }

    @Override
    public Level effectiveLevel(String loggerName) {
        LoggerConfig config = context.getConfiguration().getLoggerConfig(nativeName(loggerName));
        return LevelMapper.toApi(config.getLevel());
    }

    @Override
    public synchronized void applyLevel(String loggerName, Level level) {
        forgetCreatedConfigsOfAGoneConfiguration();
        Configuration config = context.getConfiguration();
        String name = nativeName(loggerName);
        LoggerConfig own = config.getLoggers().get(name);
        if (level == null) {
            clear(config, name, own);
        } else {
            set(config, name, own, LevelMapper.toLog4j(level));
        }
        updateLoggersQuietly();
    }

    /** {@code updateLoggers()} fires a {@code "config"} event; this adapter's own must not call it back (R0). */
    private void updateLoggersQuietly() {
        Boolean outer = inside.get();
        inside.set(Boolean.TRUE);
        try {
            context.updateLoggers();
        } finally {
            if (outer == null) {
                inside.remove();
            }
        }
    }

    private void set(Configuration config, String name, LoggerConfig own, org.apache.logging.log4j.Level level) {
        if (name.isEmpty()) {
            config.getRootLogger().setLevel(level);
        } else if (own != null) {
            if (createdOnAGoneConfiguration.contains(name)) {
                // The application declares this name in the configuration that replaced ours: remember its
                // level before the override replaces it, so a reset can give it back.
                declaredBeforeOverride.putIfAbsent(name, Optional.ofNullable(own.getExplicitLevel()));
            }
            own.setLevel(level);
        } else {
            // A name no LoggerConfig has: pre-set one of its own, additive, no appenders (A8, L3b).
            LoggerConfig created = new LoggerConfig(name, level, true);
            config.addLogger(name, created);
            createdByLogAperture.put(name, created);
        }
    }

    private void clear(Configuration config, String name, LoggerConfig own) {
        if (name.isEmpty()) {
            return; // the root has nothing to inherit from: leave it
        }
        LoggerConfig mine = createdByLogAperture.remove(name);
        boolean createdOnGone = createdOnAGoneConfiguration.remove(name);
        Optional<org.apache.logging.log4j.Level> declared = declaredBeforeOverride.remove(name);
        if (own == null) {
            return;
        }
        if (mine == own) {
            config.removeLogger(name); // reset removes the LoggerConfig it created (L3c)
        } else if (declared != null) {
            own.setLevel(declared.orElse(null)); // the application's own level, from before the override
        } else if (!createdOnGone) {
            own.setLevel(null); // a declared, level-less config inherits again (L3e)
        }
        // else: the config LogAperture created went with a reconfiguration, this one is the
        // application's own, and no override was written onto it: leave its level alone.
    }

    @Override
    public boolean hasHandlerLevels() {
        return true;
    }

    /** The appenders some {@code LoggerConfig} references, by their {@code log4j2.xml} name (A5, S6-1). */
    @Override
    public synchronized List<HandlerRef> realHandlers() {
        Set<String> names = new LinkedHashSet<>();
        for (LoggerConfig loggerConfig : HandlerLevels.loggerConfigs(context.getConfiguration())) {
            for (AppenderRef reference : liveReferences(loggerConfig)) {
                names.add(reference.getRef());
            }
        }
        List<HandlerRef> refs = new ArrayList<>(names.size());
        for (String name : names) {
            refs.add(new HandlerRef(name));
        }
        return List.copyOf(refs);
    }

    @Override
    public synchronized List<HandlerRef> knownHandlers() {
        List<HandlerRef> combined = new ArrayList<>(realHandlers());
        combined.add(HandlerRef.ALL_HANDLERS);
        return List.copyOf(combined);
    }

    @Override
    public synchronized List<HandlerRef> handlersOnRoot() {
        return handlersOn(context.getConfiguration().getRootLogger());
    }

    @Override
    public synchronized List<HandlerRef> handlersOnPath(String loggerName) {
        List<HandlerRef> refs = new ArrayList<>();
        for (LoggerConfig current : path(loggerName)) {
            for (HandlerRef ref : handlersOn(current)) {
                if (!refs.contains(ref)) {
                    refs.add(ref);
                }
            }
        }
        return List.copyOf(refs);
    }

    @Override
    public synchronized List<HandlerFloor> handlerFloorsBelow(String loggerName, Level target) {
        if (target == null) {
            return List.of(); // "back to inherited" is not a raise
        }
        Map<String, Level> strictest = new LinkedHashMap<>();
        for (LoggerConfig current : path(loggerName)) {
            for (AppenderRef reference : liveReferences(current)) {
                Level level = LevelMapper.toApi(handlerLevels.current(current, reference));
                if (level != null && level.compareTo(target) > 0) {
                    strictest.merge(reference.getRef(), level, (a, b) -> a.compareTo(b) >= 0 ? a : b);
                }
            }
        }
        List<HandlerFloor> floors = new ArrayList<>(strictest.size());
        strictest.forEach((name, level) -> floors.add(new HandlerFloor(new HandlerRef(name), level)));
        return List.copyOf(floors);
    }

    @Override
    public synchronized Optional<Level> handlerLevel(HandlerRef ref) {
        return Optional.ofNullable(mostRestrictive(slotsOf(context.getConfiguration(), ref.value())));
    }

    /**
     * Sets the level on every reference to the appender, in every {@code LoggerConfig} (A5). A swap, as for
     * trim: a call in the window is not written to this appender, and the appender goes last in the logger's
     * list. A reference already at the level is left
     * alone (no swap, so no lost call and no reordering). {@code null} gives each reference back the level the
     * file declared. If a swap fails the others are still tried and the call then throws: a level that reached
     * only some references must not be reported as set.
     */
    @Override
    public synchronized Optional<Level> setHandlerLevel(HandlerRef ref, Level level) {
        String name = ref.value();
        List<Slot> slots = slotsOf(context.getConfiguration(), name);
        if (slots.isEmpty()) {
            throw new UnknownHandlerException(ref);
        }
        Level previous = mostRestrictive(slots);
        org.apache.logging.log4j.Level wanted = level == null ? null : LevelMapper.toLog4j(level);
        List<Slot> changed = new ArrayList<>();
        List<org.apache.logging.log4j.Level> changedFrom = new ArrayList<>();
        List<org.apache.logging.log4j.Level> recordedBefore = new ArrayList<>();
        try {
            for (Slot slot : slots) {
                org.apache.logging.log4j.Level now = handlerLevels.current(slot.loggerConfig(), slot.reference());
                org.apache.logging.log4j.Level recorded = handlerLevels.recorded(slot.loggerConfig(), name);
                org.apache.logging.log4j.Level newLevel = wanted != null ? wanted : slot.reference().getLevel();
                if (sameLevel(now, newLevel)) {
                    continue;
                }
                if (!swapLevel(slot, name, newLevel, now)) {
                    throw new IllegalStateException("could not change handler '" + name + "' in logger '"
                            + slot.loggerConfig().getName() + "'; see the preceding LogAperture warning");
                }
                changed.add(slot);
                changedFrom.add(now);
                recordedBefore.add(recorded);
                handlerLevels.record(slot.loggerConfig(), name, wanted);
            }
        } catch (RuntimeException e) {
            rollBack(name, changed, changedFrom, recordedBefore); // all or nothing, whatever went wrong
            throw e;
        }
        for (Slot slot : slots) {
            handlerLevels.record(slot.loggerConfig(), name, wanted); // only once the whole call succeeded
        }
        return Optional.ofNullable(previous);
    }

    /** Swaps the slot's appender (the wrapper if there is one, never what is behind it) to {@code newLevel}. */
    private boolean swapLevel(Slot slot, String name, org.apache.logging.log4j.Level newLevel,
            org.apache.logging.log4j.Level restoreLevel) {
        Appender current = slot.loggerConfig().getAppenders().get(name);
        if (current == null) {
            return false; // taken out by a reconfiguration since the slots were read
        }
        return TrimSlots.swap(slot.loggerConfig(), slot.reference(), current, current, newLevel, restoreLevel);
    }

    /** A swap failed part-way: the references already changed go back, so the call changes all or nothing. */
    private void rollBack(String name, List<Slot> changed, List<org.apache.logging.log4j.Level> from,
            List<org.apache.logging.log4j.Level> recordedBefore) {
        for (int i = 0; i < changed.size(); i++) {
            Slot slot = changed.get(i);
            if (swapLevel(slot, name, from.get(i), handlerLevels.current(slot.loggerConfig(), slot.reference()))) {
                handlerLevels.record(slot.loggerConfig(), name, recordedBefore.get(i));
            }
        }
    }

    /** No level and {@code ALL} filter nothing, so they are the same level. */
    private static boolean sameLevel(org.apache.logging.log4j.Level a, org.apache.logging.log4j.Level b) {
        int left = a == null ? org.apache.logging.log4j.Level.ALL.intLevel() : a.intLevel();
        int right = b == null ? org.apache.logging.log4j.Level.ALL.intLevel() : b.intLevel();
        return left == right;
    }

    /**
     * The most restrictive level among the appender's references; {@code ALL} when none has one (nothing is
     * filtered, which is what the JUL handler default says too), and null only for a name no
     * {@code LoggerConfig} references. Never empty for a live handler: core captures a baseline only from a
     * level that is present, and a reset needs one.
     */
    private Level mostRestrictive(List<Slot> slots) {
        if (slots.isEmpty()) {
            return null;
        }
        Level strictest = null;
        for (Slot slot : slots) {
            Level level = LevelMapper.toApi(handlerLevels.current(slot.loggerConfig(), slot.reference()));
            if (level != null && (strictest == null || level.compareTo(strictest) > 0)) {
                strictest = level;
            }
        }
        return strictest == null ? Level.ALL : strictest;
    }

    /** One reference to an appender in one {@code LoggerConfig}. */
    private record Slot(LoggerConfig loggerConfig, AppenderRef reference) {
    }

    /** Every live reference to the named appender, in every {@code LoggerConfig}. */
    private static List<Slot> slotsOf(Configuration config, String name) {
        List<Slot> slots = new ArrayList<>();
        for (LoggerConfig loggerConfig : HandlerLevels.loggerConfigs(config)) {
            for (AppenderRef reference : liveReferences(loggerConfig)) {
                if (name.equals(reference.getRef())) {
                    slots.add(new Slot(loggerConfig, reference));
                    break;
                }
            }
        }
        return slots;
    }

    /** The {@code LoggerConfig}'s references whose appender is live in it (the one place that rule is written). */
    private static List<AppenderRef> liveReferences(LoggerConfig loggerConfig) {
        List<AppenderRef> live = new ArrayList<>();
        for (AppenderRef reference : loggerConfig.getAppenderRefs()) {
            if (loggerConfig.getAppenders().containsKey(reference.getRef())) {
                live.add(reference);
            }
        }
        return live;
    }

    private static List<HandlerRef> handlersOn(LoggerConfig loggerConfig) {
        List<HandlerRef> refs = new ArrayList<>();
        for (AppenderRef reference : liveReferences(loggerConfig)) {
            refs.add(new HandlerRef(reference.getRef()));
        }
        return refs;
    }

    /** The {@code LoggerConfig}s an event from the logger passes through, nearest first, ending where additivity does. */
    private List<LoggerConfig> path(String loggerName) {
        List<LoggerConfig> path = new ArrayList<>();
        for (LoggerConfig current = context.getConfiguration().getLoggerConfig(nativeName(loggerName));
                current != null; current = current.getParent()) {
            path.add(current);
            if (!current.isAdditive()) {
                break;
            }
        }
        return path;
    }

    /**
     * The file facts of an appender (A9, Slice 7 D7-1 to D7-3). The original in {@code config.getAppenders()},
     * never the trim wrapper in the slot. A plain file appender has a known path and no caps; a rolling one is
     * persistent with its file unknown (its policy is not stable API, A9), so the file checks leave it alone as
     * they do the JDK {@code FileHandler}; an {@code <Async>} over exactly one appender answers with that one's
     * facts, flush excepted (it flushes per batch); anything else gives none.
     */
    @Override
    public synchronized HandlerDiagnostics handlerDiagnostics(HandlerRef ref) {
        Appender appender = context.getConfiguration().getAppenders().get(ref.value());
        if (appender == null) {
            return HandlerDiagnostics.EMPTY;
        }
        if (appender instanceof AsyncAppender async) {
            String[] behind = async.getAppenderRefStrings();
            Appender inner = behind.length == 1 ? context.getConfiguration().getAppender(behind[0]) : null;
            if (inner == null || inner instanceof AsyncAppender) {
                return HandlerDiagnostics.EMPTY;
            }
            HandlerDiagnostics facts = diagnosticsOf(inner);
            return new HandlerDiagnostics(null, null, null, facts.targetPath(), facts.isConsole(), false,
                    facts.fileWithoutPath());
        }
        return diagnosticsOf(appender);
    }

    private static HandlerDiagnostics diagnosticsOf(Appender appender) {
        if (appender instanceof ConsoleAppender) {
            return new HandlerDiagnostics(null, null, null, null, true);
        }
        if (appender instanceof RollingFileAppender || appender instanceof RollingRandomAccessFileAppender) {
            return new HandlerDiagnostics(null, null, immediateFlush(appender), null, false, false, true);
        }
        String fileName = appender instanceof FileAppender file ? file.getFileName()
                : appender instanceof RandomAccessFileAppender random ? random.getFileName()
                : appender instanceof MemoryMappedFileAppender mapped ? mapped.getFileName() : null;
        if (fileName == null) {
            return HandlerDiagnostics.EMPTY;
        }
        Path path = pathOrNull(fileName);
        return path == null ? HandlerDiagnostics.EMPTY
                : new HandlerDiagnostics(null, null, immediateFlush(appender), path);
    }

    /** The path, or null for a name this platform rejects: no facts, never a failed {@code doctor} run. */
    static Path pathOrNull(String fileName) {
        try {
            return Path.of(fileName);
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** {@code immediateFlush} of a file appender (a memory-mapped one reports its own, always false). */
    private static Boolean immediateFlush(Appender appender) {
        return appender instanceof AbstractOutputStreamAppender<?> stream ? stream.getImmediateFlush() : null;
    }

    /**
     * Trim is off on this Log4j (A4, A12 {@code log4j2.trim}): {@code INFO}, because the adapter cannot tell
     * whether anyone has a trim rule (D7-5). Nothing before the probe has run.
     */
    @Override
    public List<DoctorFinding> doctorFindings() {
        return trimDisabledReason().map(reason -> new DoctorFinding("log4j2.trim", Severity.INFO, "trim",
                "trim rules are counted but not applied on this Log4j" + (version == null ? "" : " (" + version + ")")
                        + ".", reason,
                "use a Log4j that renders a trimmed exception: 2.24.x and earlier, or 2.25.4 and later.")).stream()
                .toList();
    }

    @Override
    public BackendInfo backendInfo() {
        return new BackendInfo("Log4j 2", version);
    }

    private static String alias(String log4jName) {
        return log4jName == null || log4jName.equals(LOG4J_ROOT) ? ROOT_ALIAS : log4jName;
    }

    private static List<String> aliasAll(Set<String> names) {
        List<String> out = new ArrayList<>(names.size());
        for (String name : names) {
            out.add(alias(name));
        }
        return out;
    }

    private static String nativeName(String loggerName) {
        return ROOT_ALIAS.equals(loggerName) ? LOG4J_ROOT : loggerName;
    }
}
