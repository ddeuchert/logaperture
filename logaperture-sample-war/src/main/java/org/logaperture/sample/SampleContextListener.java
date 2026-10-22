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
import java.util.logging.Logger;

/**
 * Optionally holds the deployment for {@code -Dsample.startupDelaySeconds} (default 0) before
 * anything else, to mimic an application with a slow start: WildFly reports
 * {@code server-state=running} only once every deployment is up, so this keeps the server in
 * {@code starting} for a chosen time.
 *
 * <p>Logs a line on deploy — so {@code org.logaperture.sample.work.Worker} shows
 * up in {@code logctl list loggers} straight away — and shuts the background timer
 * (and the {@link DemoShop})
 * down on undeploy, so a redeploy does not leak its thread (and so the
 * adapter is exercised keeping the logger node, and any override on it, alive
 * across the deployment going away).
 */
@WebListener
public class SampleContextListener implements ServletContextListener {

    static final String STARTUP_DELAY_PROPERTY = "sample.startupDelaySeconds";
    private static final Logger LOG = Logger.getLogger(SampleContextListener.class.getName());

    @Override
    public void contextInitialized(ServletContextEvent event) {
        sleepForStartupDelay();
        WorkLog.emitAllLevels("sample deployed");
    }

    private static void sleepForStartupDelay() {
        int seconds = Integer.getInteger(STARTUP_DELAY_PROPERTY, 0);
        if (seconds <= 0) {
            return;
        }
        LOG.info("sample startup delay: holding the deployment for " + seconds + "s");
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        LOG.info("sample startup delay: done");
    }

    @Override
    public void contextDestroyed(ServletContextEvent event) {
        SampleTimer.INSTANCE.shutdown();
        DemoShop.INSTANCE.shutdown();
    }
}
