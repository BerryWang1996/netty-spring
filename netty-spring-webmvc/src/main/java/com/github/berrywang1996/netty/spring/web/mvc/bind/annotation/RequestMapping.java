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

import java.lang.annotation.*;

/**
 * Annotation for mapping HTTP requests onto handler methods or controller classes.
 * <p>
 * When applied at the class level it defines a base URL prefix for all handler methods
 * in that controller. When applied at the method level it narrows the mapping to
 * specific URL patterns, HTTP methods, and/or server ports.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestMapping {

    /**
     * The URL path patterns to map. At the class level this serves as a prefix;
     * at the method level it specifies the concrete endpoints.
     *
     * @return an array of URL patterns; defaults to an empty array
     */
    String[] value() default {};

    /**
     * The HTTP request methods (GET, POST, PUT, etc.) accepted by this mapping.
     * An empty array means all HTTP methods are accepted.
     *
     * @return an array of accepted HTTP methods; defaults to an empty array (all methods)
     */
    HttpRequestMethod[] method() default {};

    /**
     * The server ports on which this mapping should be active.
     * An empty array means the mapping is active regardless of port.
     *
     * @return an array of port numbers; defaults to an empty array (all ports)
     */
    int[] port() default {};

    /**
     * The consumable media types of the mapped request (e.g. {@code "application/json"}).
     * <p>The request is only matched if the {@code Content-Type} of the incoming request
     * matches one of the specified media types. An empty array means no constraint.
     *
     * @return consumable media types; defaults to an empty array (no constraint)
     */
    String[] consumes() default {};

    /**
     * The producible media types of the mapped response (e.g. {@code "application/json"}).
     * <p>The request is only matched if the {@code Accept} header of the incoming request
     * is compatible with one of the specified media types. The first match determines
     * the response {@code Content-Type}. An empty array means no constraint.
     *
     * @return producible media types; defaults to an empty array (no constraint)
     */
    String[] produces() default {};

}
