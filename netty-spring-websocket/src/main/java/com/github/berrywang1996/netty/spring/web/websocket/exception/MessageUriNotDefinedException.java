package com.github.berrywang1996.netty.spring.web.websocket.exception;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class MessageUriNotDefinedException extends RuntimeException {

    public MessageUriNotDefinedException(String uri) {
        super("The message uri" + "\"" + uri + "\"" + "not defined");
    }

}
