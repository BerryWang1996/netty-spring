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
 * Annotation for handling exceptions within a specific controller or globally via {@link ControllerAdvice}.
 *
 * <p>This mirrors Spring MVC's {@code @ExceptionHandler} annotation. Methods annotated with
 * {@code @ExceptionHandler} handle exceptions thrown by handler methods in the same controller
 * (or across all controllers when used in a {@code @ControllerAdvice} class).
 *
 * <p>The handler method may accept the exception instance and optionally an {@code HttpRequestContext}
 * as parameters. The return value is processed through the standard view handler chain.
 *
 * <p>Usage example:
 * <pre>{@code
 * @RestController
 * public class UserController {
 *
 *     @GetMapping("/user/{id}")
 *     public User getUser(@PathVariable Integer id) {
 *         if (id <= 0) throw new IllegalArgumentException("Invalid ID");
 *         return userService.findById(id);
 *     }
 *
 *     @ExceptionHandler(IllegalArgumentException.class)
 *     @ResponseStatus(code = 400)
 *     public Map<String, String> handleBadRequest(IllegalArgumentException ex) {
 *         return Map.of("error", ex.getMessage());
 *     }
 * }
 * }</pre>
 *
 * @author berrywang1996
 * @since V1.5.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExceptionHandler {

    /**
     * The exception types handled by this method.
     * <p>If empty, the method handles all exception types assignable to the method's
     * exception parameter (or {@link Exception} if no exception parameter is declared).
     *
     * @return the exception classes this handler targets; defaults to empty (inferred from parameter)
     */
    Class<? extends Throwable>[] value() default {};

}
