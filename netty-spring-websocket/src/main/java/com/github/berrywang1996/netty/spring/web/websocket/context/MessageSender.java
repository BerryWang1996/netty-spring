package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public interface MessageSender {

    int NORMAL_CLOSE_STATUS_CODE = 1000;

    String NORMAL_CLOSE_REASON = "Normal closure";

    /**
     * Get all uri number of sessions
     *
     * @return all number of sessions
     */
    int getSessionNums();

    /**
     * Get current uri number of sessions
     *
     * @return current uri number of sessions
     */
    int getSessionNums(String uri);

    /**
     * Get current uri session ids.
     *
     * @param uri target uri
     * @return readonly snapshot of session ids
     */
    default Set<String> getSessionIds(String uri) {
        return Collections.emptySet();
    }

    /**
     * Get current uri session.
     *
     * @param uri       target uri
     * @param sessionId session id
     * @return session, or null if not exists
     */
    default MessageSession getSession(String uri, String sessionId) {
        return null;
    }

    /**
     * Get current uri sessions.
     *
     * @param uri target uri
     * @return readonly snapshot of sessions
     */
    default Map<String, MessageSession> getSessions(String uri) {
        return Collections.emptyMap();
    }

    /**
     * Get registered uri
     *
     * @return registered uri
     */
    Set<String> getRegisteredUri();

    /**
     * If any session closed, return false.
     *
     * @param uri        target uri
     * @param sessionIds session ids
     * @return if any session closed, return false
     */
    boolean isSessionAlive(String uri, String... sessionIds);

    /**
     * Send message. If any session closed, throw exception after execution.
     *
     * @param uri        target uri
     * @param message    message content
     * @param sessionIds session ids
     * @throws MessageUriNotDefinedException Message uri not defined
     * @throws MessageSessionClosedException if any session closed, throw exception after execution
     */
    void sendMessage(String uri, AbstractMessage message, String... sessionIds) throws MessageUriNotDefinedException,
            MessageSessionClosedException;

    /**
     * Send message to one session.
     *
     * @param uri       target uri
     * @param message   message content
     * @param sessionId session id
     * @throws MessageUriNotDefinedException Message uri not defined
     * @throws MessageSessionClosedException if session closed, throw exception after execution
     */
    default void sendToSession(String uri, AbstractMessage message, String sessionId)
            throws MessageUriNotDefinedException, MessageSessionClosedException {
        sendMessage(uri, message, sessionId);
    }

    /**
     * Send message. If any session closed, throw exception after execution.
     *
     * @param uri     target uri
     * @param message message content
     * @throws MessageUriNotDefinedException Message uri not defined
     */
    void topicMessage(String uri, AbstractMessage message) throws MessageUriNotDefinedException;

    /**
     * Broadcast message to all sessions under the uri.
     *
     * @param uri     target uri
     * @param message message content
     * @throws MessageUriNotDefinedException Message uri not defined
     */
    default void broadcast(String uri, AbstractMessage message) throws MessageUriNotDefinedException {
        topicMessage(uri, message);
    }

    /**
     * Close one session under the uri.
     *
     * @param uri       target uri
     * @param sessionId session id
     * @return true if the session close lifecycle was started
     */
    default boolean closeSession(String uri, String sessionId) throws MessageUriNotDefinedException {
        return closeSession(uri, sessionId, NORMAL_CLOSE_STATUS_CODE, NORMAL_CLOSE_REASON);
    }

    /**
     * Close one session under the uri with custom close status.
     *
     * @param uri        target uri
     * @param sessionId  session id
     * @param statusCode websocket close status code
     * @param reasonText websocket close reason text
     * @return true if the session close lifecycle was started
     */
    default boolean closeSession(String uri, String sessionId, int statusCode, String reasonText)
            throws MessageUriNotDefinedException {
        return false;
    }

    /**
     * Close all sessions under the uri.
     *
     * @param uri target uri
     * @return number of sessions whose close lifecycle was started
     */
    default int closeSessions(String uri) throws MessageUriNotDefinedException {
        return closeSessions(uri, NORMAL_CLOSE_STATUS_CODE, NORMAL_CLOSE_REASON);
    }

    /**
     * Close all sessions under the uri with custom close status.
     *
     * @param uri        target uri
     * @param statusCode websocket close status code
     * @param reasonText websocket close reason text
     * @return number of sessions whose close lifecycle was started
     */
    default int closeSessions(String uri, int statusCode, String reasonText) throws MessageUriNotDefinedException {
        int closed = 0;
        for (String sessionId : getSessionIds(uri)) {
            if (closeSession(uri, sessionId, statusCode, reasonText)) {
                closed++;
            }
        }
        return closed;
    }

    /**
     * Runtime sender statistics.
     */
    default MessageSenderRuntimeStats getRuntimeStats() {
        return MessageSenderRuntimeStats.empty();
    }

    /**
     * Shutdown sender resources when no longer needed.
     */
    default void shutdown() {
    }

}
