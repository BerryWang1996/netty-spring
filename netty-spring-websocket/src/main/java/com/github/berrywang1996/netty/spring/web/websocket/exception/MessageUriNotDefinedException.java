package com.github.berrywang1996.netty.spring.web.websocket.exception;

import java.util.Collection;

/**
 * Thrown when a {@link com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender
 * MessageSender} operation references a WebSocket URI that has no registered
 * {@code @MessageMapping} handler.
 *
 * <p>The error message includes the unregistered URI, the currently registered URIs,
 * and actionable remediation steps so that developers can diagnose misconfiguration
 * quickly.
 *
 * @author berrywang1996
 * @version V1.0.0
 * @since V1.0.0
 */
public class MessageUriNotDefinedException extends RuntimeException {

    /**
     * Creates the exception with only the unregistered URI.
     *
     * @param uri the WebSocket URI that was not found
     */
    public MessageUriNotDefinedException(String uri) {
        this(uri, null);
    }

    /**
     * Creates the exception with the unregistered URI and the set of registered URIs
     * for diagnostic purposes.
     *
     * @param uri            the WebSocket URI that was not found
     * @param registeredUris the currently registered URIs (may be {@code null} or empty)
     */
    public MessageUriNotDefinedException(String uri, Collection<String> registeredUris) {
        super(buildMessage(uri, registeredUris));
    }

    /**
     * Builds a descriptive error message with the target URI, registered URIs,
     * and actionable remediation guidance.
     */
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
