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

package com.github.berrywang1996.netty.spring.web.mvc.context;

import java.util.*;

/**
 * Extension of {@code Object} that adds an HTTP status code, headers, and a body
 * to the response. Used as a return type from controller methods to give full control
 * over the HTTP response — mirroring Spring MVC's {@code ResponseEntity}.
 *
 * <p>Usage examples:
 * <pre>{@code
 * // Return 200 OK with JSON body
 * return ResponseEntity.ok(user);
 *
 * // Return 201 Created with Location header
 * return ResponseEntity.status(201)
 *     .header("Location", "/users/" + user.getId())
 *     .body(user);
 *
 * // Return 204 No Content
 * return ResponseEntity.noContent().build();
 *
 * // Return 404 Not Found
 * return ResponseEntity.notFound().build();
 * }</pre>
 *
 * @param <T> the body type
 * @author berrywang1996
 * @since V1.5.0
 */
public class ResponseEntity<T> {

    /** The HTTP status code. */
    private final int statusCode;

    /** Custom response headers. */
    private final Map<String, List<String>> headers;

    /** The response body payload. */
    private final T body;

    /**
     * Creates a new {@code ResponseEntity} with the given status, headers, and body.
     *
     * @param statusCode the HTTP status code
     * @param headers    the response headers (may be {@code null})
     * @param body       the response body (may be {@code null})
     */
    public ResponseEntity(int statusCode, Map<String, List<String>> headers, T body) {
        this.statusCode = statusCode;
        this.headers = headers != null ? headers : Collections.emptyMap();
        this.body = body;
    }

    /**
     * Returns the HTTP status code.
     *
     * @return the status code
     */
    public int getStatusCode() {
        return statusCode;
    }

    /**
     * Returns the response headers.
     *
     * @return an unmodifiable view of the headers
     */
    public Map<String, List<String>> getHeaders() {
        return headers;
    }

    /**
     * Returns the response body.
     *
     * @return the body, or {@code null} if there is no body
     */
    public T getBody() {
        return body;
    }

    // ----- Static factory methods -----

    /**
     * Creates a builder with the given HTTP status code.
     *
     * @param statusCode the HTTP status code
     * @return a new {@link BodyBuilder}
     */
    public static BodyBuilder status(int statusCode) {
        return new DefaultBodyBuilder(statusCode);
    }

    /**
     * Creates a builder with HTTP 200 OK status.
     *
     * @return a new {@link BodyBuilder} with status 200
     */
    public static BodyBuilder ok() {
        return status(200);
    }

    /**
     * Shortcut for creating a 200 OK response with the given body.
     *
     * @param body the response body
     * @param <T>  the body type
     * @return a new {@code ResponseEntity} with status 200 and the given body
     */
    public static <T> ResponseEntity<T> ok(T body) {
        return new ResponseEntity<>(200, null, body);
    }

    /**
     * Creates a builder with HTTP 201 Created status.
     *
     * @return a new {@link BodyBuilder} with status 201
     */
    public static BodyBuilder created() {
        return status(201);
    }

    /**
     * Creates a builder with HTTP 202 Accepted status.
     *
     * @return a new {@link BodyBuilder} with status 202
     */
    public static BodyBuilder accepted() {
        return status(202);
    }

    /**
     * Creates a builder with HTTP 204 No Content status.
     *
     * @return a new {@link BodyBuilder} with status 204
     */
    public static BodyBuilder noContent() {
        return status(204);
    }

    /**
     * Creates a builder with HTTP 400 Bad Request status.
     *
     * @return a new {@link BodyBuilder} with status 400
     */
    public static BodyBuilder badRequest() {
        return status(400);
    }

    /**
     * Creates a builder with HTTP 404 Not Found status.
     *
     * @return a new {@link BodyBuilder} with status 404
     */
    public static BodyBuilder notFound() {
        return status(404);
    }

    /**
     * Creates a builder with HTTP 500 Internal Server Error status.
     *
     * @return a new {@link BodyBuilder} with status 500
     */
    public static BodyBuilder internalServerError() {
        return status(500);
    }

    // ----- Builder interface -----

    /**
     * Builder interface for creating {@link ResponseEntity} instances with headers and body.
     */
    public interface BodyBuilder {

        /**
         * Adds a header with the given name and value(s).
         *
         * @param name   the header name
         * @param values the header values
         * @return this builder
         */
        BodyBuilder header(String name, String... values);

        /**
         * Sets the {@code Content-Type} header.
         *
         * @param contentType the content type value
         * @return this builder
         */
        BodyBuilder contentType(String contentType);

        /**
         * Sets the body and builds the {@link ResponseEntity}.
         *
         * @param body the response body
         * @param <T>  the body type
         * @return a new {@code ResponseEntity}
         */
        <T> ResponseEntity<T> body(T body);

        /**
         * Builds the {@link ResponseEntity} with no body.
         *
         * @return a new {@code ResponseEntity} with {@code null} body
         */
        ResponseEntity<Void> build();

    }

    /**
     * Default implementation of {@link BodyBuilder}.
     */
    private static class DefaultBodyBuilder implements BodyBuilder {

        private final int statusCode;
        private final Map<String, List<String>> headers = new LinkedHashMap<>();

        DefaultBodyBuilder(int statusCode) {
            this.statusCode = statusCode;
        }

        @Override
        public BodyBuilder header(String name, String... values) {
            this.headers.computeIfAbsent(name, k -> new ArrayList<>())
                    .addAll(Arrays.asList(values));
            return this;
        }

        @Override
        public BodyBuilder contentType(String contentType) {
            // Replace existing Content-Type
            this.headers.put("Content-Type", Collections.singletonList(contentType));
            return this;
        }

        @Override
        public <T> ResponseEntity<T> body(T body) {
            return new ResponseEntity<>(statusCode, headers.isEmpty() ? null : headers, body);
        }

        @Override
        public ResponseEntity<Void> build() {
            return new ResponseEntity<>(statusCode, headers.isEmpty() ? null : headers, null);
        }
    }

}
