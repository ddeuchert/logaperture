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

import org.logaperture.api.HandlerDiagnostics;
import org.logaperture.api.HandlerFloor;
import org.logaperture.api.HandlerRef;
import org.logaperture.api.Level;
import org.logaperture.api.LoggerByteCount;
import org.logaperture.core.spi.LoggingAdapter;
import org.logaperture.core.spi.UnknownHandlerException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Formatter;
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
 * .setLevel(FINE)}. {@link #isJBossLogManager()} is the one place this
 * adapter looks at {@code org.jboss.logmanager} at all, and it does so by
 * comparing a class name, not by reflecting into anything — the reflective
 * attempt at recovering WildFly's own configured handler names ({@code
 * JbossHandlerNames}, doc/specs/handler-floor-control.md's "Name resolution
 * against real WildFly: tried, doesn't work") never actually resolved a
 * name against real WildFly and was retired outright (issue #13, Decision
 * #8) in favor of the reserved {@link HandlerRef#ALL_HANDLERS} target.
 * Real per-handler names come back via an injected {@link
 * HandlerNameResolver} instead (issue #14) — this adapter stays generic
 * (the resolver, and its reflective read of WildFly's management model,
 * lives in {@code logaperture-container-wildfly}); with {@link
 * HandlerNameResolver#NONE} (plain JUL) every handler keeps its identity
 * token exactly as before.
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
    /** doc/specs/top.md -- shared across every {@link ByteCountingFormatter} this adapter installs. */
    private final TopCounters topCounters = new TopCounters();

    /**
     * doc/specs/handler-floor-control.md "WildFly handler name resolution"
     * (issue #14). {@link HandlerNameResolver#NONE} for plain JUL — every
     * handler then keeps its {@code <class>@<idhash>} identity token, exactly
     * as before this feature.
     */
    private final HandlerNameResolver nameResolver;

    /**
     * Each live {@link Handler} instance's {@link HandlerRef}, memoised for
     * this adapter's lifetime so a ref never changes under a captured
     * baseline/override (doc/specs/handler-floor-control.md "Ref stability
     * across a late resolution"). {@link Handler} has no {@code
     * equals}/{@code hashCode} override, so this keys by identity.
     */
    private final Map<Handler, HandlerRef> refByHandler = new ConcurrentHashMap<>();
    /** The subset of {@link #refByHandler} values that are identity-token fallbacks, not resolved names. */
    private final Set<HandlerRef> tokenRefs = ConcurrentHashMap.newKeySet();
    /** Resolved configured-name-by-instance, populated once {@link HandlerNameResolver#resolve} first returns non-empty. */
    private final Map<Handler, String> resolvedNames = new ConcurrentHashMap<>();

    private enum Resolution { PENDING, DONE, UNAVAILABLE }

    private volatile Resolution resolution = Resolution.PENDING;
    private final AtomicInteger emptyResolveAttempts = new AtomicInteger();
    private static final int MAX_RESOLVE_ATTEMPTS = 20;

    /** Package-visible: constructed by {@link JulAdapterFactory}. */
    JulLoggingAdapter() {
        this(HandlerNameResolver.NONE);
    }

    /** Package-visible: constructed by {@link JulAdapterFactory} on WildFly with a real resolver. */
    JulLoggingAdapter(HandlerNameResolver nameResolver) {
        this.nameResolver = Objects.requireNonNull(nameResolver, "nameResolver");
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
        ensureNamesResolved();
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
        if (floors.isEmpty()) {
            return List.copyOf(floors);
        }
        boolean anyTokenBlocker = floors.stream().anyMatch(f -> tokenRefs.contains(f.handlerRef()));
        if (!isJBossLogManager() || !anyTokenBlocker) {
            // Plain JUL, or every blocking handler resolved to a configured
            // name (issue #14) -- keep the per-handler warning, one actionable
            // `logctl handler <name>` command each.
            return List.copyOf(floors);
        }
        // At least one blocking handler still can't be named individually
        // (issue #13, Decision #7): collapse to a single HandlerFloor naming
        // ALL_HANDLERS, at the strictest (least verbose) level among the
        // actual blockers. Zero changes to core, the JMX surface, or Commands.
        Level strictest = floors.stream()
                .map(HandlerFloor::currentLevel)
                .max(Comparator.naturalOrder())
                .orElseThrow();
        return List.of(new HandlerFloor(HandlerRef.ALL_HANDLERS, strictest));
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
     * doc/specs/doctor.md "Adapter SPI". {@code ref} always arrives via
     * {@link #realHandlers}, which already populated {@link #handlersByRef}
     * as a side effect, so this resolves via cache without ever needing
     * {@code knownHandlers()}'s WildFly-collapsed fallback.
     */
    @Override
    public HandlerDiagnostics handlerDiagnostics(HandlerRef ref) {
        Handler handler = resolveHandler(ref);
        if (handler == null) {
            return HandlerDiagnostics.EMPTY;
        }
        return JulHandlerDiagnostics.of(handler);
    }

    /**
     * doc/specs/top.md "Adapter SPI". Wraps every persistent handler's
     * {@link Formatter} in a {@link ByteCountingFormatter} — a console-only
     * handler is skipped entirely (doc/specs/top.md Decision #1, same
     * persistent-handler signal {@link #handlerDiagnostics} already
     * exposes). Idempotent: a handler whose formatter is already a {@link
     * ByteCountingFormatter} is left alone, so a re-invocation (this
     * adapter's periodic re-verification standing in for a reconfiguration
     * event JUL doesn't have) never double-wraps or double-counts. A handler
     * the framework has silently replaced with a fresh instance, or handed a
     * fresh formatter, gets (re-)wrapped exactly like a handler seen for the
     * first time.
     */
    @Override
    public void installByteCounting() {
        for (HandlerRef ref : realHandlers()) {
            Handler handler = handlersByRef.get(ref);
            if (handler == null || !JulHandlerDiagnostics.of(handler).isPersistent()) {
                continue;
            }
            Formatter current = handler.getFormatter();
            if (current instanceof ByteCountingFormatter || current == null) {
                continue; // already wrapped, or nothing to wrap around
            }
            handler.setFormatter(new ByteCountingFormatter(current, topCounters));
        }
    }

    @Override
    public List<LoggerByteCount> byteCounts() {
        return topCounters.snapshot();
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
     * side effect via {@link #refFor} — before giving up. On WildFly the
     * walk goes through {@link #knownHandlers()} (not {@link #realHandlers()}):
     * a name that resolved (issue #14) is re-discoverable this way, a handler
     * still on an unstable identity token deliberately is not.
     */
    private Handler resolveHandler(HandlerRef ref) {
        Handler cached = handlersByRef.get(ref);
        if (cached != null) {
            return cached;
        }
        knownHandlers(); // side effect: resolves and caches every advertised handler's ref
        return handlersByRef.get(ref);
    }

    /**
     * Every handler currently <em>addressable</em> by a user (issue #13,
     * Decision #1). Plain JUL: every real handler, plus the reserved {@link
     * HandlerRef#ALL_HANDLERS} as one more valid name alongside them.
     * WildFly (JBoss LogManager): {@code ALL_HANDLERS} <em>plus</em> every
     * real handler whose configured name has resolved (issue #14, Decision
     * #2). A real handler still on an identity-token ref is <b>not</b>
     * advertised here — the token is unstable across a restart (issue #13's
     * whole reason for {@code ALL_HANDLERS}), so advertising it would
     * reintroduce the bug #13 fixed. It still exists and still gets mutated
     * via {@link #realHandlers()}, just isn't individually addressable.
     */
    @Override
    public List<HandlerRef> knownHandlers() {
        ensureNamesResolved();
        if (isJBossLogManager()) {
            List<HandlerRef> advertised = new ArrayList<>();
            advertised.add(HandlerRef.ALL_HANDLERS);
            for (HandlerRef ref : realHandlers()) {
                if (!tokenRefs.contains(ref)) {
                    advertised.add(ref);
                }
            }
            return List.copyOf(advertised);
        }
        List<HandlerRef> combined = new ArrayList<>(realHandlers());
        combined.add(HandlerRef.ALL_HANDLERS);
        return List.copyOf(combined);
    }

    /**
     * Every real handler this adapter can act on right now — what {@link
     * HandlerRef#ALL_HANDLERS} fans out over (issue #13, Decision #1). One
     * ref per live {@link Handler} instance, deduped by identity, by resolved
     * configured name where available (issue #14) and identity token
     * otherwise. Unlike {@link #knownHandlers()}, never collapsed or
     * suppressed: WildFly's fan-out needs the true list regardless of what's
     * advertised.
     */
    @Override
    public List<HandlerRef> realHandlers() {
        ensureNamesResolved();
        List<HandlerRef> refs = new ArrayList<>();
        for (Handler handler : liveHandlers()) {
            refs.add(refFor(handler));
        }
        return List.copyOf(refs);
    }

    /**
     * Whether this adapter's root logger is JBoss LogManager's {@code
     * org.jboss.logmanager.Logger} rather than the JDK's own — distinguishes
     * "plain JUL" from "WildFly / JBoss LogManager" for {@link
     * #knownHandlers()}'s ALL_HANDLERS collapse (Decision #1) and {@link
     * #handlerFloorsBelow}'s warning collapse (Decision #7), issue #13. Just
     * a runtime class-name comparison — {@code root.getClass()} is already
     * the real runtime class (see the class doc's classloader note), so no
     * reflective lookup is needed to answer this, unlike the retired
     * friendly-name attempt.
     */
    private boolean isJBossLogManager() {
        return "org.jboss.logmanager.Logger".equals(logger(ROOT_ALIAS).getClass().getName());
    }

    /**
     * The {@link HandlerRef} for {@code handler}, minted once per instance and
     * memoised in {@link #refByHandler} for the adapter's lifetime — a ref
     * must never change under a captured baseline/override (doc/specs/
     * handler-floor-control.md "Ref stability across a late resolution").
     * {@link #upgradeTokenRefs()} is the one exception: a token minted before
     * name resolution succeeded is re-minted to its resolved name, once.
     */
    private HandlerRef refFor(Handler handler) {
        return refByHandler.computeIfAbsent(handler, this::mintRef);
    }

    private HandlerRef mintRef(Handler handler) {
        String resolved = resolvedNames.get(handler);
        HandlerRef ref = resolved != null ? new HandlerRef(resolved) : HandlerRef.anonymous(handler);
        if (resolved == null) {
            tokenRefs.add(ref);
        }
        handlersByRef.putIfAbsent(ref, handler);
        return ref;
    }

    /** Every {@link Handler} attached to any known logger, deduped by identity. */
    private List<Handler> liveHandlers() {
        List<Handler> out = new ArrayList<>();
        Set<Handler> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (String name : knownLoggerNames()) {
            for (Handler handler : logger(name).getHandlers()) {
                if (seen.add(handler)) {
                    out.add(handler);
                }
            }
        }
        return out;
    }

    /**
     * doc/specs/handler-floor-control.md "Lifecycle: when it runs, caching,
     * re-resolution" (issue #14). Lazy: attempted the first time an
     * addressable-surface method needs it, retried while {@link
     * HandlerNameResolver#resolve} keeps coming back empty (the WildFly
     * container's LogManager-ready gate fires before the management model is
     * queryable), and given up on — identity tokens forever, one diagnostic —
     * after {@link #MAX_RESOLVE_ATTEMPTS} empty tries. A no-op for the {@link
     * HandlerNameResolver#NONE} resolver (plain JUL).
     */
    private void ensureNamesResolved() {
        if (resolution != Resolution.PENDING || nameResolver == HandlerNameResolver.NONE) {
            return;
        }
        List<Handler> handlers = liveHandlers();
        if (handlers.isEmpty()) {
            return; // no handlers to resolve yet -- try again on the next call
        }
        Map<Handler, String> names;
        try {
            names = nameResolver.resolve(handlers);
        } catch (RuntimeException unexpected) { // the contract says it won't throw; never trust that
            names = Map.of();
        }
        if (names.isEmpty()) {
            if (emptyResolveAttempts.incrementAndGet() >= MAX_RESOLVE_ATTEMPTS) {
                resolution = Resolution.UNAVAILABLE;
                System.err.println("[logaperture-jul] WildFly handler name resolution unavailable after "
                        + MAX_RESOLVE_ATTEMPTS + " attempts; handlers keep their identity-token refs "
                        + "and only ALL_HANDLERS is individually addressable");
            }
            return;
        }
        resolvedNames.putAll(names);
        resolution = Resolution.DONE;
        upgradeTokenRefs();
    }

    /**
     * The "friendly refs appear" moment (doc/specs/handler-floor-control.md,
     * issue #14 Decision #5): any instance still on a token ref that now has a
     * resolved name is re-minted to that name, exactly once.
     */
    private void upgradeTokenRefs() {
        for (Map.Entry<Handler, HandlerRef> entry : refByHandler.entrySet()) {
            String name = resolvedNames.get(entry.getKey());
            if (name == null || !tokenRefs.contains(entry.getValue())) {
                continue;
            }
            HandlerRef friendly = new HandlerRef(name);
            HandlerRef old = entry.setValue(friendly);
            tokenRefs.remove(old);
            handlersByRef.remove(old);
            handlersByRef.putIfAbsent(friendly, entry.getKey());
        }
    }

    /**
     * doc/specs/handler-floor-control.md "Lifecycle" (issue #14): the WildFly
     * container calls this on a {@code /subsystem=logging} change so a
     * renamed or newly-added handler is picked up. Re-arms the lazy attempt;
     * existing instance&rarr;ref bindings are kept (a ref must stay stable
     * under a captured baseline), but a fresh handler instance created by the
     * reconfiguration resolves normally on the next {@link #realHandlers()}.
     *
     * <p>Public for exactly one collaborator — {@code
     * logaperture-container-wildfly}'s configuration-change hook, which is the
     * whole reason a resolver is injected. No-op unless a real resolver is in
     * play.
     */
    public void invalidateNameCache() {
        resolvedNames.clear();
        emptyResolveAttempts.set(0);
        resolution = Resolution.PENDING;
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
