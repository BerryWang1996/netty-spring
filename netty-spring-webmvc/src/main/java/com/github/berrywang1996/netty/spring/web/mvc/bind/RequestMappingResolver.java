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

package com.github.berrywang1996.netty.spring.web.mvc.bind;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.databind.DataBindUtil;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.*;
import com.github.berrywang1996.netty.spring.web.mvc.consts.HttpRequestMethod;
import com.github.berrywang1996.netty.spring.web.mvc.context.*;
import com.github.berrywang1996.netty.spring.web.mvc.view.AbstractViewHandler;
import com.github.berrywang1996.netty.spring.web.util.ServiceHandlerUtil;
import com.github.berrywang1996.netty.spring.web.util.StringUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpChunkedInput;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http.multipart.FileUpload;
import io.netty.handler.codec.http.multipart.HttpPostRequestDecoder;
import io.netty.handler.codec.http.multipart.InterfaceHttpData;
import io.netty.handler.stream.ChunkedFile;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Resolver responsible for handling HTTP request mappings in the Netty-based MVC framework.
 *
 * <p>Extends {@link AbstractMappingResolver} to process incoming {@link FullHttpRequest} messages
 * by matching them against registered handler methods keyed by {@link HttpRequestMethod}. For each
 * incoming request the resolver:
 * <ol>
 *   <li>Handles CORS preflight (OPTIONS) requests if @CrossOrigin is configured</li>
 *   <li>Validates the HTTP method and looks up the corresponding handler method</li>
 *   <li>Runs {@link HandlerInterceptor#preHandle} chain; aborts if any returns false</li>
 *   <li>Builds an {@link HttpRequestContext} containing headers, parameters, and cookies</li>
 *   <li>Resolves method parameters from path variables, query params, headers, cookies, and request body</li>
 *   <li>Invokes the handler method via reflection</li>
 *   <li>Runs {@link HandlerInterceptor#postHandle} chain</li>
 *   <li>Processes {@link ResponseEntity} return values, {@link ResponseStatus} annotations, and CORS headers</li>
 *   <li>Delegates the result to an {@link AbstractViewHandler} and writes the HTTP response</li>
 *   <li>Routes exceptions through {@link ExceptionHandler @ExceptionHandler} resolution</li>
 *   <li>Runs {@link HandlerInterceptor#afterCompletion} chain</li>
 * </ol>
 *
 * <p>RESTful URL support is automatically enabled when any handler method parameter is annotated
 * with {@link PathVariable}.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class RequestMappingResolver extends AbstractMappingResolver<FullHttpRequest, HttpRequestMethod> {

    /** Sentinel value for "no default" in annotation attributes. */
    private static final String NO_DEFAULT_VALUE = "\n\t\t\n\t\t\n\n\t\t\t\t\n";

    /** Discovers method parameter names from class metadata or debug information. */
    private static final ParameterNameDiscoverer PARAMETER_NAME_DISCOVERER =
            new DefaultParameterNameDiscoverer();

    /** Shared ObjectMapper for @RequestBody deserialization. */
    private static final ObjectMapper OBJECT_MAPPER;

    static {
        OBJECT_MAPPER = new ObjectMapper();
        // Use Jackson's thread-safe StdDateFormat instead of SimpleDateFormat
        OBJECT_MAPPER.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        OBJECT_MAPPER.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
    }

    /** Whether any mapped method uses {@link PathVariable}, requiring path-pattern matching. */
    private final boolean isRestfulUrl;

    /** Ant-style path matcher used for RESTful URL template extraction; null if not needed. */
    private final PathMatcher pathMatcher;

    /** The URL pattern string used for path-variable extraction; null if not RESTful. */
    private final String pathPattern;

    /** The view handler responsible for rendering the method return value into an HTTP response. */
    private final AbstractViewHandler viewHandler;

    /** Per-controller exception handler resolver; may be null if no @ExceptionHandler methods exist. */
    private ExceptionHandlerMethodResolver controllerExceptionResolver;

    /** Global exception handler resolvers from @ControllerAdvice beans. */
    private List<ExceptionHandlerMethodResolver> globalExceptionResolvers = Collections.emptyList();

    /** Ordered list of handler interceptors. */
    private List<HandlerInterceptor> interceptors = Collections.emptyList();

    /** CORS configuration from @CrossOrigin annotation (class or method level). */
    private CrossOrigin crossOrigin;

    /**
     * Constructs a resolver with a direct object reference for method invocation.
     *
     * @param url         the URL pattern this resolver handles
     * @param methods     a map of HTTP methods to their handler {@link Method} objects
     * @param invokeRef   the controller instance on which methods will be invoked
     * @param viewHandler the view handler for rendering responses
     */
    public RequestMappingResolver(String url, Map<HttpRequestMethod, Method> methods, Object invokeRef,
                                  AbstractViewHandler viewHandler) {
        super(url, methods, invokeRef);
        this.viewHandler = viewHandler;
        this.isRestfulUrl = resolveRestfulUrl(methods);
        if (this.isRestfulUrl) {
            this.pathMatcher = new AntPathMatcher();
            this.pathPattern = url;
        } else {
            this.pathMatcher = null;
            this.pathPattern = null;
        }
    }

    /**
     * Constructs a resolver using a Spring bean name for lazy lookup of the controller instance.
     *
     * @param url                  the URL pattern this resolver handles
     * @param methods              a map of HTTP methods to their handler {@link Method} objects
     * @param applicationContext   the Spring application context for bean lookup
     * @param invokeBeanName       the bean name of the controller
     * @param viewHandler          the view handler for rendering responses
     */
    public RequestMappingResolver(String url,
                                  Map<HttpRequestMethod, Method> methods,
                                  ApplicationContext applicationContext,
                                  String invokeBeanName,
                                  AbstractViewHandler viewHandler) {
        super(url, methods, applicationContext, invokeBeanName);
        this.viewHandler = viewHandler;
        this.isRestfulUrl = resolveRestfulUrl(methods);
        if (this.isRestfulUrl) {
            this.pathMatcher = new AntPathMatcher();
            this.pathPattern = url;
        } else {
            this.pathMatcher = null;
            this.pathPattern = null;
        }
    }

    // ----- Configuration setters -----

    /**
     * Sets the per-controller exception handler resolver.
     *
     * @param resolver the exception handler resolver for this controller
     */
    public void setControllerExceptionResolver(ExceptionHandlerMethodResolver resolver) {
        this.controllerExceptionResolver = resolver;
    }

    /**
     * Sets the global exception handler resolvers from @ControllerAdvice beans.
     *
     * @param resolvers the list of global exception resolvers
     */
    public void setGlobalExceptionResolvers(List<ExceptionHandlerMethodResolver> resolvers) {
        this.globalExceptionResolvers = resolvers != null ? resolvers : Collections.emptyList();
    }

    /**
     * Sets the handler interceptors to apply before/after handler execution.
     *
     * @param interceptors the ordered list of interceptors
     */
    public void setInterceptors(List<HandlerInterceptor> interceptors) {
        this.interceptors = interceptors != null ? interceptors : Collections.emptyList();
    }

    /**
     * Sets the CORS configuration from @CrossOrigin annotation.
     *
     * @param crossOrigin the CORS annotation, or null for no CORS
     */
    public void setCrossOrigin(CrossOrigin crossOrigin) {
        this.crossOrigin = crossOrigin;
    }

    // ----- URL pattern resolution -----

    /**
     * Inspects all mapped methods to determine whether any use {@link PathVariable} annotations.
     *
     * @param methods the map of HTTP methods to handler methods
     * @return {@code true} if at least one parameter is annotated with {@code @PathVariable}
     */
    private boolean resolveRestfulUrl(Map<HttpRequestMethod, Method> methods) {
        for (Method method : methods.values()) {
            for (Annotation[] paramAnnotations : method.getParameterAnnotations()) {
                for (Annotation annotation : paramAnnotations) {
                    if (annotation.annotationType() == PathVariable.class) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ----- Parameter metadata parsing -----

    /**
     * Parses method parameter metadata for all registered handler methods, resolving
     * parameter names from annotations when present, or falling back to the discovered
     * parameter name.
     *
     * @return an unmodifiable map from HTTP method to an ordered map of parameter name to type
     */
    @Override
    public Map<HttpRequestMethod, Map<String, Class>> parseMethodParameters() {
        Map<HttpRequestMethod, Map<String, Class>> tempMethodParamTypes = new HashMap<>();
        for (Map.Entry<HttpRequestMethod, Method> kMethodEntry : getMethods().entrySet()) {
            LinkedHashMap<String, Class> methodParams = new LinkedHashMap<>();
            String[] params = PARAMETER_NAME_DISCOVERER.getParameterNames(kMethodEntry.getValue());
            Annotation[][] parameterAnnotations = kMethodEntry.getValue().getParameterAnnotations();
            Class<?>[] parameterTypes = kMethodEntry.getValue().getParameterTypes();
            if (params != null && parameterTypes.length == params.length) {
                for (int i = 0; i < params.length; i++) {
                    String key = params[i];
                    // Check all parameter-binding annotations for name override
                    for (Annotation annotation : parameterAnnotations[i]) {
                        if (annotation.annotationType() == PathVariable.class) {
                            String value = ((PathVariable) annotation).value();
                            if (StringUtil.isNotBlank(value)) {
                                key = value;
                            }
                            break;
                        } else if (annotation.annotationType() == RequestParam.class) {
                            String value = ((RequestParam) annotation).value();
                            if (StringUtil.isNotBlank(value)) {
                                key = value;
                            }
                            break;
                        } else if (annotation.annotationType() == RequestHeader.class) {
                            String value = ((RequestHeader) annotation).value();
                            if (StringUtil.isNotBlank(value)) {
                                key = value;
                            }
                            break;
                        } else if (annotation.annotationType() == CookieValue.class) {
                            String value = ((CookieValue) annotation).value();
                            if (StringUtil.isNotBlank(value)) {
                                key = value;
                            }
                            break;
                        }
                        // @RequestBody does not override the parameter name
                    }
                    methodParams.put(key, parameterTypes[i]);
                }
            }
            tempMethodParamTypes.put(kMethodEntry.getKey(), methodParams);
        }
        return Collections.unmodifiableMap(tempMethodParamTypes);
    }

    /**
     * No-op for HTTP request mappings. HTTP is stateless, so session removal is not supported.
     *
     * @param sessionId the session identifier (ignored)
     */
    @Override
    public void removeSession(String sessionId) {
        log.debug("HttpSessionManager do not support remove session: {}", sessionId);
    }

    // ----- Main request resolution lifecycle -----

    /**
     * Resolves an inbound HTTP request by dispatching it to the matching handler method.
     *
     * <p>The full resolution lifecycle includes CORS handling, method resolution, interceptor
     * chain execution, parameter binding, handler invocation, ResponseEntity/ResponseStatus
     * processing, view rendering, exception handling, and response writing.
     *
     * @param ctx the Netty channel handler context
     * @param msg the full HTTP request message
     * @throws Exception if an unrecoverable error occurs during resolution
     */
    @Override
    public void resolve(ChannelHandlerContext ctx, FullHttpRequest msg) throws Exception {

        // Handle CORS preflight request (OPTIONS method)
        if (crossOrigin != null && "OPTIONS".equalsIgnoreCase(msg.method().name())) {
            handleCorsPreFlight(ctx, msg);
            return;
        }

        // Resolve the HTTP method and find the matching handler; fall back to ALL wildcard
        HttpRequestMethod requestMethod = HttpRequestMethod.getInstance(msg.method().name());
        Method invokeMethod = getMethod(requestMethod);
        if (invokeMethod == null) {
            requestMethod = HttpRequestMethod.ALL;
            invokeMethod = getMethod(requestMethod);
            if (invokeMethod == null) {
                ServiceHandlerUtil.HttpErrorMessage errorMsg =
                        new ServiceHandlerUtil.HttpErrorMessage(
                                HttpResponseStatus.METHOD_NOT_ALLOWED,
                                msg.uri(),
                                null,
                                null);
                ServiceHandlerUtil.sendError(ctx, msg, errorMsg);
                return;
            }
        }

        // Enforce consumes/produces content negotiation
        RequestMapping mappingAnnotation =
                AnnotatedElementUtils.findMergedAnnotation(invokeMethod, RequestMapping.class);
        if (mappingAnnotation != null) {
            // Validate Content-Type against consumes constraint
            String[] consumes = mappingAnnotation.consumes();
            if (consumes.length > 0) {
                String contentType = msg.headers().get(HttpHeaderNames.CONTENT_TYPE);
                if (!matchesMediaType(contentType, consumes)) {
                    ServiceHandlerUtil.HttpErrorMessage errorMsg =
                            new ServiceHandlerUtil.HttpErrorMessage(
                                    HttpResponseStatus.UNSUPPORTED_MEDIA_TYPE,
                                    msg.uri(),
                                    "Content-Type '" + contentType + "' is not supported. Supported: "
                                            + Arrays.toString(consumes),
                                    null);
                    ServiceHandlerUtil.sendError(ctx, msg, errorMsg);
                    return;
                }
            }
            // Validate Accept header against produces constraint
            String[] produces = mappingAnnotation.produces();
            if (produces.length > 0) {
                String acceptHeader = msg.headers().get(HttpHeaderNames.ACCEPT);
                if (acceptHeader != null && !acceptHeader.isEmpty()
                        && !"*/*".equals(acceptHeader.trim())
                        && !matchesMediaType(acceptHeader, produces)) {
                    ServiceHandlerUtil.HttpErrorMessage errorMsg =
                            new ServiceHandlerUtil.HttpErrorMessage(
                                    HttpResponseStatus.NOT_ACCEPTABLE,
                                    msg.uri(),
                                    "Accept '" + acceptHeader + "' is not supported. Supported: "
                                            + Arrays.toString(produces),
                                    null);
                    ServiceHandlerUtil.sendError(ctx, msg, errorMsg);
                    return;
                }
            }
        }

        // Build the request context that wraps the raw Netty request
        HttpRequestContext requestContext = new HttpRequestContext();
        requestContext.setChannelHandlerContext(ctx);
        requestContext.setHttpRequest(msg);

        // Parse query-string and form-encoded request parameters
        Map<String, String> requestParameterMap = ServiceHandlerUtil.parseRequestParameters(msg);
        requestContext.setRequestParameters(requestParameterMap);

        // Parse multipart files if the request is multipart/form-data
        String contentTypeHeader = msg.headers().get(HttpHeaderNames.CONTENT_TYPE);
        if (contentTypeHeader != null && contentTypeHeader.toLowerCase().contains("multipart/")) {
            parseMultipartFiles(msg, requestContext);
        }

        // Extract path variables from the URI for RESTful URL templates
        String rawUri = msg.uri();
        String path = new QueryStringDecoder(rawUri).path();
        Map<String, String> pathParameterMap = null;
        if (isRestfulUrl) {
            pathParameterMap = pathMatcher.extractUriTemplateVariables(pathPattern, path);
        }

        Object handlerBean = getInvokeRef();
        Exception handlerException = null;
        Object result = null;
        Method resolvedMethod = invokeMethod;
        HttpRequestMethod resolvedRequestMethod = requestMethod;

        // Track which interceptors passed preHandle for afterCompletion cleanup
        int interceptorIndex = -1;

        try {
            // Execute interceptor preHandle chain
            boolean preHandlePassed = true;
            for (int i = 0; i < interceptors.size(); i++) {
                if (!interceptors.get(i).preHandle(requestContext, handlerBean, resolvedMethod)) {
                    preHandlePassed = false;
                    // Interceptor rejected the request — send 403 Forbidden if no response was sent
                    if (ctx.channel().isActive()) {
                        ServiceHandlerUtil.HttpErrorMessage errorMsg =
                                new ServiceHandlerUtil.HttpErrorMessage(
                                        HttpResponseStatus.FORBIDDEN,
                                        msg.uri(),
                                        "Request rejected by interceptor",
                                        null);
                        ServiceHandlerUtil.sendError(ctx, msg, errorMsg);
                    }
                    // Call afterCompletion for interceptors that already passed preHandle
                    triggerAfterCompletion(requestContext, handlerBean, resolvedMethod, null, interceptorIndex);
                    return;
                }
                interceptorIndex = i;
            }

            // Bind request data to handler method parameters
            List<Object> parameters = resolveParameters(
                    resolvedMethod, resolvedRequestMethod, requestContext,
                    requestParameterMap, pathParameterMap, msg);

            // Invoke the handler method via reflection
            result = resolvedMethod.invoke(handlerBean, parameters.toArray());

            // Execute interceptor postHandle chain
            for (HandlerInterceptor interceptor : interceptors) {
                interceptor.postHandle(requestContext, handlerBean, resolvedMethod, result);
            }

        } catch (InvocationTargetException e) {
            // Unwrap the real exception from reflection invocation
            handlerException = (e.getCause() instanceof Exception)
                    ? (Exception) e.getCause()
                    : e;
            log.debug("Handler method threw exception: {}", handlerException.getMessage());
        } catch (IllegalArgumentException e) {
            // Parameter binding errors (missing required params, bad request body, etc.)
            handlerException = e;
            log.debug("Parameter binding error: {}", e.getMessage());
        } catch (Exception e) {
            handlerException = e;
            log.warn("Error during request handling: {}", e.getMessage());
        }

        try {
            // Attempt exception handler resolution if an exception occurred
            if (handlerException != null) {
                result = resolveExceptionWithHandlers(handlerException, resolvedMethod, requestContext);
                if (result == null) {
                    // Determine status code: 400 for binding errors, 500 for others
                    HttpResponseStatus status = (handlerException instanceof IllegalArgumentException)
                            ? HttpResponseStatus.BAD_REQUEST
                            : HttpResponseStatus.INTERNAL_SERVER_ERROR;
                    ServiceHandlerUtil.HttpErrorMessage errorMsg =
                            new ServiceHandlerUtil.HttpErrorMessage(
                                    status,
                                    msg.uri(),
                                    handlerException.getMessage(),
                                    handlerException);
                    ServiceHandlerUtil.sendError(ctx, msg, errorMsg);
                    return;
                }
            }

            // Build and write the HTTP response
            writeResponse(ctx, msg, result, resolvedMethod, requestContext);

        } finally {
            // Always execute interceptor afterCompletion chain (only for interceptors that passed preHandle)
            triggerAfterCompletion(requestContext, handlerBean, resolvedMethod, handlerException, interceptorIndex);
        }
    }

    /**
     * Triggers afterCompletion callbacks in reverse order for interceptors that passed preHandle.
     *
     * @param requestContext   the request context
     * @param handlerBean      the handler bean
     * @param resolvedMethod   the handler method
     * @param exception        the exception thrown during handling (may be null)
     * @param interceptorIndex the index of the last interceptor that passed preHandle (-1 for none)
     */
    private void triggerAfterCompletion(HttpRequestContext requestContext, Object handlerBean,
                                        Method resolvedMethod, Exception exception, int interceptorIndex) {
        for (int i = interceptorIndex; i >= 0; i--) {
            try {
                interceptors.get(i).afterCompletion(requestContext, handlerBean, resolvedMethod, exception);
            } catch (Exception ex) {
                log.warn("Interceptor afterCompletion threw exception", ex);
            }
        }
    }

    // ----- Parameter binding -----

    /**
     * Resolves all parameters for the handler method by inspecting annotations and types.
     *
     * @param invokeMethod       the handler method
     * @param requestMethod      the matched HTTP method key
     * @param requestContext     the request context
     * @param requestParameterMap parsed query/form parameters
     * @param pathParameterMap   extracted path variables (may be null)
     * @param msg                the full HTTP request
     * @return ordered list of resolved parameter values
     * @throws Exception if a required parameter is missing or body parsing fails
     */
    private List<Object> resolveParameters(Method invokeMethod,
                                           HttpRequestMethod requestMethod,
                                           HttpRequestContext requestContext,
                                           Map<String, String> requestParameterMap,
                                           Map<String, String> pathParameterMap,
                                           FullHttpRequest msg) throws Exception {

        Map<String, Class> methodParamType = getMethodParamType(requestMethod);
        Annotation[][] allParamAnnotations = invokeMethod.getParameterAnnotations();
        Class<?>[] parameterTypes = invokeMethod.getParameterTypes();

        List<Object> parameters = new ArrayList<>(methodParamType.size());
        Map<String, String> tempMethodParamType = new HashMap<>();

        int paramIndex = 0;
        for (Map.Entry<String, Class> methodParamEntry : methodParamType.entrySet()) {
            Annotation[] paramAnnotations = allParamAnnotations[paramIndex];
            Class<?> paramType = parameterTypes[paramIndex];
            String paramName = methodParamEntry.getKey();

            // Attempt annotation-based binding first (@RequestBody, @RequestHeader, @CookieValue, @RequestParam)
            Object resolved = resolveAnnotatedParameter(
                    paramAnnotations, paramType, paramName, requestContext, msg,
                    pathParameterMap, requestParameterMap);

            if (resolved != UNRESOLVED) {
                parameters.add(resolved);
                paramIndex++;
                continue;
            }

            // Standard binding: path variable → context injection → type conversion → object binding
            resolved = resolveStandardParameter(
                    paramType, paramName, requestContext, requestParameterMap,
                    pathParameterMap, tempMethodParamType);
            parameters.add(resolved);
            paramIndex++;
        }

        return parameters;
    }

    /** Sentinel object indicating a parameter was not resolved by annotation-based binding. */
    private static final Object UNRESOLVED = new Object();

    /**
     * Attempts to resolve a parameter from annotation-based binding strategies.
     * Returns {@link #UNRESOLVED} if no annotation-specific strategy applies.
     */
    private Object resolveAnnotatedParameter(Annotation[] annotations,
                                             Class<?> paramType,
                                             String paramName,
                                             HttpRequestContext requestContext,
                                             FullHttpRequest msg,
                                             Map<String, String> pathParameterMap,
                                             Map<String, String> requestParameterMap) throws Exception {
        for (Annotation annotation : annotations) {
            // @RequestBody — deserialize JSON request body
            if (annotation instanceof RequestBody) {
                return resolveRequestBody(msg, paramType, (RequestBody) annotation);
            }
            // @RequestHeader — bind an HTTP request header
            if (annotation instanceof RequestHeader) {
                return resolveRequestHeader(requestContext, paramType, paramName, (RequestHeader) annotation);
            }
            // @CookieValue — bind a cookie value
            if (annotation instanceof CookieValue) {
                return resolveCookieValue(requestContext, paramType, paramName, (CookieValue) annotation);
            }
            // @RequestParam — bind a query/form parameter with required/defaultValue support
            if (annotation instanceof RequestParam) {
                return resolveRequestParam(requestParameterMap, paramType, paramName, (RequestParam) annotation);
            }
            // @PathVariable is resolved via the standard binding path
        }
        return UNRESOLVED;
    }

    /**
     * Resolves a parameter annotated with @RequestParam from query string or form data.
     *
     * @param requestParameterMap the parsed request parameters
     * @param paramType           the target parameter type
     * @param paramName           the parameter name (used as fallback if annotation value is empty)
     * @param annotation          the @RequestParam annotation
     * @return the resolved parameter value, converted to the target type
     * @throws IllegalArgumentException if a required parameter is missing
     */
    private Object resolveRequestParam(Map<String, String> requestParameterMap, Class<?> paramType,
                                       String paramName, RequestParam annotation) {
        String queryParamName = StringUtil.isNotBlank(annotation.value()) ? annotation.value() : paramName;
        String paramValue = requestParameterMap.get(queryParamName);

        if (paramValue == null || paramValue.isEmpty()) {
            if (!NO_DEFAULT_VALUE.equals(annotation.defaultValue())) {
                paramValue = annotation.defaultValue();
            } else if (annotation.required()) {
                throw new IllegalArgumentException("Missing required request parameter: " + queryParamName);
            }
        }
        if (paramValue == null) {
            return null;
        }
        if (paramType == String.class) {
            return paramValue;
        }
        if (DataBindUtil.isBasicType(paramType)) {
            return DataBindUtil.parseStringToBasicType(paramValue, paramType);
        }
        return paramValue;
    }

    /**
     * Deserializes the HTTP request body from JSON into the target parameter type.
     *
     * @param msg        the HTTP request containing the body
     * @param paramType  the target Java type
     * @param annotation the @RequestBody annotation with configuration
     * @return the deserialized object, or null if body is empty and not required
     * @throws IllegalArgumentException if the body is missing when required, or if parsing fails
     */
    private Object resolveRequestBody(FullHttpRequest msg, Class<?> paramType,
                                      RequestBody annotation) throws Exception {
        ByteBuf content = msg.content();
        if (content == null || content.readableBytes() == 0) {
            if (annotation.required()) {
                throw new IllegalArgumentException("Request body is required but was empty");
            }
            return null;
        }
        String bodyStr = content.toString(StandardCharsets.UTF_8);
        if (bodyStr.trim().isEmpty()) {
            if (annotation.required()) {
                throw new IllegalArgumentException("Request body is required but was empty");
            }
            return null;
        }
        // If the target type is String, return the raw body
        if (paramType == String.class) {
            return bodyStr;
        }
        // Deserialize JSON into the target type
        try {
            return OBJECT_MAPPER.readValue(bodyStr, paramType);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Failed to parse request body as " + paramType.getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    /**
     * Resolves a parameter annotated with @RequestHeader.
     *
     * @param requestContext the HTTP request context
     * @param paramType      the target parameter type
     * @param paramName      the parameter name (used as fallback header name)
     * @param annotation     the @RequestHeader annotation
     * @return the resolved header value, converted to the target type
     * @throws IllegalArgumentException if a required header is missing
     */
    private Object resolveRequestHeader(HttpRequestContext requestContext, Class<?> paramType,
                                        String paramName, RequestHeader annotation) {
        String headerName = StringUtil.isNotBlank(annotation.value()) ? annotation.value() : paramName;
        String headerValue = requestContext.getRequestHeaders().get(headerName);

        if (headerValue == null) {
            if (!NO_DEFAULT_VALUE.equals(annotation.defaultValue())) {
                headerValue = annotation.defaultValue();
            } else if (annotation.required()) {
                throw new IllegalArgumentException("Missing required header: " + headerName);
            }
        }
        if (headerValue == null) {
            return null;
        }
        if (paramType == String.class) {
            return headerValue;
        }
        if (DataBindUtil.isBasicType(paramType)) {
            return DataBindUtil.parseStringToBasicType(headerValue, paramType);
        }
        return headerValue;
    }

    /**
     * Resolves a parameter annotated with @CookieValue.
     *
     * @param requestContext the HTTP request context
     * @param paramType      the target parameter type
     * @param paramName      the parameter name (used as fallback cookie name)
     * @param annotation     the @CookieValue annotation
     * @return the resolved cookie value, converted to the target type
     * @throws IllegalArgumentException if a required cookie is missing
     */
    private Object resolveCookieValue(HttpRequestContext requestContext, Class<?> paramType,
                                      String paramName, CookieValue annotation) {
        String cookieName = StringUtil.isNotBlank(annotation.value()) ? annotation.value() : paramName;
        String cookieValue = requestContext.getRequestCookies().get(cookieName);

        if (cookieValue == null) {
            if (!NO_DEFAULT_VALUE.equals(annotation.defaultValue())) {
                cookieValue = annotation.defaultValue();
            } else if (annotation.required()) {
                throw new IllegalArgumentException("Missing required cookie: " + cookieName);
            }
        }
        if (cookieValue == null) {
            return null;
        }
        if (paramType == String.class) {
            return cookieValue;
        }
        if (DataBindUtil.isBasicType(paramType)) {
            return DataBindUtil.parseStringToBasicType(cookieValue, paramType);
        }
        return cookieValue;
    }

    /**
     * Standard parameter resolution: path variables, context injection, basic type conversion,
     * and complex object binding.
     */
    private Object resolveStandardParameter(Class<?> paramType,
                                            String paramName,
                                            HttpRequestContext requestContext,
                                            Map<String, String> requestParameterMap,
                                            Map<String, String> pathParameterMap,
                                            Map<String, String> tempMethodParamType) {
        // Try path variable first
        if (isRestfulUrl && pathParameterMap != null) {
            String value = pathParameterMap.get(paramName);
            if (StringUtil.isNotBlank(value)) {
                return DataBindUtil.parseStringToBasicType(
                        ServiceHandlerUtil.decodeRequestString(value), paramType);
            }
        }

        // Inject framework context objects by type
        if (paramType == HttpRequestContext.class) {
            return requestContext;
        } else if (paramType == HttpRequest.class) {
            return requestContext.getHttpRequest();
        } else if (paramType == HttpHeaders.class) {
            return requestContext.getRequestHeaders();
        } else if (paramType == ChannelHandlerContext.class) {
            return requestContext.getChannelHandlerContext();
        } else if (paramType == MultipartFile.class) {
            // Inject uploaded file by parameter name
            return requestContext.getMultipartFile(paramName);
        } else if (paramType == MultipartFile[].class) {
            // Inject all uploaded files as an array for the given field name
            List<MultipartFile> files = requestContext.getMultipartFiles().get(paramName);
            return files != null ? files.toArray(new MultipartFile[0]) : new MultipartFile[0];
        }

        // Basic type or String binding from request parameters
        if (paramType == String.class) {
            return requestParameterMap.get(paramName);
        }
        if (DataBindUtil.isBasicType(paramType)) {
            return DataBindUtil.parseStringToBasicType(
                    requestParameterMap.get(paramName), paramType);
        }

        // Complex object binding: collect request params with matching prefix and bind to POJO
        for (Map.Entry<String, String> entry : requestParameterMap.entrySet()) {
            if (entry.getKey().startsWith(paramName)) {
                tempMethodParamType.put(entry.getKey(), requestParameterMap.get(entry.getKey()));
            }
        }
        Object result = DataBindUtil.parseStringToObject(tempMethodParamType, paramType);
        tempMethodParamType.clear();
        return result;
    }

    // ----- Exception handling -----

    /**
     * Attempts to resolve an exception via @ExceptionHandler methods.
     * Checks per-controller handlers first, then global @ControllerAdvice handlers.
     *
     * @param exception      the thrown exception
     * @param invokeMethod   the handler method that threw the exception
     * @param requestContext the request context
     * @return the result from the exception handler, or null if no handler was found
     */
    private Object resolveExceptionWithHandlers(Exception exception, Method invokeMethod,
                                                HttpRequestContext requestContext) {
        // Try per-controller @ExceptionHandler first
        if (controllerExceptionResolver != null) {
            Object result = invokeExceptionHandler(controllerExceptionResolver, exception, requestContext);
            if (result != null) {
                return result;
            }
        }

        // Try global @ControllerAdvice exception handlers
        for (ExceptionHandlerMethodResolver resolver : globalExceptionResolvers) {
            Object result = invokeExceptionHandler(resolver, exception, requestContext);
            if (result != null) {
                return result;
            }
        }

        return null;
    }

    /**
     * Invokes a matched exception handler method with the appropriate parameters.
     *
     * @param resolver       the exception handler resolver containing the target method
     * @param exception      the exception to handle
     * @param requestContext the HTTP request context
     * @return the handler result, or null if no method matched or invocation failed
     */
    private Object invokeExceptionHandler(ExceptionHandlerMethodResolver resolver,
                                          Exception exception,
                                          HttpRequestContext requestContext) {
        Method handler = resolver.resolveMethod(exception);
        if (handler == null) {
            return null;
        }

        try {
            // Build parameters for the exception handler method
            Class<?>[] paramTypes = handler.getParameterTypes();
            Object[] params = new Object[paramTypes.length];
            for (int i = 0; i < paramTypes.length; i++) {
                if (Throwable.class.isAssignableFrom(paramTypes[i])) {
                    params[i] = exception;
                } else if (paramTypes[i] == HttpRequestContext.class) {
                    params[i] = requestContext;
                } else {
                    params[i] = null;
                }
            }

            return handler.invoke(resolver.getHandlerBean(), params);
        } catch (Exception e) {
            log.warn("Failed to invoke @ExceptionHandler method {}: {}", handler.getName(), e.getMessage());
            return null;
        }
    }

    // ----- Response writing -----

    /**
     * Builds and writes the HTTP response, handling FileDownload, ResponseEntity,
     * @ResponseStatus, CORS, cookies, and custom response headers.
     */
    private void writeResponse(ChannelHandlerContext ctx, FullHttpRequest msg, Object result,
                               Method invokeMethod, HttpRequestContext requestContext) {

        // Extract FileDownload from result (direct or wrapped in ResponseEntity)
        FileDownload fileDownload = extractFileDownload(result);
        if (fileDownload != null) {
            writeFileDownloadResponse(ctx, msg, result, fileDownload, requestContext);
            return;
        }

        FullHttpResponse response;

        if (result instanceof ResponseEntity) {
            // Handle ResponseEntity return type — extract status, headers, and body
            ResponseEntity<?> responseEntity = (ResponseEntity<?>) result;
            Object body = responseEntity.getBody();

            // Delegate body rendering to the view handler
            response = viewHandler.handleView(body);

            // Override status code from ResponseEntity
            response.setStatus(HttpResponseStatus.valueOf(responseEntity.getStatusCode()));

            // Apply ResponseEntity custom headers
            for (Map.Entry<String, List<String>> headerEntry : responseEntity.getHeaders().entrySet()) {
                for (String headerValue : headerEntry.getValue()) {
                    response.headers().add(headerEntry.getKey(), headerValue);
                }
            }
        } else {
            // Standard return value — use view handler directly
            if (result == null) {
                log.debug("Handler return value is null");
            }
            response = viewHandler.handleView(result);
        }

        // Apply @ResponseStatus if present (does not override ResponseEntity status)
        ResponseStatus responseStatus = invokeMethod.getAnnotation(ResponseStatus.class);
        if (responseStatus != null && !(result instanceof ResponseEntity)) {
            if (StringUtil.isNotBlank(responseStatus.reason())) {
                response.setStatus(new HttpResponseStatus(responseStatus.code(), responseStatus.reason()));
            } else {
                response.setStatus(HttpResponseStatus.valueOf(responseStatus.code()));
            }
        }

        applyCommonResponseHeaders(response, msg, requestContext);

        // Flush the response to the client
        ChannelFuture writeFuture = ctx.writeAndFlush(response);
        writeFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (!future.isSuccess()) {
                    getHttpRuntimeRecorder().recordHttpResponseWriteFailure();
                    log.warn("Write http response failed, close channel. uri={}", msg.uri(), future.cause());
                    future.channel().close();
                }
            }
        });
    }

    /**
     * Extracts a {@link FileDownload} from the handler return value — either directly
     * or unwrapped from a {@link ResponseEntity}.
     *
     * @param result the handler method return value
     * @return the FileDownload instance, or {@code null} if not a file download
     */
    private FileDownload extractFileDownload(Object result) {
        if (result instanceof FileDownload) {
            return (FileDownload) result;
        }
        if (result instanceof ResponseEntity) {
            Object body = ((ResponseEntity<?>) result).getBody();
            if (body instanceof FileDownload) {
                return (FileDownload) body;
            }
        }
        return null;
    }

    /**
     * Writes a file download response, using chunked transfer for {@link java.io.File} sources
     * and buffered content for byte array and InputStream sources.
     *
     * <p>Sets Content-Type, Content-Length, and Content-Disposition headers automatically.
     * ResponseEntity headers (if present) are also applied.
     */
    private void writeFileDownloadResponse(ChannelHandlerContext ctx, FullHttpRequest msg,
                                           Object result, FileDownload fileDownload,
                                           HttpRequestContext requestContext) {
        try {
            // Determine HTTP status code
            int statusCode = 200;
            Map<String, List<String>> entityHeaders = Collections.emptyMap();
            if (result instanceof ResponseEntity) {
                ResponseEntity<?> entity = (ResponseEntity<?>) result;
                statusCode = entity.getStatusCode();
                entityHeaders = entity.getHeaders();
            }

            if (fileDownload.getFile() != null) {
                // File-based download — use Netty chunked file for zero-copy streaming
                java.io.File downloadFile = fileDownload.getFile();
                if (!downloadFile.exists() || !downloadFile.isFile()) {
                    ServiceHandlerUtil.HttpErrorMessage errorMsg =
                            new ServiceHandlerUtil.HttpErrorMessage(
                                    HttpResponseStatus.NOT_FOUND,
                                    msg.uri(),
                                    "File not found: " + downloadFile.getName(),
                                    null);
                    ServiceHandlerUtil.sendError(ctx, msg, errorMsg);
                    return;
                }

                RandomAccessFile raf = new RandomAccessFile(downloadFile, "r");
                try {
                    long fileLength = raf.length();

                    HttpResponse response = new DefaultHttpResponse(
                            HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(statusCode));
                    HttpUtil.setContentLength(response, fileLength);
                    response.headers().set(HttpHeaderNames.CONTENT_TYPE, fileDownload.getContentType());
                    setContentDisposition(response, fileDownload);
                    applyEntityHeaders(response, entityHeaders);
                    applyCommonResponseHeaders(response, msg, requestContext);

                    // Write in three parts: headers → chunked content → end marker
                    // ChunkedFile takes ownership of the RandomAccessFile and closes it when done
                    ctx.write(response);
                    ctx.write(new HttpChunkedInput(new ChunkedFile(raf, 0, fileLength, 8192)));
                    raf = null; // Ownership transferred to ChunkedFile — do not close manually
                    ChannelFuture writeFuture = ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
                    writeFuture.addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture future) {
                            if (!future.isSuccess()) {
                                getHttpRuntimeRecorder().recordHttpResponseWriteFailure();
                                log.warn("Write file download response failed. uri={}", msg.uri(), future.cause());
                                future.channel().close();
                            }
                        }
                    });
                } finally {
                    // Close RAF only if ownership was NOT transferred to ChunkedFile
                    if (raf != null) {
                        raf.close();
                    }
                }

            } else {
                // Byte array or InputStream — write as a FullHttpResponse
                byte[] content;
                if (fileDownload.getBytes() != null) {
                    content = fileDownload.getBytes();
                } else if (fileDownload.getInputStream() != null) {
                    content = readInputStream(fileDownload.getInputStream());
                } else {
                    content = new byte[0];
                }

                ByteBuf buf = Unpooled.wrappedBuffer(content);
                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(statusCode), buf);
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, fileDownload.getContentType());
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.length);
                setContentDisposition(response, fileDownload);
                applyEntityHeaders(response, entityHeaders);
                applyCommonResponseHeaders(response, msg, requestContext);

                ChannelFuture writeFuture = ctx.writeAndFlush(response);
                writeFuture.addListener(new ChannelFutureListener() {
                    @Override
                    public void operationComplete(ChannelFuture future) {
                        if (!future.isSuccess()) {
                            getHttpRuntimeRecorder().recordHttpResponseWriteFailure();
                            log.warn("Write file download response failed. uri={}", msg.uri(), future.cause());
                            future.channel().close();
                        }
                    }
                });
            }

        } catch (Exception e) {
            log.warn("Failed to write file download response: {}", e.getMessage(), e);
            ServiceHandlerUtil.HttpErrorMessage errorMsg =
                    new ServiceHandlerUtil.HttpErrorMessage(
                            HttpResponseStatus.INTERNAL_SERVER_ERROR,
                            msg.uri(),
                            "File download failed: " + e.getMessage(),
                            e);
            ServiceHandlerUtil.sendError(ctx, msg, errorMsg);
        }
    }

    /**
     * Sets the Content-Disposition header for file download.
     * Sanitizes the filename to prevent HTTP response header injection.
     */
    private void setContentDisposition(HttpResponse response, FileDownload fileDownload) {
        String disposition = fileDownload.isInline() ? "inline" : "attachment";
        if (fileDownload.getFilename() != null && !fileDownload.getFilename().isEmpty()) {
            // Sanitize filename: remove characters that could enable header injection
            String sanitized = fileDownload.getFilename()
                    .replace("\"", "")
                    .replace("\\", "")
                    .replace("\r", "")
                    .replace("\n", "");
            disposition += "; filename=\"" + sanitized + "\"";
        }
        response.headers().set("Content-Disposition", disposition);
    }

    /**
     * Applies ResponseEntity headers to an HttpResponse.
     */
    private void applyEntityHeaders(HttpResponse response, Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            for (String value : entry.getValue()) {
                response.headers().add(entry.getKey(), value);
            }
        }
    }

    /**
     * Applies common response headers: CORS, cookies, and custom headers from the request context.
     */
    private void applyCommonResponseHeaders(HttpResponse response, FullHttpRequest msg,
                                            HttpRequestContext requestContext) {
        // Attach CORS headers if @CrossOrigin is configured
        if (crossOrigin != null) {
            applyCorsHeaders(response, msg);
        }

        // Attach response cookies
        List<Cookie> responseCookies = requestContext.getResponseCookies();
        if (responseCookies != null) {
            for (Cookie responseCookie : responseCookies) {
                String cookieHeader = Cookie.toHeaderStrings(responseCookie);
                if (cookieHeader != null) {
                    response.headers().add("Set-Cookie", cookieHeader);
                }
            }
        }

        // Merge custom response headers set during handler execution.
        // setAll() replaces all existing values for each header name in the source,
        // so user-defined headers intentionally override auto-generated ones.
        if (requestContext.getResponseHeaders() != null) {
            response.headers().setAll(requestContext.getResponseHeaders());
        }
    }

    /**
     * Reads an entire InputStream into a byte array.
     */
    private byte[] readInputStream(InputStream inputStream) throws IOException {
        try (InputStream is = inputStream) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            while ((bytesRead = is.read(buffer)) != -1) {
                baos.write(buffer, 0, bytesRead);
            }
            return baos.toByteArray();
        }
    }

    // ----- Multipart file parsing -----

    /**
     * Parses multipart/form-data request body and extracts uploaded files into the request context.
     *
     * <p>Uses Netty's {@link HttpPostRequestDecoder} to parse the multipart body. Form fields
     * ({@link Attribute} instances) are skipped here since they are already handled by
     * {@link ServiceHandlerUtil#parseRequestParameters}. Only {@link FileUpload} instances
     * are extracted and wrapped in {@link MultipartFile} objects.
     *
     * @param msg            the full HTTP request
     * @param requestContext the request context to populate with uploaded files
     */
    private void parseMultipartFiles(FullHttpRequest msg, HttpRequestContext requestContext) {
        HttpPostRequestDecoder decoder = null;
        try {
            decoder = new HttpPostRequestDecoder(msg);
            List<InterfaceHttpData> bodyData = decoder.getBodyHttpDatas();
            for (InterfaceHttpData data : bodyData) {
                if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
                    FileUpload fileUpload = (FileUpload) data;
                    if (fileUpload.isCompleted()) {
                        // Copy file content to a byte array; decoder.destroy() will release the FileUpload
                        byte[] content = fileUpload.get();
                        MultipartFile multipartFile = new MultipartFile(
                                fileUpload.getName(),
                                fileUpload.getFilename(),
                                fileUpload.getContentType(),
                                content);
                        requestContext.addMultipartFile(multipartFile);
                        log.debug("Parsed multipart file: name={}, filename={}, size={}",
                                fileUpload.getName(), fileUpload.getFilename(), content.length);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse multipart request: {}", e.getMessage(), e);
        } finally {
            if (decoder != null) {
                decoder.destroy();
            }
        }
    }

    // ----- CORS support -----

    /**
     * Handles a CORS preflight (OPTIONS) request by responding with 204 No Content
     * and the appropriate Access-Control-* headers.
     */
    private void handleCorsPreFlight(ChannelHandlerContext ctx, FullHttpRequest msg) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.NO_CONTENT, Unpooled.EMPTY_BUFFER);
        applyCorsHeaders(response, msg);
        ctx.writeAndFlush(response);
    }

    /**
     * Applies CORS response headers based on the @CrossOrigin annotation configuration.
     */
    private void applyCorsHeaders(HttpResponse response, FullHttpRequest msg) {
        if (crossOrigin == null) {
            return;
        }

        // Access-Control-Allow-Origin
        String[] origins = crossOrigin.origins();
        String requestOrigin = msg.headers().get("Origin");
        if (origins.length == 1 && "*".equals(origins[0])) {
            // When allowCredentials is true, wildcard "*" is invalid per CORS spec;
            // echo back the request Origin instead and add Vary: Origin
            if (crossOrigin.allowCredentials() && requestOrigin != null) {
                response.headers().set("Access-Control-Allow-Origin", requestOrigin);
                response.headers().set("Vary", "Origin");
            } else {
                response.headers().set("Access-Control-Allow-Origin", "*");
            }
        } else if (requestOrigin != null) {
            for (String origin : origins) {
                if (origin.equals(requestOrigin)) {
                    response.headers().set("Access-Control-Allow-Origin", requestOrigin);
                    response.headers().set("Vary", "Origin");
                    break;
                }
            }
        }

        // Access-Control-Allow-Methods
        String[] allowedMethods = crossOrigin.allowedMethods();
        if (allowedMethods.length > 0) {
            response.headers().set("Access-Control-Allow-Methods", String.join(", ", allowedMethods));
        } else {
            // Default: derive from the methods supported by this mapping
            Set<HttpRequestMethod> methodKeys = getMethodKey();
            if (methodKeys != null) {
                StringBuilder methods = new StringBuilder();
                for (HttpRequestMethod m : methodKeys) {
                    if (methods.length() > 0) methods.append(", ");
                    methods.append(m.name());
                }
                response.headers().set("Access-Control-Allow-Methods", methods.toString());
            }
        }

        // Access-Control-Allow-Headers
        String[] allowedHeaders = crossOrigin.allowedHeaders();
        if (allowedHeaders.length > 0) {
            response.headers().set("Access-Control-Allow-Headers", String.join(", ", allowedHeaders));
        }

        // Access-Control-Expose-Headers
        String[] exposedHeaders = crossOrigin.exposedHeaders();
        if (exposedHeaders.length > 0) {
            response.headers().set("Access-Control-Expose-Headers", String.join(", ", exposedHeaders));
        }

        // Access-Control-Allow-Credentials
        if (crossOrigin.allowCredentials()) {
            response.headers().set("Access-Control-Allow-Credentials", "true");
        }

        // Access-Control-Max-Age
        if (crossOrigin.maxAge() >= 0) {
            response.headers().set("Access-Control-Max-Age", String.valueOf(crossOrigin.maxAge()));
        }
    }

    // ----- Content negotiation support -----

    /**
     * Checks whether a request media type (from Content-Type or Accept header) matches
     * any of the declared media type patterns.
     *
     * <p>Supports wildcard matching: a pattern of {@code "application/*"} matches
     * {@code "application/json"}, and {@code "*&#47;*"} matches any type. Media type
     * parameters (e.g. {@code "; charset=UTF-8"}) are stripped before comparison.
     *
     * @param requestMediaType the media type from the request header (may be {@code null})
     * @param declaredTypes    the media types declared in the mapping annotation
     * @return {@code true} if the request type matches at least one declared type
     */
    private boolean matchesMediaType(String requestMediaType, String[] declaredTypes) {
        if (requestMediaType == null || requestMediaType.isEmpty()) {
            // No Content-Type or Accept header — allow (lenient matching)
            return true;
        }

        // Accept header may contain multiple types separated by commas
        String[] requestTypes = requestMediaType.split(",");
        for (String reqType : requestTypes) {
            // Strip quality factor and parameters (e.g. ";q=0.9", "; charset=UTF-8")
            String normalizedReq = reqType.trim();
            int semicolonIdx = normalizedReq.indexOf(';');
            if (semicolonIdx > 0) {
                normalizedReq = normalizedReq.substring(0, semicolonIdx).trim();
            }

            if ("*/*".equals(normalizedReq)) {
                return true;
            }

            for (String declaredType : declaredTypes) {
                String normalizedDeclared = declaredType.trim();
                int dSemicolon = normalizedDeclared.indexOf(';');
                if (dSemicolon > 0) {
                    normalizedDeclared = normalizedDeclared.substring(0, dSemicolon).trim();
                }

                if (normalizedReq.equalsIgnoreCase(normalizedDeclared)) {
                    return true;
                }

                // Wildcard matching: "application/*" matches "application/json"
                if (normalizedDeclared.endsWith("/*")) {
                    String prefix = normalizedDeclared.substring(0, normalizedDeclared.length() - 1);
                    if (normalizedReq.toLowerCase().startsWith(prefix.toLowerCase())) {
                        return true;
                    }
                }
                if (normalizedReq.endsWith("/*")) {
                    String prefix = normalizedReq.substring(0, normalizedReq.length() - 1);
                    if (normalizedDeclared.toLowerCase().startsWith(prefix.toLowerCase())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

}
