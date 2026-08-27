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
package org.logaperture.cli.it;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A plain JVM: attachable, but with no agent, no MBean, and no {@code
 * logaperture.version} marker. {@link CliEndToEndIT} points {@code --pid}
 * at it to prove an explicit target without the control surface is
 * reported as "no LogAperture agent" (exit 3), not a crash.
 */
public final class PlainApp {

    private PlainApp() {
    }

    public static void main(String[] args) throws Exception {
        System.out.println("PLAIN-READY");
        System.out.flush();
        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
    }
}
