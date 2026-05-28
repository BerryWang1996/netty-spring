/*
 * Copyright 2018 berrywang1996
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

package com.github.berrywang1996.netty.spring.web.util;

/**
 * Lightweight string utility class providing null-safe blank checks for {@link CharSequence} types.
 *
 * <p>Supports {@link String}, {@link StringBuilder}, and {@link StringBuffer} instances.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public class StringUtil {

    /** Private constructor to prevent instantiation of this utility class. */
    private StringUtil() {
    }

    /**
     * Checks whether the given character sequence is not blank (not null and not empty after trimming).
     *
     * @param charSequence the character sequence to check (may be {@code null})
     * @return {@code true} if the sequence is non-null and contains at least one non-whitespace character
     */
    public static boolean isNotBlank(CharSequence charSequence) {
        return !isBlank(charSequence);
    }

    /**
     * Checks whether the given character sequence is blank (null, empty, or contains only whitespace).
     *
     * @param charSequence the character sequence to check (may be {@code null})
     * @return {@code true} if the sequence is null or empty after trimming
     */
    public static boolean isBlank(CharSequence charSequence) {
        if (charSequence == null) {
            return true;
        }
        if (charSequence instanceof String) {
            return "".equals(((String) charSequence).trim());
        } else if (charSequence instanceof StringBuilder) {
            return "".equals(charSequence.toString().trim());
        } else if (charSequence instanceof StringBuffer) {
            return "".equals(charSequence.toString().trim());
        }
        return false;
    }

}
