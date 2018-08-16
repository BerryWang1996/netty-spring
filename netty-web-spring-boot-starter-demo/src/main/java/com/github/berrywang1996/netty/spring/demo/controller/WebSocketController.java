package com.github.berrywang1996.netty.spring.demo.controller;

import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.ResponseBody;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.AutowiredMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.context.BinaryMessage;
import com.github.berrywang1996.netty.spring.web.websocket.context.Message;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.TextMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
@Slf4j
@Controller
@MessageMapping("/ws")
public class WebSocketController {

    @AutowiredMessageSender
    private MessageSender messageSender;

    private static final String TEST_URI = "/test";

    @RequestMapping("/info")
    @ResponseBody
    public Object getInfo() {

        Map<String, Object> dataMap = new HashMap<>();
        dataMap.put("session numbers", messageSender.getSessionNums());
        dataMap.put("test url session nums", messageSender.getSessionNums("/ws/test"));

        return dataMap;
    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.HANDSHAKE)
    public boolean testHandShake() {
        return true;
    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.TEXT_MESSAGE)
    public void testTextMessage() {

    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.BINARY_MESSAGE)
    public void testBinaryMessage() {

    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.PING)
    public void testPing() {

    }

    @MessageMapping(value = TEST_URI, messageType = MessageType.ERROR)
    public void testError() {

    }

    public void testSendMessage() {
        try {
            String sessionId1 = UUID.randomUUID().toString();
            String sessionId2 = UUID.randomUUID().toString();
            if (messageSender.isSessionAlive(sessionId1, sessionId2)) {
                messageSender.sendMessage(TEST_URI, new Message(), sessionId1);
                messageSender.sendMessage(TEST_URI, new BinaryMessage(), sessionId1, sessionId2);
                messageSender.topicMessage(TEST_URI, new TextMessage());
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

}
