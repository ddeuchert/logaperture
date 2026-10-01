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

/**
 * Prints the cost of one {@code System.nanoTime()} call in nanoseconds, run as
 * {@code java NanoTimeCost.java}. The benchmarks read the clock on the logging path, so a slow
 * clock (about 25 ns is normal; over 100 ns means a slow kernel clocksource, such as Linux falling
 * back to HPET) inflates every result. {@code run-baseline.ps1} records this in {@code machine.txt}.
 */
public class NanoTimeCost {

    public static void main(String[] args) {
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
            System.out.print(' ');
        }
        System.out.printf(java.util.Locale.ROOT, "%.1f%n", best);
    }
}
