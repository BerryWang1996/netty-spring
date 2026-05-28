package com.github.berrywang1996.netty.spring.web.databind;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation used to specify a date format pattern for field-level data binding.
 *
 * <p>When applied to a {@link java.util.Date} field in a request model, the framework
 * uses the specified pattern to parse incoming date strings during parameter binding.
 *
 * <p>Example usage:
 * <pre>
 *   &#64;DateFormat(pattern = "yyyy-MM-dd")
 *   private Date birthday;
 * </pre>
 *
 * @author berrywang1996
 * @version V1.0.0
 * @since V1.0.0
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface DateFormat {

    /**
     * The date format pattern used for parsing, following {@link java.text.SimpleDateFormat} conventions.
     *
     * @return the date format pattern string (e.g. "yyyy-MM-dd HH:mm:ss")
     */
    String pattern();

}
