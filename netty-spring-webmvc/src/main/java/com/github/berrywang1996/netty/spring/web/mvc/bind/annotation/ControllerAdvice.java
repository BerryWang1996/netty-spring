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

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * Specialization of {@link Component @Component} for classes that declare
 * {@link ExceptionHandler @ExceptionHandler} methods to be shared across all controllers.
 *
 * <p>This mirrors Spring MVC's {@code @ControllerAdvice} annotation. Classes annotated with
 * {@code @ControllerAdvice} are auto-detected during application startup, and their
 * {@code @ExceptionHandler} methods apply globally to all request mapping handlers.
 *
 * <p>Usage example:
 * <pre>{@code
 * @ControllerAdvice
 * public class GlobalExceptionHandler {
 *
 *     @ExceptionHandler(Exception.class)
 *     @ResponseStatus(code = 500)
 *     @ResponseBody
 *     public Map<String, String> handleAll(Exception ex) {
 *         return Map.of("error", "Internal Server Error", "message", ex.getMessage());
 *     }
 *
 *     @ExceptionHandler(IllegalArgumentException.class)
 *     @ResponseStatus(code = 400)
 *     @ResponseBody
 *     public Map<String, String> handleBadRequest(IllegalArgumentException ex) {
 *         return Map.of("error", "Bad Request", "message", ex.getMessage());
 *     }
 * }
 * }</pre>
 *
 * @author berrywang1996
 * @since V1.5.0
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Component
public @interface ControllerAdvice {

    /**
     * Optional bean name for the controller advice component.
     *
     * @return the bean name; defaults to empty string (auto-generated)
     */
    String value() default "";

}
