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

package com.github.berrywang1996.netty.spring.web.websocket.bind.annotation;

import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;

import java.lang.annotation.*;

/**
 * Annotation that maps WebSocket message handler methods or handler classes to
 * specific URI paths and message types.
 *
 * <p>When placed on a <b>method</b>, it registers that method as a handler for the
 * specified {@link #messageType()} on the given URI(s). When placed on a <b>class</b>,
 * it provides a base URI prefix that is prepended to all method-level mappings
 * within that class.
 *
 * <p>Example usage:
 * <pre>{@code
 * @Component
 * @MessageMapping("/chat")
 * public class ChatHandler {
 *
 *     @MessageMapping(messageType = MessageType.ON_CONNECTED)
 *     public void onConnected(MessageSession session) { ... }
 *
 *     @MessageMapping(messageType = MessageType.TEXT_MESSAGE)
 *     public void onMessage(MessageSession session, String text) { ... }
 * }
 * }</pre>
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface MessageMapping {

    /**
     * The WebSocket URI path(s) this handler is mapped to. When used on a class,
     * acts as a prefix for all method-level mappings within that class.
     *
     * @return the URI path(s); defaults to an empty string
     */
    String[] value() default "";

    /**
     * The type of WebSocket message or lifecycle event this method handles.
     *
     * @return the message type; defaults to {@link MessageType#TEXT_MESSAGE}
     */
    MessageType messageType() default MessageType.TEXT_MESSAGE;

    /**
     * Optional server port filter. When specified, the mapping is only active on
     * the listed port(s). When empty (the default), the mapping applies to all ports.
     *
     * @return the port numbers this mapping applies to; empty means all ports
     */
    int[] port() default {};

}
