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

package com.github.berrywang1996.netty.spring.web.mvc.bind.annotation;

import java.lang.annotation.*;

/**
 * Annotation indicating that a method parameter should be bound to an HTTP cookie value.
 *
 * <p>This mirrors Spring MVC's {@code @CookieValue} annotation. The cookie name is specified
 * via the {@link #value()} attribute, or falls back to the parameter name if not specified.
 *
 * <p>Usage example:
 * <pre>{@code
 * @GetMapping("/profile")
 * public String profile(@CookieValue("sessionId") String sessionId,
 *                       @CookieValue(value = "theme", defaultValue = "light") String theme) {
 *     return "Session: " + sessionId + ", Theme: " + theme;
 * }
 * }</pre>
 *
 * @author berrywang1996
 * @since V1.5.0
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface CookieValue {

    /**
     * The name of the cookie to bind to. If empty, the method parameter name is used.
     *
     * @return the cookie name; defaults to empty string (use parameter name)
     */
    String value() default "";

    /**
     * Whether the cookie is required.
     * <p>When {@code true} (default), a missing cookie results in a 400 Bad Request error.
     * When {@code false}, a missing cookie results in a {@code null} parameter value
     * (or the {@link #defaultValue()} if specified).
     *
     * @return {@code true} if the cookie is required; defaults to {@code true}
     */
    boolean required() default true;

    /**
     * The default value to use when the cookie is not present in the request.
     * <p>Setting a default value implicitly sets {@link #required()} to {@code false}.
     *
     * @return the default value; defaults to a special sentinel meaning "no default"
     */
    String defaultValue() default "\n\t\t\n\t\t\n\n\t\t\t\t\n";

}
