package com.github.berrywang1996.netty.spring.web.websocket.bind.annotation;

import java.lang.annotation.*;

/**
 * Field-level injection annotation that marks a field for automatic injection of a
 * {@link com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender MessageSender}
 * instance.
 *
 * <p>When placed on a field inside a {@code @MessageMapping}-annotated handler bean,
 * the framework populates the field with the active {@code MessageSender} so that the
 * handler can send messages, broadcast to sessions, or close sessions programmatically.
 *
 * <pre>{@code
 * @Component
 * @MessageMapping("/chat")
 * public class ChatHandler {
 *
 *     @AutowiredMessageSender
 *     private MessageSender sender;
 * }
 * }</pre>
 *
 * @author berrywang1996
 * @version V1.0.0
 * @since V1.0.0
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AutowiredMessageSender {
}
