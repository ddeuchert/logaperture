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
package org.logaperture.adapter.jul;

import org.logaperture.api.HandlerFloor;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.UnknownHandlerException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Handler;
import java.util.logging.LogManager;
import java.util.logging.Logger;

/**
 * {@link LoggingAdapter} over {@code java.util.logging} — see
 * doc/specs/wildfly-support.md, Slice 2.
 *
 * <p><b>Why {@code java.util.logging} and not {@code org.jboss.logmanager}.</b>
 * JBoss LogManager installs itself as the {@code java.util.logging.LogManager}
 * singleton; its logger nodes — the ones WildFly's handlers hang off and
 * that gate {@code server.log} — are {@code java.util.logging.Logger}
 * subclasses. Everything level control needs ({@code getLevel} /
 * {@code setLevel} / {@code getParent} / {@code getHandlers} /
 * {@code getUseParentHandlers}) is on the JDK base classes, which are on the
 * boot classpath and visible everywhere. So this adapter has <em>no</em>
 * compile-time reference to any {@code org.jboss.logmanager} class, and the
 * agent attaches to WildFly with a bare {@code -javaagent} — no
 * {@code -Xbootclasspath/a}, no {@code jboss.modules.system.pkgs}. The M0
 * spike validated this exact path on WildFly 26.1.3.Final by observing real
 * {@code server.log} output change after a {@code Logger.getLogger(name)
 * .setLevel(FINE)}. Handler <em>name</em> resolution ({@link
 * JbossHandlerNames}) is the one place this adapter reaches for {@code
 * org.jboss.logmanager} at all, and it does so reflectively, for the same
 * reason — see that class's doc.
 *
 * <p>It also works, unchanged, against the JDK's own default
 * {@code LogManager} (plain JUL apps).
 *
 * <p>Level read-back is lossy-but-defined (see {@link LevelMapper}); to keep
 * {@code resetLevel} exact anyway, this adapter privately retains the real
 * {@code java.util.logging.Level} it first observed for each logger and
 * restores <em>that</em> object when asked to apply a level that maps back
 * to it. It also keeps a strong reference to every {@code Logger} it
 * touches (JBoss LogManager weak-references facades by default; the strong
 * ref pins the node so an applied level is not reaped).
 *
 * <p>No reconfiguration hook here (§4.3) — the WildFly container's periodic
 * verification sweep, and its {@code LogManager} configuration-change
 * listener, own re-application. {@link #onReset}/{@link #clearResetListener}
 * keep the SPI's no-op default.
 */
public final class JulLoggingAdapter implements LoggingAdapter {

    /** How this adapter names the root logger externally (Logback's convention). */
    static final String ROOT_ALIAS = "ROOT";
    private static final String JUL_ROOT_NAME = "";

    private final ConcurrentHashMap<String, Logger> loggers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Optional<java.util.logging.Level>> capturedOriginals =
            new ConcurrentHashMap<>();
    /** Handlers this adapter has resolved a {@link HandlerRef} for, keyed by that ref -- {@link #setHandlerLevel} needs it back. */
    private final ConcurrentHashMap<HandlerRef, Handler> handlersByRef = new ConcurrentHashMap<>();

    /** Package-visible: constructed by {@link JulAdapterFactory}. */
    JulLoggingAdapter() {
    }

