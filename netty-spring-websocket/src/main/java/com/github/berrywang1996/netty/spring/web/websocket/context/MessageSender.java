package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageSessionClosedException;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;

import java.util.Set;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public interface MessageSender {

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
     * Send message. If any session closed, throw exception after execution.
     *
     * @param uri     target uri
     * @param message message content
     * @throws MessageUriNotDefinedException Message uri not defined
     */
    void topicMessage(String uri, AbstractMessage message) throws MessageUriNotDefinedException;

}