package com.github.berrywang1996.netty.spring.demo.controller;

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.ResponseBody;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.JsonMessage;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import com.github.berrywang1996.netty.spring.web.websocket.context.TextMessage;
import io.netty.handler.codec.http.HttpRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
@Slf4j
@Controller
@RequestMapping("/ws")
public class WebSocketController {

    private final MessageSender messageSender;

    private static final String TEST_URI = "/ws/test";

    private static final String JSON_URI = "/ws/json";

    public WebSocketController(MessageSender messageSender) {
        this.messageSender = messageSender;
    }

    @RequestMapping("/sendWebsocketMessage")
    @ResponseBody
    public String sendWebsocketMessage(String message) {
        try {
            messageSender.broadcast(TEST_URI, new TextMessage(message));
        } catch (Exception e) {
            log.warn("Broadcast websocket text message failed.", e);
        }
        return "success";
    }

    @RequestMapping("/sendWebsocketJson")
    @ResponseBody
    public String sendWebsocketJson(String message) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("message", message);
            payload.put("activeSessions", messageSender.getSessionNums(TEST_URI));
            messageSender.broadcast(TEST_URI, new JsonMessage(payload));
        } catch (Exception e) {
            log.warn("Broadcast websocket json message failed.", e);
        }
        return "success";
    }

    @RequestMapping("/closeWebsocketSession")
    @ResponseBody
    public String closeWebsocketSession(String sessionId) {
        return messageSender.closeSession(TEST_URI, sessionId, 1000, "Closed by demo API")
                ? "closed"
                : "session not found";
    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.ON_HANDSHAKE)
    public void testHandShake(HttpRequest request) {
        log.info("testHandShake ok");
    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.ON_CONNECTED)
    public void testConnected(MessageSession messageSession) {
        log.info("testConnected ok, sessionId={}, path={}, room={}",
                messageSession.getSessionId(),
                messageSession.getPath(),
                messageSession.getQueryParam("room"));
    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.TEXT_MESSAGE)
    public void testTextMessage(String text, MessageSession session) {
        log.info("testTextMessage ok, sessionId={}, received message: {}", session.getSessionId(), text);
        messageSender.sendToSession(TEST_URI, new TextMessage(text), session.getSessionId());
    }

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
        messageSender.sendToSession(JSON_URI, new JsonMessage(payload), session.getSessionId());
    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.BINARY_MESSAGE)
    public void testBinaryMessage(byte[] binary, MessageSession session) {
        log.info("testBinaryMessage ok, sessionId={}, bytes={}", session.getSessionId(), binary.length);
    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.ON_PING)
    public void testPing() {
        log.info("get ping");
    }

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

    public static final class DemoWebSocketMessage {
        private String room;
        private String message;

        public String getRoom() {
            return room;
        }

        public void setRoom(String room) {
            this.room = room;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }
    }

}