    @Override
    public List<String> knownLoggerNames() {
        List<String> names = new ArrayList<>();
        boolean sawRoot = false;
        for (String name : Collections.list(LogManager.getLogManager().getLoggerNames())) {
            if (name.equals(JUL_ROOT_NAME)) {
                names.add(ROOT_ALIAS);
                sawRoot = true;
            } else {
                names.add(name);
            }
        }
        if (!sawRoot) {
            names.add(0, ROOT_ALIAS); // the root logger always exists; surface it like the Logback adapter
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
        return Level.INFO; // no ancestor carries an explicit level -- JUL's own effective default
    }

    @Override
    public void applyLevel(String loggerName, Level level) {
        String resolved = resolveName(loggerName);
        Logger logger = logger(loggerName);
        logger.setLevel(resolveTarget(resolved, level));
        // Handler-floor detection stays in handlerFloorsBelow(); the warning
        // itself is core's job now (doc/specs/handler-floor-control.md
        // "Warning on level commands"), not this adapter's -- it has strictly
        // less information (no resolved HandlerRef, no --quiet/--json).
    }

    // onReset / clearResetListener: SPI no-op default -- see class doc.

    @Override
    public boolean hasHandlerLevels() {
        return true;
    }

    /**
     * The handlers on {@code loggerName}'s path to the root whose own level
     * is stricter than {@code target} — the second, independent gate
     * (doc/specs/wildfly-support.md, "Handler-level thresholds"). A {@code
     * null} {@code target} ("back to inherited") is not a raise, so it
     * yields an empty list.
     */
    @Override
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
                    floors.add(new HandlerFloor(refFor(handler), LevelMapper.toApi(handlerLevel)));
                }
            }
            if (!current.getUseParentHandlers()) {
                break; // records stop propagating upward here (JUL semantics)
            }
        }
        return List.copyOf(floors);
    }

    @Override
    public Optional<Level> handlerLevel(HandlerRef ref) {
        Handler handler = resolveHandler(ref);
        if (handler == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(LevelMapper.toApi(handler.getLevel()));
    }

    @Override
    public Optional<Level> setHandlerLevel(HandlerRef ref, Level level) {
        Handler handler = resolveHandler(ref);
        if (handler == null) {
            throw new UnknownHandlerException(ref);
        }
        Optional<Level> previous = Optional.ofNullable(LevelMapper.toApi(handler.getLevel()));
        handler.setLevel(level == null ? null : LevelMapper.toJul(level));
        return previous;
    }

    /**
     * Finds the live {@link Handler} {@code ref} names. {@code ref} usually
     * arrives fresh from a {@link #handlerFloorsBelow} call that just
     * populated {@link #handlersByRef} via {@link #refFor} — but {@code
     * setHandlerLevel}/{@code handlerLevel} must also work as the very first
     * call this adapter instance ever sees for that handler (a user typing
     * {@code logctl handler CONSOLE TRACE} cold, with no prior warning in
     * this session), when the cache is empty. In that case, fall back to
     * walking every known handler once — which populates the cache as a
     * side effect via {@link #refFor} — before giving up.
     */
    private Handler resolveHandler(HandlerRef ref) {
        Handler cached = handlersByRef.get(ref);
        if (cached != null) {
            return cached;
        }
        knownHandlers(); // side effect: resolves and caches every handler's ref
        return handlersByRef.get(ref);
    }

    @Override
    public List<HandlerRef> knownHandlers() {
        // Walk every known logger's own (non-inherited) handler list --
        // getHandlers() only returns handlers actually attached at that
        // node, so this naturally dedupes via refFor's identity map without
        // walking parent chains here.
        List<HandlerRef> refs = new ArrayList<>();
        for (String name : knownLoggerNames()) {
            for (Handler handler : logger(name).getHandlers()) {
                refs.add(refFor(handler));
            }
        }
        return List.copyOf(refs);
    }

    /**
     * Resolves (or reuses) the {@link HandlerRef} for {@code handler}: the
     * real {@code org.jboss.logmanager} configured name when {@link
     * JbossHandlerNames} can find one, else a stable identity-hash fallback
     * — doc/specs/handler-floor-control.md, Open decision #1.
     */
    private HandlerRef refFor(Handler handler) {
        Optional<String> configuredName = JbossHandlerNames.nameOf(logger(ROOT_ALIAS), handler);
        HandlerRef ref = configuredName.map(HandlerRef::new).orElseGet(() -> HandlerRef.anonymous(handler));
        handlersByRef.putIfAbsent(ref, handler);
        return ref;
    }

    /**
     * Resolves the root alias, materialises the logger via the JDK factory
     * (which registers it with the installed {@code LogManager} — JBoss
     * LogManager on WildFly, the JDK default elsewhere), and pins a strong
     * reference to it.
     */
    private Logger logger(String requestedName) {
        return loggers.computeIfAbsent(resolveName(requestedName), name -> {
            Logger materialised = Logger.getLogger(name);
            capturedOriginals.computeIfAbsent(name, n -> Optional.ofNullable(materialised.getLevel()));
            return materialised;
        });
    }

    private static String resolveName(String loggerName) {
        return ROOT_ALIAS.equals(loggerName) ? JUL_ROOT_NAME : loggerName;
    }

    private java.util.logging.Level resolveTarget(String resolvedName, Level level) {
        if (level == null) {
            return null; // back to inherited -- always exact
        }
        Optional<java.util.logging.Level> original = capturedOriginals.get(resolvedName);
        if (original != null && original.isPresent() && LevelMapper.toApi(original.get()) == level) {
            // Asked to apply exactly the level the captured baseline read
            // back as -- restore the real object (e.g. FINER, not FINEST).
            return original.get();
        }
        return LevelMapper.toJul(level);
    }
}
