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

import com.github.berrywang1996.netty.spring.web.mvc.consts.HttpRequestMethod;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * Annotation for mapping HTTP GET requests onto specific handler methods.
 * <p>
 * This is a composed annotation that acts as a shortcut for
 * {@code @RequestMapping(method = HttpRequestMethod.GET)}. It can only be
 * applied at the method level.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@RequestMapping(method = HttpRequestMethod.GET)
public @interface GetMapping {

    /**
     * The URL path patterns to map this method to.
     * Alias for {@link RequestMapping#value}.
     *
     * @return an array of URL patterns; defaults to an empty array (matches the class-level mapping)
     */
    @AliasFor(annotation = RequestMapping.class)
    String[] value() default {};

    /**
     * The server ports on which this mapping should be active.
     * Alias for {@link RequestMapping#port}.
     *
     * @return an array of port numbers; defaults to an empty array (active on all ports)
     */
    @AliasFor(annotation = RequestMapping.class)
    int[] port() default {};

    /**
     * The consumable media types. Alias for {@link RequestMapping#consumes}.
     *
     * @return consumable media types; defaults to an empty array
     */
    @AliasFor(annotation = RequestMapping.class)
    String[] consumes() default {};

    /**
     * The producible media types. Alias for {@link RequestMapping#produces}.
     *
     * @return producible media types; defaults to an empty array
     */
    @AliasFor(annotation = RequestMapping.class)
    String[] produces() default {};

}
