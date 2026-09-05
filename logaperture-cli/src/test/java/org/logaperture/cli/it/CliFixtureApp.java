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

import org.logaperture.control.jmx.JmxRegistrar;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * A stand-in for an agent-enabled JVM: it publishes the same {@code
 * org.logaperture:type=LevelControl} MBean the agent would, and sets the
 * same {@code logaperture.version} marker system property discovery filters
 * on — but backed by {@link FakeOps}, so {@link CliEndToEndIT} exercises
 * the CLI's real cross-process transport without needing the shaded agent
 * jar.
 *
 * <p>Blocks on stdin so the harness controls its lifetime precisely.
 */
public final class CliFixtureApp {

    private CliFixtureApp() {
    }

    public static void main(String[] args) throws Exception {
        System.setProperty("logaperture.version", "it-fixture");
        FakeOps ops = new FakeOps();
        JmxRegistrar.register(ops, ops);

        System.out.println("FIXTURE-READY");
        System.out.flush();

        new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
    }
}
