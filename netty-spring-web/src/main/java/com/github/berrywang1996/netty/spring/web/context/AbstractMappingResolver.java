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

package com.github.berrywang1996.netty.spring.web.context;

import io.netty.channel.ChannelHandlerContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.util.PathMatcher;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Abstract base class for URL mapping resolvers that dispatch inbound Netty messages
 * to the appropriate handler method.
 *
 * <p>A mapping resolver binds a URL pattern to one or more handler {@link Method}s,
 * identified by a key of type {@code K}. Subclasses implement {@link #resolve(ChannelHandlerContext, Object)}
 * to decode the inbound message {@code T} and invoke the correct handler method.
 *
 * <p>This class also handles:
 * <ul>
 *   <li>Reflective discovery of method parameter names and types via Spring's
 *       {@link DefaultParameterNameDiscoverer}</li>
 *   <li>Ant-style path matching through a pluggable {@link PathMatcher}</li>
 *   <li>Optional runtime recording of HTTP diagnostics via {@link HttpRuntimeRecorder}</li>
 * </ul>
 *
 * @param <T> the inbound message type (e.g. {@code FullHttpRequest}, {@code WebSocketFrame})
 * @param <K> the key type used to identify handler methods (e.g. {@code HttpMethod}, {@code String})
 * @author berrywang1996
 * @since V1.0.0
 */
public abstract class AbstractMappingResolver<T, K> {

    /** The URL pattern this resolver is mapped to. */
    private final String url;

    /** Immutable map of handler method keys to their corresponding {@link Method} instances. */
    private final Map<K, Method> methods;

    /** Parsed parameter metadata: key -> ordered map of parameter name to parameter type. */
    private final Map<K, Map<String, Class>> methodParamTypes;

    /** Direct reference to the handler bean instance (used when not resolved from Spring context). */
    private final Object invokeRef;

    /** Spring application context for lazy bean lookup when {@code invokeBeanName} is set. */
    private final ApplicationContext applicationContext;

    /** Spring bean name for deferred handler resolution from the application context. */
    private final String invokeBeanName;

    /** Ant-style path matcher for wildcard URL pattern matching. */
    private PathMatcher pathMatcher;

    /** Recorder for HTTP runtime diagnostics; defaults to a no-op instance. */
    private HttpRuntimeRecorder httpRuntimeRecorder = HttpRuntimeRecorder.noop();

    /**
     * Creates a resolver with a direct handler object reference.
     *
     * @param url       the URL pattern this resolver handles
     * @param methods   map of method keys to handler {@link Method}s
     * @param invokeRef the handler bean instance
     */
    public AbstractMappingResolver(String url, Map<K, Method> methods, Object invokeRef) {
        this(url, methods, invokeRef, null, null);
    }

    /**
     * Creates a resolver that lazily looks up the handler bean from the Spring context.
     *
     * @param url                the URL pattern this resolver handles
     * @param methods            map of method keys to handler {@link Method}s
     * @param applicationContext the Spring application context for bean lookup
     * @param invokeBeanName     the bean name to resolve from the application context
     */
    public AbstractMappingResolver(String url,
                                   Map<K, Method> methods,
                                   ApplicationContext applicationContext,
                                   String invokeBeanName) {
        this(url, methods, null, applicationContext, invokeBeanName);
    }

    /**
     * Internal constructor that initializes all fields and parses method parameter metadata.
     *
     * @param url                the URL pattern this resolver handles
     * @param methods            map of method keys to handler {@link Method}s
     * @param invokeRef          the handler bean instance (may be {@code null})
     * @param applicationContext the Spring application context (may be {@code null})
     * @param invokeBeanName     the bean name for deferred lookup (may be {@code null})
     */
    private AbstractMappingResolver(String url,
                                    Map<K, Method> methods,
                                    Object invokeRef,
                                    ApplicationContext applicationContext,
                                    String invokeBeanName) {
        this.url = url;
        this.methods = Collections.unmodifiableMap(methods);
        this.invokeRef = invokeRef;
        this.applicationContext = applicationContext;
        this.invokeBeanName = invokeBeanName;

        // parse method parameters
        this.methodParamTypes = parseMethodParameters();
    }

    /**
     * Returns the immutable map of all handler methods registered for this resolver.
     *
     * @return unmodifiable map of method keys to {@link Method} instances
     */
    public Map<K, Method> getMethods() {
        return methods;
    }

    /**
     * Returns the handler method associated with the given key.
     *
     * @param key the method key (e.g. HTTP method or message type identifier)
     * @return the corresponding {@link Method}, or {@code null} if not found
     */
    public Method getMethod(K key) {
        return methods.get(key);
    }

    /**
     * Returns the ordered parameter name-to-type map for the handler method identified by the given key.
     *
     * @param key the method key
     * @return ordered map of parameter names to their types, or {@code null} if the key is not found
     */
    public Map<String, Class> getMethodParamType(K key) {
        return methodParamTypes.get(key);
    }

    /**
     * Returns the set of all method keys registered in this resolver.
     *
     * @return set of method keys, or {@code null} if no methods are registered
     */
    public Set<K> getMethodKey() {
        if (methods == null) {
            return null;
        }
        return methods.keySet();
    }

    /**
     * Returns the URL pattern this resolver is bound to.
     *
     * @return the URL pattern string
     */
    public String getUrl() {
        return url;
    }

    /**
     * Returns the handler bean instance. If a bean name is configured, the bean is looked up
     * from the Spring application context on each call (supporting prototype-scoped beans).
     *
     * @return the handler bean instance used for method invocation
     */
    public Object getInvokeRef() {
        // Prefer lazy lookup from Spring context when bean name is available
        if (this.invokeBeanName != null && this.applicationContext != null) {
            return this.applicationContext.getBean(this.invokeBeanName);
        }
        return invokeRef;
    }

    /**
     * Returns the path matcher used for Ant-style URL pattern matching.
     *
     * @return the current {@link PathMatcher} instance
     */
    public PathMatcher getPathMatcher() {
        return pathMatcher;
    }

    /**
     * Sets the path matcher for Ant-style URL pattern matching.
     *
     * @param pathMatcher the {@link PathMatcher} to use
     */
    public void setPathMatcher(PathMatcher pathMatcher) {
        this.pathMatcher = pathMatcher;
    }

    /**
     * Sets the HTTP runtime recorder for diagnostics. Falls back to a no-op recorder if {@code null}.
     *
     * @param httpRuntimeRecorder the recorder instance, or {@code null} for no-op
     */
    public void setHttpRuntimeRecorder(HttpRuntimeRecorder httpRuntimeRecorder) {
        this.httpRuntimeRecorder = httpRuntimeRecorder == null
                ? HttpRuntimeRecorder.noop()
                : httpRuntimeRecorder;
    }

    /**
     * Returns the HTTP runtime recorder for subclass use.
     *
     * @return the current {@link HttpRuntimeRecorder} (never {@code null})
     */
    protected HttpRuntimeRecorder getHttpRuntimeRecorder() {
        return httpRuntimeRecorder;
    }

    /**
     * Dispatches the inbound message to the appropriate handler method.
     *
     * @param ctx the Netty channel handler context
     * @param msg the inbound message to resolve
     * @throws Exception if handler invocation or message processing fails
     */
    public abstract void resolve(ChannelHandlerContext ctx, T msg) throws Exception;

    /**
     * Handles exceptions that occur during message resolution. The default implementation
     * re-throws the exception; subclasses may override to provide custom error handling.
     *
     * @param ctx the Netty channel handler context
     * @param e   the exception to handle
     * @throws Exception if the exception cannot be handled
     */
    public void resolveException(ChannelHandlerContext ctx, Exception e) throws Exception {
        throw e;
    }

    /**
     * Called when the Netty channel becomes inactive. Subclasses may override to perform
     * cleanup (e.g. removing WebSocket sessions).
     *
     * @param ctx the Netty channel handler context
     * @throws Exception if cleanup fails
     */
    public void onChannelInactive(ChannelHandlerContext ctx) throws Exception {
    }

    /**
     * Releases resources held by this resolver during server shutdown.
     * Subclasses may override to close executors or connections.
     *
     * @throws Exception if shutdown fails
     */
    public void shutdown() throws Exception {
    }

    /**
     * Returns the number of currently active sessions managed by this resolver.
     * Subclasses (e.g. WebSocket resolvers) override to report actual session counts.
     *
     * @return the active session count; defaults to 0
     */
    public int getActiveSessionCount() {
        return 0;
    }

    /**
     * Returns event counters for observability. Subclasses override to provide
     * module-specific metrics. The returned map is consumed by the management
     * endpoint and {@link WebSocketRuntimeStats}.
     *
     * @return an unmodifiable map of counter names to values, or an empty map
     * @since V1.3.0
     */
    public Map<String, Object> getEventCounters() {
        return Collections.emptyMap();
    }

    /** Shared discoverer for resolving method parameter names from bytecode debug info or annotations. */
    private static final DefaultParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    /**
     * Parses all registered handler methods to extract their parameter names and types.
     *
     * <p>Uses Spring's {@link DefaultParameterNameDiscoverer} to resolve parameter names
     * from bytecode debug symbols or {@code @Param} annotations. The result is an
     * unmodifiable map keyed by method key, with each value being an ordered map
     * of parameter name to parameter type.
     *
     * @return unmodifiable map of method keys to their parameter metadata
     */
    public Map<K, Map<String, Class>> parseMethodParameters() {
        Map<K, Map<String, Class>> tempMethodParamTypes = new HashMap<>();
        for (Map.Entry<K, Method> kMethodEntry : methods.entrySet()) {
            LinkedHashMap<String, Class> methodParams = new LinkedHashMap<>();
            // Discover parameter names from bytecode or annotations
            String[] params = PARAMETER_NAME_DISCOVERER.getParameterNames(kMethodEntry.getValue());
            Class<?>[] parameterTypes = kMethodEntry.getValue().getParameterTypes();
            if (params != null && parameterTypes.length == params.length) {
                for (int i = 0; i < params.length; i++) {
                    methodParams.put(params[i], parameterTypes[i]);
                }
            }
            tempMethodParamTypes.put(kMethodEntry.getKey(), methodParams);
        }
        return Collections.unmodifiableMap(tempMethodParamTypes);
    }

    /**
     * Removes the session identified by the given session ID from this resolver's
     * internal session tracking. Subclasses implement this to clean up WebSocket
     * or long-lived connection state.
     *
     * @param sessionId the unique identifier of the session to remove
     */
    public abstract void removeSession(String sessionId);

}
