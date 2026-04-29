package com.github.berrywang1996.netty.spring.web.websocket.exception;

import java.util.Collection;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class MessageUriNotDefinedException extends RuntimeException {

    public MessageUriNotDefinedException(String uri) {
        this(uri, null);
    }

    public MessageUriNotDefinedException(String uri, Collection<String> registeredUris) {
        super(buildMessage(uri, registeredUris));
    }

    private static String buildMessage(String uri, Collection<String> registeredUris) {
        StringBuilder message = new StringBuilder();
        message.append("WebSocket message uri \"").append(uri).append("\" is not registered. ");
        if (registeredUris == null || registeredUris.isEmpty()) {
            message.append("No websocket mappings are currently registered. ");
        } else {
            message.append("Registered websocket uri(s): ").append(registeredUris).append(". ");
        }
        message.append("Action: verify the @MessageMapping value, check the target uri string, ");
        message.append("and ensure server.netty.websocket.enable is true.");
        return message.toString();
    }

}
