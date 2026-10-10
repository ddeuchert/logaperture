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
package org.logaperture.bench;

import java.util.Locale;

/**
 * doc/specs/overhead-benchmarks.md Decision #18: measures what one
 * {@code System.nanoTime()} call costs, before any benchmark runs. Every log
 * record reads the clock, so a slow clock source inflates both sides of the
 * overhead ratio and the result means nothing.
 *
 * <p>Prints the cost in nanoseconds on standard output. Exits {@value #SLOW}
 * if it is over {@value #LIMIT_NS} ns, with an explanation on standard error,
 * unless given {@code --allow-slow-clock}.
 */
public final class ClockCheck {

    static final double LIMIT_NS = 100.0;
    static final int SLOW = 2;

    private ClockCheck() {
    }

    public static void main(String[] args) {
        boolean allowSlow = args.length > 0 && args[0].equals("--allow-slow-clock");
        double cost = measure();
        System.out.printf(Locale.ROOT, "%.1f%n", cost);
        if (cost <= LIMIT_NS) {
            return;
        }
        System.err.printf(Locale.ROOT, """
                One System.nanoTime() call costs %.0f ns on this machine; about 25 ns is normal, and \
                over %.0f ns makes the overhead numbers meaningless.
                On Linux, check the clock source:
                    cat /sys/devices/system/clocksource/clocksource0/current_clocksource
                If it says hpet or acpi_pm, the kernel rejected the TSC at boot (see dmesg | grep -i tsc). \
                Booting with tsc=reliable usually fixes it.
                """, cost, LIMIT_NS);
        if (allowSlow) {
            System.err.println("Continuing anyway (--allow-slow-clock); the report will carry a warning.");
            return;
        }
        System.err.println("Stopping. Run with --allow-slow-clock to measure anyway.");
        System.exit(SLOW);
    }

    /** The best of five rounds of two million calls: the steady-state cost, not a warmup outlier. */
    static double measure() {
        long sink = 0;
        double best = Double.MAX_VALUE;
        int calls = 2_000_000;
        for (int round = 0; round < 5; round++) {
            long start = System.nanoTime();
            for (int i = 0; i < calls; i++) {
                sink += System.nanoTime();
            }
            best = Math.min(best, (System.nanoTime() - start) / (double) calls);
        }
        if (sink == 1) {
            System.out.print(' '); // keeps the loop from being optimized away
        }
        return best;
    }
}
