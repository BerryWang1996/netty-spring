package com.github.berrywang1996.netty.spring.demo.controller;

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.ResponseBody;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import io.netty.handler.codec.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;

/**
 * Demo controller showcasing WebSocket text and JSON messaging, as well as
 * HTTP endpoints that trigger WebSocket broadcasts.
 * <p>
 * This controller registers two WebSocket endpoints:
 * <ul>
 *   <li>{@code /ws/test} - echoes text and binary messages back to the sender</li>
 *   <li>{@code /ws/json} - echoes JSON messages back to the sender</li>
 * </ul>
 * It also exposes HTTP endpoints under {@code /ws/} that allow external callers
 * to broadcast messages, send JSON, and close sessions via simple GET requests.
 *
 * @author berrywang1996
 * @version V1.0.0
 */
@Slf4j
@Controller
@RequestMapping("/ws")
public class WebSocketController {

    /** Injected message sender for WebSocket broadcast and targeted delivery. */
    private final MessageSender messageSender;

    /** WebSocket URI for the text/binary echo endpoint. */
    private static final String TEST_URI = "/ws/test";

    /** WebSocket URI for the JSON echo endpoint. */
    private static final String JSON_URI = "/ws/json";

    /**
     * Constructs the WebSocket controller with the required message sender.
     *
     * @param messageSender the WebSocket message sender
     */
    public WebSocketController(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    /**
     * HTTP endpoint that broadcasts a text message to all sessions connected to {@code /ws/test}.
     *
     * @param message the text message to broadcast
     * @return {@code "success"} on completion
     */
    @RequestMapping("/sendWebsocketMessage")
    @ResponseBody
    public String sendWebsocketMessage(String message) {
        try {
            messageSender.broadcastText(TEST_URI, message);
        } catch (Exception e) {
            log.warn("Broadcast websocket text message failed.", e);
        }
        return "success";
    }

    /**
     * HTTP endpoint that broadcasts a JSON payload (including active session count)
     * to all sessions connected to {@code /ws/test}.
     *
     * @param message the message text to include in the JSON payload
     * @return {@code "success"} on completion
     */
    @RequestMapping("/sendWebsocketJson")
    @ResponseBody
    public String sendWebsocketJson(String message) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("message", message);
            payload.put("activeSessions", messageSender.getSessionNums(TEST_URI));
            messageSender.broadcastJson(TEST_URI, payload);
        } catch (Exception e) {
            log.warn("Broadcast websocket json message failed.", e);
        }
        return "success";
    }

    /**
     * HTTP endpoint that forcibly closes a WebSocket session by its session ID.
     *
     * @param sessionId the ID of the WebSocket session to close
     * @return {@code "closed"} if the session was found and closed, {@code "session not found"} otherwise
     */
    @RequestMapping("/closeWebsocketSession")
    @ResponseBody
    public String closeWebsocketSession(String sessionId) {
        return messageSender.closeSession(TEST_URI, sessionId, 1000, "Closed by demo API")
                ? "closed"
                : "session not found";
    }

    /**
     * Handles the WebSocket handshake event on the {@code /ws/test} endpoint.
     *
     * @param request the original HTTP upgrade request
     */
    @MessageMapping(value = TEST_URI, messageType = MessageType.ON_HANDSHAKE)
    public void testHandShake(HttpRequest request) {
        log.info("testHandShake ok");
    }

    /**
     * Handles a successful WebSocket connection on the {@code /ws/test} endpoint.
     * Logs the session ID, path, and optional {@code room} query parameter.
     *
     * @param messageSession the newly established WebSocket session
     */
    @MessageMapping(value = TEST_URI, messageType = MessageType.ON_CONNECTED)
    public void testConnected(MessageSession messageSession) {
        log.info("testConnected ok, sessionId={}, path={}, room={}",
                messageSession.getSessionId(),
                messageSession.getPath(),
                messageSession.getQueryParam("room"));
    }

    /**
     * Handles an inbound text message on the {@code /ws/test} endpoint.
     * Echoes the received text back to the sender.
     *
     * @param text    the text message received from the client
     * @param session the WebSocket session that sent the message
     */
    @MessageMapping(value = TEST_URI, messageType = MessageType.TEXT_MESSAGE)
    public void testTextMessage(String text, MessageSession session) {
        log.info("testTextMessage ok, sessionId={}, received message: {}", session.getSessionId(), text);
        messageSender.sendTextToSession(TEST_URI, text, session.getSessionId());
    }

    /**
     * Handles an inbound JSON message on the {@code /ws/json} endpoint.
     * Echoes the deserialized message (with session ID and room) back as JSON.
     *
     * @param message the deserialized JSON message DTO
     * @param session the WebSocket session that sent the message
     */
    @MessageMapping(value = JSON_URI, messageType = MessageType.TEXT_MESSAGE)
    public void testJsonMessage(DemoWebSocketMessage message, MessageSession session) {
        log.info("testJsonMessage ok, sessionId={}, room={}, message={}",
                session.getSessionId(),
                message.getRoom(),
                message.getMessage());
        Map<String, Object> payload = new HashMap<>();
        payload.put("sessionId", session.getSessionId());
        payload.put("room", message.getRoom());
        payload.put("message", message.getMessage());
        messageSender.sendJsonToSession(JSON_URI, payload, session.getSessionId());
    }

    /**
     * Handles an inbound binary message on the {@code /ws/test} endpoint.
     * Logs the session ID and the number of bytes received.
     *
     * @param binary  the raw binary data received from the client
     * @param session the WebSocket session that sent the message
     */
    @MessageMapping(value = TEST_URI, messageType = MessageType.BINARY_MESSAGE)
    public void testBinaryMessage(byte[] binary, MessageSession session) {
        log.info("testBinaryMessage ok, sessionId={}, bytes={}", session.getSessionId(), binary.length);
    }

    /**
     * Handles a WebSocket ping frame on the {@code /ws/test} endpoint.
     */
    @MessageMapping(value = TEST_URI, messageType = MessageType.ON_PING)
    public void testPing() {
        log.info("get ping");
    }

    /**
     * Handles WebSocket errors on the {@code /ws/test} endpoint by logging the exception.
     *
     * @param e the exception that occurred during WebSocket processing
     */
    @MessageMapping(value = TEST_URI, messageType = MessageType.ON_ERROR)
    public void testError(Exception e) {
        log.info("get exception", e);
    }

//    @RequestMapping("/info")
//    @ResponseBody
//    public Object getInfo() {
//
//        Set<String> registeredUris = messageSender.getRegisteredUri();
//        Map<String, Integer> sessionNums = new HashMap<>();
//        for (String registeredUri : registeredUris) {
//            sessionNums.put(registeredUri, messageSender.getSessionNums(registeredUri));
//        }
//
//        Map<String, Object> dataMap = new HashMap<>(1);
//        dataMap.put("session map", sessionNums);
//
//        return dataMap;
//    }

    /**
     * DTO for deserializing inbound JSON WebSocket messages on the {@code /ws/json} endpoint.
     */
    public static final class DemoWebSocketMessage {
        /** The chat room identifier. */
        private String room;
        /** The message text payload. */
        private String message;

        /** @return the room identifier */
        public String getRoom() {
            return room;
        }

        /** @param room the room identifier to set */
        public void setRoom(String room) {
            this.room = room;
        }

        /** @return the message text */
        public String getMessage() {
            return message;
        }

        /** @param message the message text to set */
        public void setMessage(String message) {
            this.message = message;
        }
    }

}
