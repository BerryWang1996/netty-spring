package com.github.berrywang1996.netty.spring.web.mvc.bind.annotation;

import java.lang.annotation.*;

/**
 * Annotation indicating that a method parameter should be bound to a query-string
 * or form-encoded request parameter.
 * <p>
 * When a handler method parameter is annotated with {@code @RequestParam}, the framework
 * extracts the named query parameter from the request URI (or POST form body) and converts
 * it to the target type before invoking the method.
 *
 * <p>Usage example:
 * <pre>{@code
 * @GetMapping("/search")
 * public List<Item> search(@RequestParam("q") String query,
 *                          @RequestParam(value = "page", defaultValue = "1") Integer page,
 *                          @RequestParam(value = "size", required = false) Integer size) {
 *     // ...
 * }
 * }</pre>
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequestParam {

    /**
     * The name of the query-string parameter to bind to this method parameter.
     * If left empty, the method parameter name is used by default.
     *
     * @return the request parameter name, or an empty string to use the method parameter name
     */
    String value() default "";

    /**
     * Whether the parameter is required.
     * <p>When {@code true} (default), a missing parameter results in a 400 Bad Request error.
     * When {@code false}, a missing parameter results in a {@code null} value
     * (or the {@link #defaultValue()} if specified).
     *
     * @return {@code true} if the parameter is required; defaults to {@code true}
     */
    boolean required() default true;

    /**
     * The default value to use when the request parameter is not present.
     * <p>Setting a default value implicitly sets {@link #required()} to {@code false}.
     *
     * @return the default value as a string; defaults to a sentinel meaning "no default"
     */
    String defaultValue() default "\n\t\t\n\t\t\n\n\t\t\t\t\n";

}
