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
 * Annotation for enabling Cross-Origin Resource Sharing (CORS) on handler methods or controllers.
 *
 * <p>This mirrors Spring MVC's {@code @CrossOrigin} annotation. When placed on a controller class,
 * CORS settings apply to all handler methods. When placed on individual methods, settings apply
 * only to that endpoint. Method-level attributes override class-level ones.
 *
 * <p>Usage example:
 * <pre>{@code
 * @RestController
 * @CrossOrigin(origins = "https://example.com")
 * public class ApiController {
 *
 *     @GetMapping("/data")
 *     public String data() { return "ok"; }
 *
 *     @PostMapping("/submit")
 *     @CrossOrigin(origins = "*", maxAge = 3600)
 *     public String submit(@RequestBody Data data) { return "submitted"; }
 * }
 * }</pre>
 *
 * @author berrywang1996
 * @since V1.5.0
 */
@Documented
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface CrossOrigin {

    /**
     * The list of allowed origins. The special value {@code "*"} allows all origins.
     * <p>These values are placed in the {@code Access-Control-Allow-Origin} response header.
     *
     * @return allowed origins; defaults to {@code "*"} (all origins)
     */
    String[] origins() default {"*"};

    /**
     * The list of allowed HTTP methods for CORS requests.
     * <p>These values are placed in the {@code Access-Control-Allow-Methods} response header.
     * By default, the allowed methods are the same as the mapping methods.
     *
     * @return allowed methods; defaults to empty (derived from mapping)
     */
    String[] allowedMethods() default {};

    /**
     * The list of request headers allowed in CORS preflight requests.
     * <p>These values are placed in the {@code Access-Control-Allow-Headers} response header.
     * The special value {@code "*"} allows all headers.
     *
     * @return allowed headers; defaults to {@code "*"}
     */
    String[] allowedHeaders() default {"*"};

    /**
     * The list of response headers that the browser is allowed to access.
     * <p>These values are placed in the {@code Access-Control-Expose-Headers} response header.
     *
     * @return exposed headers; defaults to empty
     */
    String[] exposedHeaders() default {};

    /**
     * Whether the browser should send credentials (cookies, authorization headers, TLS certificates)
     * with cross-origin requests.
     * <p>When {@code true}, the {@code Access-Control-Allow-Credentials} header is set to {@code true}.
     * Note: when credentials are enabled, the origin cannot be {@code "*"}.
     *
     * @return {@code true} to allow credentials; defaults to {@code false}
     */
    boolean allowCredentials() default false;

    /**
     * The maximum time (in seconds) that the preflight response can be cached by the browser.
     * <p>A value of {@code -1} means undefined (the browser decides). This value is placed in the
     * {@code Access-Control-Max-Age} header.
     *
     * @return the max age in seconds; defaults to {@code -1}
     */
    long maxAge() default -1;

}
