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

import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.logaperture.api.BackendInfo;
import org.logaperture.api.Level;
import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.RuleGate;
import org.logaperture.core.StormObserver;
import org.logaperture.core.spi.LoggingAdapter;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
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
 * <p>Not in this class yet: handler levels, the gate and trim.
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
    /** The gate: one filter for drop rules and storm detection, attached to the live configuration. */
    private final GateFilter gateFilter = new GateFilter();
    /** The configuration {@link #gateFilter} is attached to; a swap leaves it behind and it is attached again. */
    private volatile Configuration gateAttachedTo;
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
        if (gateAttachedTo == live) {
            return;
        }
        gateFilter.start();
        live.addFilter(gateFilter);
        gateAttachedTo = live;
    }

    /** Whether this adapter controls {@code other}. */
    boolean isFor(org.apache.logging.log4j.spi.LoggerContext other) {
        return context == other;
    }

    /** Lets go of the context: removes the event listener and both callbacks (spec "Scope" 7). */
    synchronized void detach() {
        gateFilter.release();
        Configuration attached = gateAttachedTo;
        if (attached != null) {
            attached.removeFilter(gateFilter);
        }
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
