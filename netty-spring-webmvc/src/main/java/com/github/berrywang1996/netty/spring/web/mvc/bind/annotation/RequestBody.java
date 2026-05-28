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
 * Annotation indicating that a method parameter should be bound to the body of the HTTP request.
 *
 * <p>The request body is deserialized from JSON into the target parameter type using Jackson's
 * {@code ObjectMapper}. This annotation is the primary mechanism for accepting JSON payloads
 * in POST/PUT/PATCH endpoints — mirroring Spring MVC's {@code @RequestBody}.
 *
 * <p>Usage example:
 * <pre>{@code
 * @PostMapping("/users")
 * public ResponseEntity<User> createUser(@RequestBody User user) {
 *     // user is deserialized from JSON request body
 *     return ResponseEntity.ok(userService.save(user));
 * }
 * }</pre>
 *
 * @author berrywang1996
 * @since V1.5.0
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestBody {

    /**
     * Whether the request body is required.
     * <p>When {@code true} (default), a missing or empty body will result in a 400 Bad Request error.
     * When {@code false}, a missing body will result in a {@code null} parameter value.
     *
     * @return {@code true} if the body is required; defaults to {@code true}
     */
    boolean required() default true;

}
