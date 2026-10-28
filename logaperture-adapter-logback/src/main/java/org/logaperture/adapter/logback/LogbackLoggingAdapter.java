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
package org.logaperture.adapter.logback;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggerContextListener;
import org.logaperture.api.BackendInfo;
import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link LoggingAdapter} implementation over a real {@code LoggerContext}.
 * Method bodies map 1:1 onto the API shapes the M0 spike validated (see
 * doc/spikes/m0-adapter-grid.md point 1): {@code getLoggerList()}, {@code
 * getLogger(name).setLevel()}, {@code getLevel()} null-vs-explicit, {@code
 * getEffectiveLevel()}.
 */
public final class LogbackLoggingAdapter implements LoggingAdapter {

    private final LoggerContext context;
    private final boolean configurationEvents;
    private final AtomicReference<Registration> registered = new AtomicReference<>();

    /** Package-visible: tests construct against a fresh, throwaway {@code LoggerContext} directly. */
    LogbackLoggingAdapter(LoggerContext context) {
        this(context, ConfigurationEndedHook.isSupported(context));
    }

    /**
     * {@code configurationEvents} false forces the reset-listener fallback {@link #onReset} uses
     * on Logback without configuration events, so tests on the 1.5.x the build compiles against
     * can still exercise it.
     */
    LogbackLoggingAdapter(LoggerContext context, boolean configurationEvents) {
        this.context = Objects.requireNonNull(context, "context");
        this.configurationEvents = configurationEvents;
    }

    /**
     * doc/specs/environment-report.md "Adapter / container SPI" (issue #283). The version is
     * the Logback jar's manifest {@code Implementation-Version}, read off {@code LoggerContext}
     * as the class loader resolved it, so it names the Logback the application runs, not one
     * this adapter was compiled against, and not a context subclass's own jar; left out when the class has no package info (an unusual classloader setup).
     */
    @Override
    public BackendInfo backendInfo() {
        Package pkg = LoggerContext.class.getPackage();
        return new BackendInfo("Logback", pkg == null ? null : pkg.getImplementationVersion());
    }

    @Override
    public List<String> knownLoggerNames() {
        return context.getLoggerList().stream().map(Logger::getName).toList();
    }

    @Override
    public Optional<Level> configuredLevel(String loggerName) {
        // getLogger(name) creates the Logback Logger as a side effect if it
        // doesn't exist yet -- this is exactly the "Known" state
        // doc/specs/level-control.md §8.5 describes, and it is Logback's
        // own native behavior, not something this adapter has to fake.
        return Optional.ofNullable(LevelMapper.toApi(context.getLogger(loggerName).getLevel()));
    }

    @Override
    public Level effectiveLevel(String loggerName) {
        return LevelMapper.toApi(context.getLogger(loggerName).getEffectiveLevel());
    }

    @Override
    public void applyLevel(String loggerName, Level level) {
        context.getLogger(loggerName).setLevel(level == null ? null : LevelMapper.toLogback(level));
    }

    /**
     * Runs {@code listener} after each reconfiguration -- doc/specs/persistence.md
     * "Reconfiguration re-application", issue #231. Two mechanisms, chosen once per context:
     *
     * <ul>
     *   <li><b>Configuration ended</b>, where Logback has {@code ConfigurationEventListener}
     *       (1.3.14+, 1.4.14+, 1.5.x): {@link ConfigurationEndedHook} fires after Joran has
     *       applied the file, so a level the file sets doesn't overwrite the re-applied override,
     *       and the appenders it creates already exist. A bare {@code context.reset()} with no
     *       configuration after it fires nothing; the verification sweep covers that.</li>
     *   <li><b>Reset</b>, on older Logback: a {@link LoggerContextListener} on {@code onReset}.
     *       It has to be reset-resistant, or Logback removes it at the end of the first reset
     *       that fires it ({@code resetListenersExceptResetResistant}). It runs before Joran, so
     *       a level the file sets still wins until the next sweep.</li>
     * </ul>
     *
     * <p>Replaces, rather than accumulates alongside, any listener already registered by an
     * earlier call on this same instance -- matching the "at most one listener" contract {@link
     * LoggingAdapter#onReset} documents. {@link #clearResetListener()} handles the other half:
     * unregistering across separate adapter instances that all wrap the same shared static {@code
     * context} (e.g. successive {@code NoneContainer.install()} calls in one JVM).
     */
    @Override
    public void onReset(Runnable listener) {
        Registration registration = configurationEvents
                ? ConfigurationEndedHook.register(context, listener)
                : registerResetListener(listener);
        Registration previous = registered.getAndSet(registration);
        if (previous != null) {
            previous.remove();
        }
    }

    private Registration registerResetListener(Runnable listener) {
        LoggerContextListener wrapper = new LoggerContextListener() {
            @Override
            public boolean isResetResistant() {
                return true;
            }

            @Override
            public void onStart(LoggerContext context) {
                // not of interest to this adapter
            }

            @Override
            public void onReset(LoggerContext context) {
                listener.run();
            }

            @Override
            public void onStop(LoggerContext context) {
                // not of interest to this adapter
            }

            @Override
            public void onLevelChange(Logger logger, ch.qos.logback.classic.Level level) {
                // not of interest to this adapter
            }
        };
        context.addListener(wrapper);
        return () -> context.removeListener(wrapper);
    }

    @Override
    public void clearResetListener() {
        Registration previous = registered.getAndSet(null);
        if (previous != null) {
            previous.remove();
        }
    }

    /** Undoes one {@link #onReset} registration, whichever mechanism made it. */
    @FunctionalInterface
    interface Registration {
        void remove();
    }
}
