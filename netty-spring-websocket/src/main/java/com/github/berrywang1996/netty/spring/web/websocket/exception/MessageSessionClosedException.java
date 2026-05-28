package com.github.berrywang1996.netty.spring.web.websocket.exception;

import java.util.List;

/**
 * Thrown when a message send operation targets one or more WebSocket sessions that are
 * already closed or no longer present in the session map.
 *
 * <p>This exception is raised by
 * {@link com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender#sendMessage
 * MessageSender.sendMessage()} after it has attempted to deliver the message to all
 * requested sessions. The exception carries the list of session IDs that were found to
 * be closed so that callers can handle stale references gracefully.
 *
 * @author berrywang1996
 * @version V1.0.0
 * @since V1.0.0
 */
public class MessageSessionClosedException extends RuntimeException {

    private List<String> sessionIds;

    /**
     * Returns the session IDs that were closed or missing at send time.
     *
     * @return list of closed/missing session IDs
     */
    public List<String> getSessionIds() {
        return sessionIds;
    }

    /**
     * Replaces the list of closed session IDs.
     *
     * @param sessionIds the new list of session IDs
     */
    public void setSessionIds(List<String> sessionIds) {
        this.sessionIds = sessionIds;
    }

    /**
     * Creates the exception without a URI context.
     *
     * @param sessionIds the closed/missing session IDs
     */
    public MessageSessionClosedException(List<String> sessionIds) {
        this(null, sessionIds);
    }

    /**
     * Creates the exception with both URI and session ID context.
     *
     * @param uri        the target WebSocket URI (may be {@code null})
     * @param sessionIds the closed/missing session IDs
     */
    public MessageSessionClosedException(String uri, List<String> sessionIds) {
        super(buildMessage(uri, sessionIds));
        this.sessionIds = sessionIds;
    }

    /**
     * Builds a descriptive error message that includes the URI, affected session IDs,
     * and actionable remediation guidance.
     */
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
