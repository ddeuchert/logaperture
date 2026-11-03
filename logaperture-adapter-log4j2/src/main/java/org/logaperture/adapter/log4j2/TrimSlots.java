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
package org.logaperture.adapter.log4j2;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.appender.AsyncAppender;
import org.apache.logging.log4j.core.config.AppenderRef;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.layout.PatternLayout;

import java.util.ArrayList;
import java.util.List;

/**
 * Puts a {@link TrimAppender} in every appender slot of a configuration whose appender writes text
 * (doc/specs/log4j2-adapter.md A4, "Scope" 5). Idempotent: a slot that already holds one is left alone.
 *
 * <p>A slot is one {@code LoggerConfig}'s reference to an appender, as its {@code AppenderRef} lists it:
 * the reference is the only place the slot's level and filter can be read, so an appender added to a
 * {@code LoggerConfig} in code with no reference is left alone rather than rewrapped with a guess. The wrapper replaces the appender in
 * the slot only, keeping its name and the reference's level and filter; {@code config.getAppenders()}
 * still lists the originals, so the handler listing is unaffected. Swapping an appender takes a
 * {@code remove} and an {@code add} (the set is keyed by name), so a call on that logger can miss that
 * appender for the microseconds between them.
 */
final class TrimSlots {

    private TrimSlots() {
    }

    /** @return how many slots now hold a wrapper that did not before */
    static int wrapAll(Configuration configuration, TrimAppender.Shared shared) {
        int wrapped = 0;
        List<LoggerConfig> loggers = new ArrayList<>(configuration.getLoggers().values());
        for (LoggerConfig loggerConfig : loggers) {
            for (AppenderRef reference : new ArrayList<>(loggerConfig.getAppenderRefs())) {
                Appender appender = loggerConfig.getAppenders().get(reference.getRef());
                if (appender == null || appender instanceof TrimAppender || !writesText(appender, configuration)) {
                    continue;
                }
                TrimAppender wrapper = new TrimAppender(appender, shared);
                wrapper.start();
                Level level = reference.getLevel();
                Filter filter = reference.getFilter();
                loggerConfig.removeAppender(reference.getRef()); // stops the reference's filter
                if (filter != null && filter.isStopped()) {
                    filter.start();
                }
                loggerConfig.addAppender(wrapper, level, filter);
                wrapped++;
            }
        }
        return wrapped;
    }

    /**
     * Whether the appender writes text through a {@code PatternLayout}, which is what a trim can be
     * rendered for ("text formatters only", trim-rule.md; a structured layout is left untouched, #83). An
     * {@code <Async>} appender has no layout of its own: it counts if everything behind it does.
     */
    static boolean writesText(Appender appender, Configuration configuration) {
        if (appender instanceof AsyncAppender async) {
            String[] behind = async.getAppenderRefStrings();
            if (behind.length == 0) {
                return false;
            }
            for (String name : behind) {
                Appender inner = configuration.getAppender(name);
                if (inner == null || !writesText(inner, configuration)) {
                    return false;
                }
            }
            return true;
        }
        return appender.getLayout() instanceof PatternLayout;
    }
}
