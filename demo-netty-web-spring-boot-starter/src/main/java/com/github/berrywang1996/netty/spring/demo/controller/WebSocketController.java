package com.github.berrywang1996.netty.spring.demo.controller;

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.ResponseBody;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.AutowiredMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import com.github.berrywang1996.netty.spring.web.websocket.context.TextMessage;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
@Slf4j
@Controller
@RequestMapping("/ws")
public class WebSocketController {

    @AutowiredMessageSender
    private MessageSender messageSender;

    private static final String TEST_URI = "/ws/test";

    @RequestMapping("/sendWebsocketMessage")
    @ResponseBody
    public String sendWebsocketMessage(String message) {
        try {
            messageSender.topicMessage(TEST_URI, new TextMessage(message));
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "success";
    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.ON_HANDSHAKE)
    public void testHandShake(HttpRequest request) {
        log.info("testHandShake ok");
    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.ON_CONNECTED)
    public void testConnected(HttpRequest request, MessageSession messageSession) {
        log.info("testConnected ok");
    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.TEXT_MESSAGE)
    public void testTextMessage(HttpRequest request, TextWebSocketFrame text) {
        log.info("testTextMessage ok, received message: {}", text.text());
    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.BINARY_MESSAGE)
    public void testBinaryMessage(HttpRequest request, BinaryWebSocketFrame binary) {
        log.info("testTextMessage ok, received message: {}", binary.content());
    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.ON_PING)
    public void testPing() {
        log.info("get ping");
    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.ON_ERROR)
    public void testError(Exception e) {
        log.info("get exception");
    }

    @RequestMapping("/info")
    @ResponseBody
    public Object getInfo() {

        Set<String> registeredUris = messageSender.getRegisteredUri();
        Map<String, Integer> sessionNums = new HashMap<>();
        for (String registeredUri : registeredUris) {
            sessionNums.put(registeredUri, messageSender.getSessionNums(registeredUri));
        }

        Map<String, Object> dataMap = new HashMap<>(1);
        dataMap.put("session map", sessionNums);

        return dataMap;
    }

}
