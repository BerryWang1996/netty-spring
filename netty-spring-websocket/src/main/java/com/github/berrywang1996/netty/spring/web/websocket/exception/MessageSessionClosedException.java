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
        this(null, sessionIds);
    }

    public MessageSessionClosedException(String uri, List<String> sessionIds) {
        super(buildMessage(uri, sessionIds));
        this.sessionIds = sessionIds;
    }

    private static String buildMessage(String uri, List<String> sessionIds) {
        StringBuilder message = new StringBuilder();
        message.append("Cannot send websocket message because one or more target sessions are closed or missing. ");
        if (uri != null) {
            message.append("uri=").append(uri).append(", ");
        }
        message.append("sessionId(s)=").append(sessionIds).append(". ");
        message.append("Action: refresh session ids with MessageSender#getSessionIds(uri), ");
        message.append("or call isSessionAlive(uri, sessionIds...) before targeted sends.");
        return message.toString();
    }
}
