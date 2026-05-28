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

package com.github.berrywang1996.netty.spring.web.mvc.consts;

/**
 * Enumeration of HTTP request methods supported by the Netty MVC framework.
 * <p>
 * Includes the standard HTTP methods (GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS, TRACE)
 * plus a special {@link #ALL} value that matches any HTTP method.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public enum HttpRequestMethod {

    /** HTTP GET method -- retrieve a resource. */
    GET,
    /** HTTP POST method -- submit data for processing. */
    POST,
    /** HTTP PUT method -- replace or create a resource. */
    PUT,
    /** HTTP DELETE method -- remove a resource. */
    DELETE,
    /** HTTP PATCH method -- apply partial modifications to a resource. */
    PATCH,
    /** HTTP HEAD method -- same as GET but without a response body. */
    HEAD,
    /** HTTP OPTIONS method -- describe the communication options for the target resource. */
    OPTIONS,
    /** HTTP TRACE method -- perform a message loop-back test. */
    TRACE,
    /** Wildcard value indicating that all HTTP methods are accepted. */
    ALL;

    /**
     * Returns the {@code HttpRequestMethod} enum constant matching the given name (case-insensitive).
     *
     * @param name the HTTP method name (e.g. "GET", "post")
     * @return the matching enum constant, or {@code null} if no match is found
     */
    public static HttpRequestMethod getInstance(String name) {
        switch (name.toUpperCase()) {
            case "GET":
                return GET;
            case "POST":
                return POST;
            case "PUT":
                return PUT;
            case "DELETE":
                return DELETE;
            case "PATCH":
                return PATCH;
            case "HEAD":
                return HEAD;
            case "OPTIONS":
                return OPTIONS;
            case "TRACE":
                return TRACE;
            case "ALL":
                return ALL;
            default:
                return null;
        }
    }

}
