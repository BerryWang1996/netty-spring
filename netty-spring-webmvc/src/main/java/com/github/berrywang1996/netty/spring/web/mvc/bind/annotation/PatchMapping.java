package com.github.berrywang1996.netty.spring.web.mvc.bind.annotation;

import com.github.berrywang1996.netty.spring.web.mvc.consts.HttpRequestMethod;
import org.springframework.core.annotation.AliasFor;

import java.lang.annotation.*;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@RequestMapping(method = HttpRequestMethod.PATCH)
public @interface PatchMapping {

    /**
     * Alias for {@link RequestMapping#value}.
     */
    @AliasFor(annotation = RequestMapping.class)
    String[] value() default {};

    /**
     * Alias for {@link RequestMapping#port}.
     */
    @AliasFor(annotation = RequestMapping.class)
    int[] port() default {};

}
