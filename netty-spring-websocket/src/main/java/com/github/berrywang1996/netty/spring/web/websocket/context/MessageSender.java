package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Core API interface for sending WebSocket messages and managing WebSocket sessions.
 *
 * <p>This is the primary abstraction that application code interacts with to:
 * <ul>
 *   <li>Send targeted messages to one or more sessions via {@link #sendMessage}</li>
 *   <li>Broadcast messages to all sessions on a URI via {@link #topicMessage} / {@link #broadcast}</li>
 *   <li>Query active sessions with {@link #getSessionIds}, {@link #getSessions}, {@link #isSessionAlive}</li>
 *   <li>Close sessions programmatically with {@link #closeSession} / {@link #closeSessions}</li>
 *   <li>Inspect runtime statistics via {@link #getRuntimeStats()}</li>
 * </ul>
 *
 * <p>Inject an instance into handler beans via the
 * {@link com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.AutowiredMessageSender @AutowiredMessageSender}
 * annotation, or obtain it from the Spring context as a
 * {@link com.github.berrywang1996.netty.spring.web.websocket.support.MessageSenderSupport} bean.
 *
 * @author berrywang1996
 * @version V1.0.0
 * @since V1.0.0
 * @see DefaultMessageSender
 */
public interface MessageSender {

    /** Standard WebSocket normal closure status code (1000). */
    int NORMAL_CLOSE_STATUS_CODE = 1000;

    /** Default reason text for a normal closure. */
    String NORMAL_CLOSE_REASON = "Normal closure";

    /**
     * Returns the total number of active sessions across all registered URIs.
     *
     * @return total number of active sessions
     */
    int getSessionNums();

    /**
     * Returns the number of active sessions for the specified URI.
     *
     * @param uri the WebSocket mapping URI
     * @return number of active sessions for the URI, or 0 if the URI is not registered
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
     * Send a text message to one or more sessions without manually creating {@link TextMessage}.
     *
     * @param uri        target uri
     * @param text       text payload
     * @param sessionIds session ids
     * @throws MessageUriNotDefinedException Message uri not defined
     * @throws MessageSessionClosedException if any session closed, throw exception after execution
     */
    default void sendText(String uri, String text, String... sessionIds) throws MessageUriNotDefinedException,
            MessageSessionClosedException {
        sendMessage(uri, new TextMessage(text), sessionIds);
    }

    /**
     * Send a JSON message to one or more sessions without manually creating {@link JsonMessage}.
     *
     * @param uri        target uri
     * @param payload    JSON serializable payload
     * @param sessionIds session ids
     * @throws MessageUriNotDefinedException Message uri not defined
     * @throws MessageSessionClosedException if any session closed, throw exception after execution
     */
    default void sendJson(String uri, Object payload, String... sessionIds) throws MessageUriNotDefinedException,
            MessageSessionClosedException {
        sendMessage(uri, new JsonMessage(payload), sessionIds);
    }

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
     * Send a text message to one session.
     *
     * @param uri       target uri
     * @param text      text payload
     * @param sessionId session id
     * @throws MessageUriNotDefinedException Message uri not defined
     * @throws MessageSessionClosedException if session closed, throw exception after execution
     */
    default void sendTextToSession(String uri, String text, String sessionId)
            throws MessageUriNotDefinedException, MessageSessionClosedException {
        sendToSession(uri, new TextMessage(text), sessionId);
    }

    /**
     * Send a JSON message to one session.
     *
     * @param uri       target uri
     * @param payload   JSON serializable payload
     * @param sessionId session id
     * @throws MessageUriNotDefinedException Message uri not defined
     * @throws MessageSessionClosedException if session closed, throw exception after execution
     */
    default void sendJsonToSession(String uri, Object payload, String sessionId)
            throws MessageUriNotDefinedException, MessageSessionClosedException {
        sendToSession(uri, new JsonMessage(payload), sessionId);
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
     * Broadcast a text message to all sessions under the uri.
     *
     * @param uri  target uri
     * @param text text payload
     * @throws MessageUriNotDefinedException Message uri not defined
     */
    default void broadcastText(String uri, String text) throws MessageUriNotDefinedException {
        broadcast(uri, new TextMessage(text));
    }

    /**
     * Broadcast a JSON message to all sessions under the uri.
     *
     * @param uri     target uri
     * @param payload JSON serializable payload
     * @throws MessageUriNotDefinedException Message uri not defined
     */
    default void broadcastJson(String uri, Object payload) throws MessageUriNotDefinedException {
        broadcast(uri, new JsonMessage(payload));
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
