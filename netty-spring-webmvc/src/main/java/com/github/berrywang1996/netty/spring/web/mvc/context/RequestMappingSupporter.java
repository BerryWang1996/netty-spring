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

import com.github.berrywang1996.netty.spring.web.context.MappingSupporter;
import com.github.berrywang1996.netty.spring.web.mvc.bind.ExceptionHandlerMethodResolver;
import com.github.berrywang1996.netty.spring.web.mvc.bind.RequestMappingResolver;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.*;
import com.github.berrywang1996.netty.spring.web.mvc.consts.HttpRequestMethod;
import com.github.berrywang1996.netty.spring.web.mvc.view.AbstractViewHandler;
import com.github.berrywang1996.netty.spring.web.mvc.view.HtmlViewHandler;
import com.github.berrywang1996.netty.spring.web.mvc.view.JsonViewHandler;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Scans the Spring application context for controller beans annotated with HTTP mapping
 * annotations and builds a map of URL patterns to {@link RequestMappingResolver} instances.
 *
 * <p>During initialization this supporter:
 * <ol>
 *   <li>Discovers all {@link Component}-annotated beans with HTTP mapping annotations</li>
 *   <li>Registers URL-to-resolver mappings with appropriate view handlers</li>
 *   <li>Scans {@link ControllerAdvice @ControllerAdvice} beans for global {@link ExceptionHandler} methods</li>
 *   <li>Discovers {@link HandlerInterceptor} beans for the interceptor chain</li>
 *   <li>Detects {@link CrossOrigin @CrossOrigin} on controllers and methods for CORS support</li>
 *   <li>Wires per-controller {@link ExceptionHandler @ExceptionHandler} methods into resolvers</li>
 * </ol>
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class RequestMappingSupporter implements MappingSupporter<RequestMappingResolver> {

    /** Server startup properties (e.g. port) used for port-based mapping filtering. */
    private NettyServerStartupProperties startupProperties;

    /** The Spring application context used for bean discovery. */
    private ApplicationContext applicationContext;

    /** Accumulated map of URL patterns to their resolvers. */
    private final Map<String, RequestMappingResolver> resolverMap = new HashMap<>();

    /** Global exception handler resolvers from @ControllerAdvice beans. */
    private final List<ExceptionHandlerMethodResolver> globalExceptionResolvers = new ArrayList<>();

    /** Ordered list of handler interceptors. */
    private final List<HandlerInterceptor> interceptors = new ArrayList<>();

    /** Map of controller class to its per-controller exception handler resolver. */
    private final Map<Class<?>, ExceptionHandlerMethodResolver> controllerExceptionResolvers = new HashMap<>();

    /**
     * Scans all {@link Component}-annotated beans in the application context, discovers
     * methods annotated with HTTP mapping annotations, and builds the resolver map.
     * Also discovers @ControllerAdvice, HandlerInterceptor, and @CrossOrigin beans.
     *
     * @param startupProperties  server startup properties for port filtering
     * @param applicationContext the Spring application context to scan
     * @return a map of URL patterns to {@link RequestMappingResolver} instances
     */
    @Override
    public Map<String, RequestMappingResolver> initMappingResolverMap(NettyServerStartupProperties startupProperties,
                                                                      ApplicationContext applicationContext) {

        this.startupProperties = startupProperties;
        this.applicationContext = applicationContext;

        // Phase 1: Discover global @ControllerAdvice exception handlers
        initControllerAdvice();

        // Phase 2: Discover HandlerInterceptor beans
        initInterceptors();

        // Phase 3: Scan controllers and register request mapping resolvers
        initRequestMappings();

        // Phase 4: Wire exception resolvers and interceptors into each resolver
        wireResolverDependencies();

        return resolverMap;
    }

    // ----- Phase 1: @ControllerAdvice scanning -----

    /**
     * Scans for beans annotated with {@link ControllerAdvice @ControllerAdvice} and builds
     * global {@link ExceptionHandlerMethodResolver} instances from their @ExceptionHandler methods.
     *
     * <p>Uses {@code applicationContext.getType(beanName)} instead of {@code getBean()} to avoid
     * eagerly instantiating beans during bootstrap, which could trigger circular dependency errors.
     * The actual bean instance is resolved lazily when an exception handler is invoked.
     */
    private void initControllerAdvice() {
        String[] adviceBeanNames = applicationContext.getBeanNamesForAnnotation(ControllerAdvice.class);
        for (String beanName : adviceBeanNames) {
            Class<?> adviceClass = applicationContext.getType(beanName);
            if (adviceClass == null) {
                log.debug("Skip @ControllerAdvice bean {} because bean type is not resolvable yet.", beanName);
                continue;
            }
            ExceptionHandlerMethodResolver resolver =
                    new ExceptionHandlerMethodResolver(adviceClass, beanName, applicationContext);
            if (resolver.hasHandlers()) {
                globalExceptionResolvers.add(resolver);
                log.info("Detected @ControllerAdvice: {} with @ExceptionHandler methods", adviceClass.getSimpleName());
            }
        }
    }

    // ----- Phase 2: HandlerInterceptor discovery -----

    /**
     * Discovers all {@link HandlerInterceptor} beans in the application context and
     * adds them to the interceptor chain.
     */
    private void initInterceptors() {
        Map<String, HandlerInterceptor> interceptorBeans =
                applicationContext.getBeansOfType(HandlerInterceptor.class);
        for (Map.Entry<String, HandlerInterceptor> entry : interceptorBeans.entrySet()) {
            interceptors.add(entry.getValue());
            log.info("Registered HandlerInterceptor: {}", entry.getKey());
        }
    }

    // ----- Phase 3: Request mapping scanning -----

    /**
     * Scans all Component beans for @RequestMapping annotations and registers resolvers.
     */
    private void initRequestMappings() {
        String[] beanNames = applicationContext.getBeanNamesForAnnotation(Component.class);
        log.debug("Scanning for HTTP mapping annotations (DeleteMapping, GetMapping, PostMapping, PutMapping, " +
                "PatchMapping, RequestMapping)");

        for (String beanName : beanNames) {
            Class<?> beanType = applicationContext.getType(beanName);
            if (beanType == null) {
                log.debug("Skip bean {} because bean type is not resolvable yet.", beanName);
                continue;
            }

            // Build per-controller exception handler resolver if @ExceptionHandler methods exist.
            // Uses the lazy constructor to avoid triggering bean creation during bootstrap.
            ExceptionHandlerMethodResolver controllerExceptionResolver = null;
            for (Method m : beanType.getDeclaredMethods()) {
                if (m.isAnnotationPresent(ExceptionHandler.class)) {
                    if (controllerExceptionResolver == null) {
                        controllerExceptionResolver =
                                new ExceptionHandlerMethodResolver(beanType, beanName, applicationContext);
                        controllerExceptionResolvers.put(beanType, controllerExceptionResolver);
                        log.debug("Found @ExceptionHandler methods in controller {}", beanType.getSimpleName());
                    }
                    break;
                }
            }

            // Detect class-level @CrossOrigin
            CrossOrigin classCrossOrigin = beanType.getAnnotation(CrossOrigin.class);

            // Scan all public methods for HTTP mapping annotations
            Method[] methods = beanType.getMethods();
            for (Method method : methods) {

                RequestMapping annotation = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (annotation != null) {

                    log.debug("Found annotation {} at method {}", annotation, method);

                    // Skip this mapping if it specifies ports and none match the current server port
                    if (annotation.port().length != 0) {
                        boolean notMap = true;
                        for (int port : annotation.port()) {
                            if (port == this.startupProperties.getPort()) {
                                notMap = false;
                                break;
                            }
                        }
                        if (notMap) {
                            log.debug("The method marked ports did not contained port {}",
                                    this.startupProperties.getPort());
                            continue;
                        }
                    }

                    // Resolve the full URL by combining class-level and method-level paths
                    List<String> mappingUrls = getMappingUrls(method);

                    log.info("Mapping request {{}{}} onto {}",
                            mappingUrls,
                            annotation.method().length > 0 ? ",method=" + Arrays.toString(annotation.method()) : "",
                            method);

                    // Determine @CrossOrigin: method-level overrides class-level
                    CrossOrigin methodCrossOrigin = method.getAnnotation(CrossOrigin.class);
                    CrossOrigin effectiveCorsConfig = methodCrossOrigin != null ? methodCrossOrigin : classCrossOrigin;

                    for (String url : mappingUrls) {
                        if (this.resolverMap.containsKey(url)) {

                            RequestMappingResolver resolver = this.resolverMap.get(url);

                            // Fail fast if the same URL + HTTP method combination is already mapped
                            if (isDuplicateMapping(annotation.method(), resolver)) {
                                throw new IllegalStateException("Ambiguous mapping uri \"" + url + "\". Cannot map " +
                                        "method " + method);
                            }

                            // URL already exists: merge the new HTTP method(s) into the existing resolver
                            Map<HttpRequestMethod, Method> methodMap = new HashMap<>(resolver.getMethods());
                            if (annotation.method().length == 0) {
                                methodMap.put(HttpRequestMethod.ALL, method);
                            } else {
                                for (HttpRequestMethod httpRequestMethod : annotation.method()) {
                                    methodMap.put(httpRequestMethod, method);
                                }
                            }
                            RequestMappingResolver newResolver = new RequestMappingResolver(url, methodMap,
                                    this.applicationContext, beanName, getViewHandler(method));
                            if (effectiveCorsConfig != null) {
                                newResolver.setCrossOrigin(effectiveCorsConfig);
                            }
                            if (controllerExceptionResolver != null) {
                                newResolver.setControllerExceptionResolver(controllerExceptionResolver);
                            }
                            this.resolverMap.put(url, newResolver);

                        } else {

                            // First time this URL is seen: create a new resolver
                            Map<HttpRequestMethod, Method> methodMap = new HashMap<>();
                            if (annotation.method().length == 0) {
                                methodMap.put(HttpRequestMethod.ALL, method);
                            } else {
                                for (HttpRequestMethod httpRequestMethod : annotation.method()) {
                                    methodMap.put(httpRequestMethod, method);
                                }
                            }
                            RequestMappingResolver newResolver = new RequestMappingResolver(url, methodMap,
                                    this.applicationContext, beanName, getViewHandler(method));
                            if (effectiveCorsConfig != null) {
                                newResolver.setCrossOrigin(effectiveCorsConfig);
                            }
                            if (controllerExceptionResolver != null) {
                                newResolver.setControllerExceptionResolver(controllerExceptionResolver);
                            }
                            this.resolverMap.put(url, newResolver);
                        }
                    }
                }
            }
        }
    }

    // ----- Phase 4: Wire dependencies into resolvers -----

    /**
     * Wires global exception resolvers and handler interceptors into each registered resolver.
     *
     * <p>Per-controller exception resolvers are already wired during {@link #initRequestMappings()}
     * to avoid calling {@code resolver.getInvokeRef()} which would trigger eager bean creation
     * and potentially cause circular dependency errors.
     */
    private void wireResolverDependencies() {
        for (RequestMappingResolver resolver : resolverMap.values()) {
            // Set global exception resolvers
            if (!globalExceptionResolvers.isEmpty()) {
                resolver.setGlobalExceptionResolvers(globalExceptionResolvers);
            }

            // Set handler interceptors
            if (!interceptors.isEmpty()) {
                resolver.setInterceptors(interceptors);
            }
        }
    }

    // ----- Utility methods -----

    /**
     * Determines the appropriate view handler for the given controller method.
     * <p>
     * Returns a {@link JsonViewHandler} if the method or its declaring class is annotated
     * with {@link ResponseBody} or {@link RestController}; otherwise returns an
     * {@link HtmlViewHandler}.
     *
     * @param method the handler method to inspect
     * @return the view handler to use for rendering the method's return value
     */
    private AbstractViewHandler getViewHandler(Method method) {
        if (method.isAnnotationPresent(ResponseBody.class) ||
                method.getDeclaringClass().isAnnotationPresent(ResponseBody.class) ||
                method.getDeclaringClass().isAnnotationPresent(RestController.class)) {
            return new JsonViewHandler();
        } else {
            return new HtmlViewHandler();
        }
    }

    /**
     * Checks whether registering the given HTTP methods would conflict with methods
     * already registered in an existing resolver for the same URL.
     *
     * @param methods  the HTTP methods being registered
     * @param resolver the existing resolver for the same URL
     * @return {@code true} if any HTTP method overlaps or either side accepts all methods
     */
    private boolean isDuplicateMapping(HttpRequestMethod[] methods, RequestMappingResolver resolver) {
        if (methods.length == 0 || resolver.getMethodKey().size() == 0) {
            return true;
        }
        Set<HttpRequestMethod> methodKey = resolver.getMethodKey();
        for (HttpRequestMethod httpRequestMethod : methods) {
            if (methodKey.contains(httpRequestMethod)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Resolves the full mapping URLs for a handler method by combining the class-level
     * and method-level {@link RequestMapping#value()} paths.
     *
     * @param method the handler method whose URLs are to be resolved
     * @return a list of fully qualified URL patterns
     */
    private List<String> getMappingUrls(Method method) {
        List<String> urls = new ArrayList<>();

        RequestMapping methodAnno =
                AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        String[] methodUrls = getAnnotationUrls(methodAnno);

        RequestMapping clzAnno =
                AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), RequestMapping.class);
        String[] clzUrls = getAnnotationUrls(clzAnno);

        for (String methodUrl : methodUrls) {
            if (clzUrls.length > 0) {
                for (String clzUrl : clzUrls) {
                    urls.add(clzUrl + methodUrl);
                }
            } else {
                urls.add(methodUrl);
            }
        }
        return urls;
    }

    /**
     * Extracts the URL patterns from a {@link RequestMapping} annotation.
     *
     * @param methodAnno the request mapping annotation, may be {@code null}
     * @return the URL patterns, or an empty array if the annotation is {@code null}
     */
    private static String[] getAnnotationUrls(RequestMapping methodAnno) {
        if (methodAnno == null) {
            return new String[0];
        }
        return methodAnno.value();
    }

}
