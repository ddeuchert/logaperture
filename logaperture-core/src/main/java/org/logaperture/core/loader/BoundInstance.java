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
package org.logaperture.core.loader;

import org.logaperture.bridge.Diagnostics;
import org.logaperture.core.spi.ContextHandle;

import java.lang.ref.WeakReference;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * One framework adapter, bound to one owner (doc/specs/class-loader-detector.md, "Scope" 5).
 *
 * <p>Created in a {@link BridgeClassLoader} as soon as the detector sees the framework's marker
 * class. Then:
 * <ul>
 *   <li>it waits, on its own daemon thread, until the framework has a started context
 *       ({@link BridgedFramework#ready()}), and touches nothing before (the readiness constraint);</li>
 *   <li>it gives up {@link Timing#giveUpAfter()} after it was created if there is none, with a
 *       {@code Diagnostics} line (C8), and closes its bridge loader so a loader that is being
 *       unloaded is not kept alive by a wait that never ends (WF6);</li>
 *   <li>once ready it opens the framework, hands each context to the {@link ContextSink}, and lets
 *       go when the framework's own shutdown listener fires (TC5): no Tomcat or WildFly API.</li>
 * </ul>
 * Its thread takes the system loader as context class loader, so a container does not attribute it
 * to the webapp (TC7).
 */
public final class BoundInstance {

    /** How often readiness is polled, and how long to wait for it. */
    public record Timing(Duration pollEvery, Duration giveUpAfter) {
        /** 20 ms (spike I-4), 3 s (WF6: 4 of 4 loaders collected with it, 2 leaked without). */
        public static Timing standard() {
            return new Timing(Duration.ofMillis(20), Duration.ofSeconds(3));
        }
    }

    private enum State { WAITING, BOUND, CLOSED }

    private final FrameworkWatch watch;
    private final String ownerName;
    private final WeakReference<ClassLoader> ownerRef;
    private final BridgeClassLoader bridge;
    private final ContextSink sink;
    private final Timing timing;
    private final AtomicReference<State> state = new AtomicReference<>(State.WAITING);
    private volatile BridgedFramework entry;
    private volatile List<ContextHandle> handles = List.of();
    private volatile boolean registered;
    /** Set when release() has finished; what {@link #isClosed()} reports (issue #314). */
    private volatile boolean released;

    private BoundInstance(FrameworkWatch watch, ClassLoader owner, BridgeSource source, ClassLoader system,
                          ContextSink sink, Timing timing) {
        this.watch = watch;
        this.ownerName = describe(owner);
        this.ownerRef = new WeakReference<>(owner);
        this.bridge = new BridgeClassLoader(owner, watch.ownerPackages(), system, source);
        this.sink = sink;
        this.timing = timing;
    }

    /**
     * Creates the entry in a new bridge loader over {@code owner} and starts waiting for the
     * framework. Never throws: a bind that cannot start is a {@code Diagnostics} line (C8) and a
     * closed instance.
     */
    public static BoundInstance start(FrameworkWatch watch, ClassLoader owner, BridgeSource source,
                                      ClassLoader system, ContextSink sink, Timing timing) {
        Objects.requireNonNull(watch, "watch");
        Objects.requireNonNull(owner, "owner");
        BoundInstance instance = new BoundInstance(watch, owner, source, system, sink, timing);
        try {
            Class<?> entryClass = instance.bridge.loadClass(watch.entryClass());
            instance.entry = (BridgedFramework) entryClass.getDeclaredConstructor().newInstance();
        } catch (Throwable t) {
            instance.fail("its adapter could not be created (" + t + ")", t, false);
            return instance;
        }
        Thread waiter = new Thread(instance::awaitAndBind, "logaperture-" + watch.framework() + "-bind");
        waiter.setDaemon(true);
        waiter.setContextClassLoader(system);
        waiter.start();
        return instance;
    }

    /** Bound and every context handed to the sink (not merely opened). */
    public boolean isBound() {
        return state.get() == State.BOUND && registered;
    }

    /**
     * True once this instance has let go and its release has finished: the adapter's {@code close()}
     * has returned and its contexts are removed. The state flips to closed first, so a caller that
     * only needs "no longer bound" can read {@link #isBound()}; this one is for a caller that goes
     * on to read what the release did (issue #314).
     */
    public boolean isClosed() {
        return released;
    }

    /** The bridge loader, for the tests. */
    BridgeClassLoader bridge() {
        return bridge;
    }

    private void awaitAndBind() {
        long deadline = System.nanoTime() + timing.giveUpAfter().toNanos();
        try {
            while (state.get() == State.WAITING) {
                BridgedFramework waiting = entry;
                if (waiting == null) {
                    return; // let go while waiting
                }
                if (waiting.ready()) {
                    bind();
                    return;
                }
                if (System.nanoTime() - deadline >= 0) {
                    String waited = "it has no started context " + timing.giveUpAfter().toMillis()
                            + " ms after it was found";
                    fail(waited, null, true);
                    return;
                }
                Thread.sleep(timing.pollEvery().toMillis());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            letGo();
        } catch (Throwable t) {
            fail("it failed while waiting for its first context (" + t + ")", t, false);
        }
    }

    private void bind() {
        if (!state.compareAndSet(State.WAITING, State.BOUND)) {
            return; // let go before it was ready
        }
        BridgedFramework opened = entry;
        List<ContextHandle> contexts;
        try {
            contexts = opened.open(this::letGo);
        } catch (Throwable t) {
            fail("opening it failed (" + t + ")", t, false);
            return;
        }
        ClassLoader owner = ownerRef.get();
        if (owner != null) {
            BindFailures.forget(watch.framework(), owner); // before the contexts are added, so doctor never sees both
        }
        List<ContextHandle> added = new ArrayList<>();
        for (ContextHandle context : contexts) {
            if (state.get() != State.BOUND) {
                break; // the framework shut down while open() or this loop was running: add no more
            }
            sink.add(context);
            added.add(context);
        }
        handles = added;
        registered = true;
        if (state.get() == State.CLOSED) {
            // a shutdown landed after the check above: letGo may have seen no handles yet
            added.forEach(sink::remove);
        } else {
            Diagnostics.debug(watch.framework() + " bound in " + ownerName + ": "
                    + added.stream().map(ContextHandle::stableKey).toList());
        }
    }

    /** The framework's shutdown listener calls this. Idempotent, safe from any thread. */
    void letGo() {
        if (state.getAndSet(State.CLOSED) == State.CLOSED) {
            return;
        }
        release();
    }

    private void fail(String reason, Throwable cause, boolean gaveUp) {
        if (state.getAndSet(State.CLOSED) == State.CLOSED) {
            return;
        }
        // C8: a silent failure is a reassuring answer that does nothing
        Diagnostics.warn("LogAperture found " + watch.framework() + " in " + ownerName
                + " but did not bind: " + reason);
        ClassLoader owner = ownerRef.get();
        if (owner != null) {
            BindFailures.record(watch.framework(), owner, ownerName, describeFailure(reason, cause),
                    isVersionProblem(cause) ? BindFailures.Kind.VERSION
                            : gaveUp ? BindFailures.Kind.GAVE_UP : BindFailures.Kind.FAILED);
            if (!gaveUp) {
                // D1, D2: a real problem brings the control surface up so doctor can report it (and there is a
                // row for it to report); a give-up (usually a framework the application never used) does not.
                try {
                    sink.unbound(watch.framework());
                } catch (Throwable t) {
                    Diagnostics.warn("LogAperture could not publish its control surface for the unbound "
                            + watch.framework(), t);
                }
            }
            try {
                sink.notBound(watch.framework(), owner);
            } catch (Throwable t) {
                Diagnostics.warn("LogAperture could not fall back after " + watch.framework() + " was not bound", t);
            }
        }
        release();
    }

    /** The framework's own words when it gave them (a too-old version), else what this class saw. */
    private static String describeFailure(String reason, Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof UnsupportedFrameworkVersionException) {
                return t.getMessage();
            }
        }
        return reason;
    }

    private static boolean isVersionProblem(Throwable cause) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (t instanceof UnsupportedFrameworkVersionException) {
                return true;
            }
        }
        return false;
    }

    private void release() {
        try {
            releaseResources();
        } finally {
            released = true;
        }
    }

    private void releaseResources() {
        BridgedFramework closing = entry;
        entry = null;
        try {
            if (closing != null) {
                closing.close();
            }
        } catch (Throwable t) {
            Diagnostics.warn("LogAperture could not close its " + watch.framework() + " adapter cleanly", t);
        }
        for (ContextHandle handle : handles) {
            sink.remove(handle);
        }
        handles = List.of();
        bridge.close();
    }

    /** The owner's loader class and name, never its {@code toString()} (it may be mid-unload). */
    static String describe(ClassLoader owner) {
        String name = owner.getName();
        return owner.getClass().getName() + (name == null ? "" : " '" + name + "'");
    }
}
