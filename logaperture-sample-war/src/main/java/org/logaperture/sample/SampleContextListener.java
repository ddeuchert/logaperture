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
package org.logaperture.sample;

import javax.servlet.ServletContextEvent;
import javax.servlet.ServletContextListener;
import javax.servlet.annotation.WebListener;

/**
 * Logs a line on deploy — so {@code org.logaperture.sample.work.Worker} shows
 * up in {@code logctl levels} straight away — and shuts the background timer
 * down on undeploy, so a redeploy does not leak its thread (and so the
 * adapter is exercised keeping the logger node, and any override on it, alive
 * across the deployment going away).
 */
@WebListener
public class SampleContextListener implements ServletContextListener {

    @Override
    public void contextInitialized(ServletContextEvent event) {
        WorkLog.emitAllLevels("sample deployed");
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        SampleTimer.INSTANCE.shutdown();
    }
}
