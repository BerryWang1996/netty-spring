package com.github.berrywang1996.netty.spring.web.websocket.exception;

import java.util.List;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class MessageSessionClosedException extends RuntimeException {

    private List<String> sessionIds;

    public List<String> getSessionIds() {
        return sessionIds;
    }

    public void setSessionIds(List<String> sessionIds) {
        this.sessionIds = sessionIds;
    }

    public MessageSessionClosedException(List<String> sessionIds) {
        super("The session is closed while send message. Session id(s): " + sessionIds.toString());
        this.sessionIds = sessionIds;
    }
}
