package com.github.berrywang1996.netty.spring.web.websocket.bind.annotation;

import java.lang.annotation.*;

/**
 * <b>Deprecated — never wired.</b> This field-level injection annotation is NOT processed by the
 * framework: there is no {@code BeanPostProcessor} or reflection injection for it, so a field
 * annotated with it is left {@code null} (and any code that relies on it will NPE). Inject
 * {@link com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender MessageSender}
 * via constructor injection instead (see {@code docs/api-guide.md} §5).
 *
 * @deprecated Use constructor injection of {@code MessageSender}; this annotation has never been
 * wired and is a no-op.
 * @author berrywang1996
 * @version V1.0.0
 * @since V1.0.0
 */
@Deprecated
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AutowiredMessageSender {
}
