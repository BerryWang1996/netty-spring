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

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.ExceptionHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Method;
import java.util.*;

/**
 * Resolves {@link ExceptionHandler @ExceptionHandler}-annotated methods for a given controller or
 * {@link com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.ControllerAdvice @ControllerAdvice} class.
 *
 * <p>This class introspects the target class for methods annotated with {@code @ExceptionHandler},
 * builds a mapping from exception types to handler methods, and provides a lookup mechanism
 * that finds the best-matching handler for a given exception type.
 *
 * <p>The resolution order follows specificity: a handler for {@code IllegalArgumentException}
 * is preferred over a handler for {@code RuntimeException} when the thrown exception is
 * an {@code IllegalArgumentException}.
 *
 * <p>Supports two construction modes:
 * <ul>
 *   <li><b>Eager</b>: accepts a pre-resolved bean instance directly</li>
 *   <li><b>Lazy</b>: accepts a bean name and {@link ApplicationContext}, deferring bean
 *       resolution until {@link #getHandlerBean()} is first called. This avoids circular
 *       dependency issues when scanning occurs during application context initialization.</li>
 * </ul>
 *
 * @author berrywang1996
 * @since V1.5.0
 */
@Slf4j
public class ExceptionHandlerMethodResolver {

    /** Map from exception type to the handler method that handles it. */
    private final Map<Class<? extends Throwable>, Method> exceptionHandlerMap = new LinkedHashMap<>();

    /** The bean instance on which the exception handler methods should be invoked (resolved lazily if null). */
    private volatile Object handlerBean;

    /** Spring bean name for lazy resolution of the handler bean. */
    private final String beanName;

    /** Spring application context for lazy bean lookup. */
    private final ApplicationContext applicationContext;

    /**
     * Creates a new resolver that scans the given class for {@code @ExceptionHandler} methods.
     * The handler bean is provided directly (eager resolution).
     *
     * @param handlerClass the class to scan (controller or @ControllerAdvice)
     * @param handlerBean  the bean instance for method invocation
     */
    public ExceptionHandlerMethodResolver(Class<?> handlerClass, Object handlerBean) {
        this.handlerBean = handlerBean;
        this.beanName = null;
        this.applicationContext = null;
        scanExceptionHandlers(handlerClass);
    }

    /**
     * Creates a new resolver that scans the given class for {@code @ExceptionHandler} methods.
     * The handler bean is resolved lazily from the Spring application context to avoid
     * circular dependency issues during bootstrap.
     *
     * @param handlerClass       the class to scan (controller or @ControllerAdvice)
     * @param beanName           the Spring bean name for lazy resolution
     * @param applicationContext the Spring application context for bean lookup
     */
    public ExceptionHandlerMethodResolver(Class<?> handlerClass, String beanName,
                                          ApplicationContext applicationContext) {
        this.handlerBean = null;
        this.beanName = beanName;
        this.applicationContext = applicationContext;
        scanExceptionHandlers(handlerClass);
    }

    /**
     * Scans the given class for methods annotated with {@code @ExceptionHandler} and
     * populates the exception-type-to-method mapping.
     *
     * @param handlerClass the class to scan
     */
    private void scanExceptionHandlers(Class<?> handlerClass) {
        for (Method method : handlerClass.getDeclaredMethods()) {
            ExceptionHandler annotation = method.getAnnotation(ExceptionHandler.class);
            if (annotation != null) {
                Class<? extends Throwable>[] exceptionTypes = annotation.value();
                if (exceptionTypes.length == 0) {
                    // Infer exception type from method parameter
                    exceptionTypes = inferExceptionTypes(method);
                }
                for (Class<? extends Throwable> exceptionType : exceptionTypes) {
                    Method existing = exceptionHandlerMap.put(exceptionType, method);
                    if (existing != null && !existing.equals(method)) {
                        log.warn("Ambiguous @ExceptionHandler for {}: {} vs {}", exceptionType, existing, method);
                    }
                }
            }
        }
    }

    /**
     * Infers exception types from the method's parameter list.
     * If the method has a parameter that extends {@code Throwable}, that type is used.
     * Otherwise, defaults to {@code Exception.class}.
     */
    @SuppressWarnings("unchecked")
    private Class<? extends Throwable>[] inferExceptionTypes(Method method) {
        for (Class<?> paramType : method.getParameterTypes()) {
            if (Throwable.class.isAssignableFrom(paramType)) {
                return new Class[]{(Class<? extends Throwable>) paramType};
            }
        }
        return new Class[]{Exception.class};
    }

    /**
     * Finds the best-matching exception handler method for the given exception.
     *
     * <p>The resolution prefers handlers for the most specific (closest) exception type
     * in the class hierarchy. For example, if both {@code IllegalArgumentException}
     * and {@code RuntimeException} have handlers, and the thrown exception is an
     * {@code IllegalArgumentException}, the former handler is selected.
     *
     * @param exception the thrown exception
     * @return the matching handler method, or {@code null} if no handler matches
     */
    public Method resolveMethod(Throwable exception) {
        Class<? extends Throwable> exceptionClass = exception.getClass();

        // Walk up the exception class hierarchy to find the best match
        Method handler = null;
        int depth = Integer.MAX_VALUE;

        for (Map.Entry<Class<? extends Throwable>, Method> entry : exceptionHandlerMap.entrySet()) {
            if (entry.getKey().isAssignableFrom(exceptionClass)) {
                int currentDepth = getInheritanceDepth(exceptionClass, entry.getKey());
                if (currentDepth < depth) {
                    depth = currentDepth;
                    handler = entry.getValue();
                }
            }
        }

        return handler;
    }

    /**
     * Returns the bean instance on which handler methods should be invoked.
     * If the resolver was constructed with a bean name and application context,
     * the bean is resolved lazily on first access using double-checked locking
     * for thread safety.
     *
     * @return the handler bean instance
     */
    public Object getHandlerBean() {
        Object bean = handlerBean;
        if (bean == null && beanName != null && applicationContext != null) {
            synchronized (this) {
                bean = handlerBean;
                if (bean == null) {
                    bean = applicationContext.getBean(beanName);
                    handlerBean = bean;
                }
            }
        }
        return bean;
    }

    /**
     * Returns whether this resolver has any registered exception handlers.
     *
     * @return {@code true} if at least one handler is registered
     */
    public boolean hasHandlers() {
        return !exceptionHandlerMap.isEmpty();
    }

    /**
     * Calculates the inheritance depth between a derived exception class and a base exception class.
     *
     * @param exceptionClass the actual exception class
     * @param targetClass    the handler's target exception class
     * @return the number of inheritance levels between them (0 = exact match)
     */
    private int getInheritanceDepth(Class<?> exceptionClass, Class<?> targetClass) {
        if (exceptionClass.equals(targetClass)) {
            return 0;
        }
        if (exceptionClass.equals(Object.class)) {
            return Integer.MAX_VALUE;
        }
        return 1 + getInheritanceDepth(exceptionClass.getSuperclass(), targetClass);
    }

}
