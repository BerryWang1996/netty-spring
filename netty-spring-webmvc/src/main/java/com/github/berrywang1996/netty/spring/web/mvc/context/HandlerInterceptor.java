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

import java.lang.reflect.Method;

/**
 * Interface for intercepting HTTP request handling — mirroring Spring MVC's {@code HandlerInterceptor}.
 *
 * <p>Implementations can perform pre-processing before the handler is invoked, post-processing
 * after handler execution (but before response rendering), and completion callbacks after the
 * full request lifecycle.
 *
 * <p>Interceptors are registered as Spring beans and automatically discovered during startup.
 * They execute in the order determined by Spring's bean ordering.
 *
 * <p>Usage example:
 * <pre>{@code
 * @Component
 * public class AuthInterceptor implements HandlerInterceptor {
 *
 *     @Override
 *     public boolean preHandle(HttpRequestContext request, Object handler, Method handlerMethod) {
 *         String token = request.getRequestHeaders().get("Authorization");
 *         if (token == null) {
 *             // Return false to abort the handler execution
 *             return false;
 *         }
 *         return true;
 *     }
 *
 *     @Override
 *     public void postHandle(HttpRequestContext request, Object handler, Method handlerMethod, Object result) {
 *         // Add custom headers or modify result after handler execution
 *     }
 * }
 * }</pre>
 *
 * @author berrywang1996
 * @since V1.5.0
 */
public interface HandlerInterceptor {

    /**
     * Called before the handler method is invoked.
     *
     * <p>If this method returns {@code false}, the handler method will NOT be invoked,
     * and subsequent interceptors in the chain will NOT be called. The caller is responsible
     * for producing a response (e.g. returning an error).
     *
     * @param request       the HTTP request context
     * @param handler       the controller bean instance
     * @param handlerMethod the handler method about to be invoked
     * @return {@code true} to continue processing; {@code false} to abort
     * @throws Exception if an error occurs during pre-processing
     */
    default boolean preHandle(HttpRequestContext request, Object handler, Method handlerMethod) throws Exception {
        return true;
    }

    /**
     * Called after the handler method has been invoked, but before the response is written.
     *
     * <p>This is called only if {@link #preHandle} returned {@code true} and the handler
     * executed without throwing an exception.
     *
     * @param request       the HTTP request context
     * @param handler       the controller bean instance
     * @param handlerMethod the handler method that was invoked
     * @param result        the return value from the handler method (may be {@code null})
     * @throws Exception if an error occurs during post-processing
     */
    default void postHandle(HttpRequestContext request, Object handler, Method handlerMethod, Object result)
            throws Exception {
    }

    /**
     * Called after the complete request has finished (response written), regardless of outcome.
     *
     * <p>This method is always called if {@link #preHandle} returned {@code true}, even
     * when the handler throws an exception or response writing fails. Use this for resource
     * cleanup (e.g. releasing thread-local variables).
     *
     * @param request       the HTTP request context
     * @param handler       the controller bean instance
     * @param handlerMethod the handler method that was invoked (may be {@code null} if pre-processing failed)
     * @param ex            any exception thrown during handler execution, or {@code null}
     * @throws Exception if an error occurs during completion
     */
    default void afterCompletion(HttpRequestContext request, Object handler, Method handlerMethod, Exception ex)
            throws Exception {
    }

}
