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
 * Marks a method or exception class with the HTTP status code and reason that should be returned.
 *
 * <p>This mirrors Spring MVC's {@code @ResponseStatus} annotation. When placed on a controller
 * method, it overrides the default 200 OK status. When placed on an exception class used with
 * {@link ExceptionHandler}, it sets the response status for that exception type.
 *
 * <p>Usage example:
 * <pre>{@code
 * @PostMapping("/users")
 * @ResponseStatus(code = 201, reason = "Created")
 * public User createUser(@RequestBody User user) {
 *     return userService.save(user);
 * }
 * }</pre>
 *
 * @author berrywang1996
 * @since V1.5.0
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ResponseStatus {

    /**
     * The HTTP status code to use for the response.
     *
     * @return the HTTP status code; defaults to 200
     */
    int code() default 200;

    /**
     * The reason phrase to accompany the status code.
     * <p>If left empty, the standard reason phrase for the status code is used.
     *
     * @return the reason phrase; defaults to empty string
     */
    String reason() default "";

}
