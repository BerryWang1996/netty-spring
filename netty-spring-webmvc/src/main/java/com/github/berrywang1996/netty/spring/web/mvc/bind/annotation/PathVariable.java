package com.github.berrywang1996.netty.spring.web.mvc.bind.annotation;

import java.lang.annotation.*;

/**
 * Annotation indicating that a method parameter should be bound to a URI template variable.
 * <p>
 * When a handler method's URL pattern contains path placeholders (e.g. {@code /users/{id}}),
 * annotating a method parameter with {@code @PathVariable} binds the corresponding segment
 * of the actual request URI to that parameter.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Documented
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface PathVariable {

    /**
     * The name of the URI template variable to bind to this parameter.
     * If left empty, the parameter name is used by default.
     *
     * @return the path variable name, or an empty string to use the parameter name
     */
    String value() default "";

}
