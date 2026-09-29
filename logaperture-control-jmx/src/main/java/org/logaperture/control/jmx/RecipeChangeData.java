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

import org.logaperture.core.RecipeDetail;

import java.beans.ConstructorProperties;

/** One change {@code logctl show recipe} lists -- doc/specs/recipes.md "logctl show recipe". */
public final class RecipeChangeData {

    private final String kind;
    private final String target;
    private final String currentLevel;
    private final String newLevel;
    private final String detail;
    private final String note;

    @ConstructorProperties({"kind", "target", "currentLevel", "newLevel", "detail", "note"})
    public RecipeChangeData(String kind, String target, String currentLevel, String newLevel, String detail, String note) {
        this.kind = kind;
        this.target = target;
        this.currentLevel = currentLevel;
        this.newLevel = newLevel;
        this.detail = detail;
        this.note = note;
    }

    public static RecipeChangeData from(RecipeDetail.Change change) {
        return new RecipeChangeData(change.kind(), change.target(), change.currentLevel(), change.newLevel(),
                change.detail(), change.note());
    }

    /** {@code logger}, {@code handler}, {@code rule drop} or {@code rule trim}. */
    public String getKind() {
        return kind;
    }

    /** The logger or handler name. */
    public String getTarget() {
        return target;
    }

    /** The live level, or {@code null} for a rule or a logger or handler not known yet. */
    public String getCurrentLevel() {
        return currentLevel;
    }

    /** The recipe's level ({@code AUTO} possible for a handler), or {@code null} for a rule. */
    public String getNewLevel() {
        return newLevel;
    }

    /** A rule's options as {@code add rule} takes them, else the entry's reason, or {@code null}. */
    public String getDetail() {
        return detail;
    }

    /** Why the change would be skipped or refused, or {@code null}. */
    public String getNote() {
        return note;
    }
}
