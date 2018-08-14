package com.github.berrywang1996.netty.spring.web.mvc.bind.annotation;

import org.springframework.core.annotation.AliasFor;
import org.springframework.stereotype.Controller;

import java.lang.annotation.*;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Controller
public @interface RestController {

    @AliasFor(annotation = Controller.class)
    String value() default "";

}
