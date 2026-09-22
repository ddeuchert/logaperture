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
package org.logaperture.control.jmx;

import org.logaperture.api.LogRule;
import org.logaperture.core.RuleView;

import java.beans.ConstructorProperties;

/**
 * MXBean-friendly mirror of a {@link LogRule} — same reasoning as {@link
 * StormData}/{@link LoggerByteCountData}: a plain class with a {@link
 * ConstructorProperties} constructor and JavaBean getters, flattening
 * {@link org.logaperture.api.CompiledMatchers} onto this bean rather than
 * nesting a second MXBean-shaped type. {@code action} reflects {@link
 * LogRule#actionName()} — this slice's own {@code TestRule} double aside,
 * every field here is generic across whatever concrete action (#72's
 * {@code Drop}, #34's {@code Trim}) produced the rule.
 */
public final class RuleData {

    private final String id;
    private final String loggerName;
    private final String action;
    private final String levelAtMost;
    private final String messageContains;
    private final boolean messageIgnoreCase;
    private final String throwableType;
    private final String throwableMessageContains;
    private final boolean anyCause;
    private final String reason;
    private final String tier;
    private final String expiresAt;
    private final String createdAt;
    private final String context;
    private final long hitCount;

    @ConstructorProperties({"id", "loggerName", "action", "levelAtMost", "messageContains", "messageIgnoreCase",
            "throwableType", "throwableMessageContains", "anyCause", "reason", "tier", "expiresAt", "createdAt",
            "context", "hitCount"})
    public RuleData(String id, String loggerName, String action, String levelAtMost, String messageContains,
            boolean messageIgnoreCase, String throwableType, String throwableMessageContains, boolean anyCause,
            String reason, String tier, String expiresAt, String createdAt, String context, long hitCount) {
        this.id = id;
        this.loggerName = loggerName;
        this.action = action;
        this.levelAtMost = levelAtMost;
        this.messageContains = messageContains;
        this.messageIgnoreCase = messageIgnoreCase;
        this.throwableType = throwableType;
        this.throwableMessageContains = throwableMessageContains;
        this.anyCause = anyCause;
        this.reason = reason;
        this.tier = tier;
        this.expiresAt = expiresAt;
        this.createdAt = createdAt;
        this.context = context;
        this.hitCount = hitCount;
    }

    public static RuleData from(RuleView view) {
        LogRule rule = view.rule();
        var matchers = rule.matchers();
        return new RuleData(
                rule.id(),
                rule.loggerName(),
                rule.actionName(),
                matchers.levelAtMost() == null ? null : matchers.levelAtMost().name(),
                matchers.messageContains(),
                matchers.messageIgnoreCase(),
                matchers.throwableType(),
                matchers.throwableMessageContains(),
                matchers.anyCause(),
                rule.reason(),
                rule.tier().name(),
                rule.expiresAt() == null ? null : rule.expiresAt().toString(),
                rule.createdAt().toString(),
                view.context(),
                view.hitCount());
    }

    public String getId() {
        return id;
    }

    public String getLoggerName() {
        return loggerName;
    }

    public String getAction() {
        return action;
    }

    public String getLevelAtMost() {
        return levelAtMost;
    }

    public String getMessageContains() {
        return messageContains;
    }

    public boolean isMessageIgnoreCase() {
        return messageIgnoreCase;
    }

    public String getThrowableType() {
        return throwableType;
    }

    public String getThrowableMessageContains() {
        return throwableMessageContains;
    }

    public boolean isAnyCause() {
        return anyCause;
    }

    public String getReason() {
        return reason;
    }

    public String getTier() {
        return tier;
    }

    public String getExpiresAt() {
        return expiresAt;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public String getContext() {
        return context;
    }

    public long getHitCount() {
        return hitCount;
    }
}
