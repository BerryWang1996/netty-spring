package com.github.berrywang1996.netty.spring.web.mvc.bind.annotation;

import com.github.berrywang1996.netty.spring.web.mvc.consts.HttpRequestMethod;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * Annotation for mapping HTTP PATCH requests onto specific handler methods.
 * <p>
 * This is a composed annotation that acts as a shortcut for
 * {@code @RequestMapping(method = HttpRequestMethod.PATCH)}. It can only be
 * applied at the method level.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@RequestMapping(method = HttpRequestMethod.PATCH)
public @interface PatchMapping {

    /**
     * The URL path patterns to map this method to.
     * Alias for {@link RequestMapping#value}.
     *
     * @return an array of URL patterns; defaults to an empty array (matches the class-level mapping)
     */
    @AliasFor(annotation = RequestMapping.class)
    String[] value() default {};

    /**
     * The server ports on which this mapping should be active.
     * Alias for {@link RequestMapping#port}.
     *
     * @return an array of port numbers; defaults to an empty array (active on all ports)
     */
    @AliasFor(annotation = RequestMapping.class)
    int[] port() default {};

    @AliasFor(annotation = RequestMapping.class)
    String[] consumes() default {};

    @AliasFor(annotation = RequestMapping.class)
    String[] produces() default {};

}
