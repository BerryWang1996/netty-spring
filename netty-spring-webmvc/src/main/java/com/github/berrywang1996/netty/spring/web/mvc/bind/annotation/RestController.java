package com.github.berrywang1996.netty.spring.web.mvc.bind.annotation;

import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Controller;

import java.lang.annotation.*;

/**
 * A convenience annotation that combines {@link Controller @Controller} with implicit
 * {@link ResponseBody @ResponseBody} semantics.
 * <p>
 * Controllers annotated with {@code @RestController} have every handler method
 * treated as if it were annotated with {@code @ResponseBody}, meaning the return
 * value is serialized as JSON directly into the HTTP response body.
 *
 * @author berrywang1996
 * @version V1.0.0
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Controller
public @interface RestController {

    /**
     * The logical name of the controller bean, used as an alias for
     * {@link Controller#value()}.
     *
     * @return the bean name, or an empty string to let Spring generate one
     */
    @AliasFor(annotation = Controller.class)
    String value() default "";

}
