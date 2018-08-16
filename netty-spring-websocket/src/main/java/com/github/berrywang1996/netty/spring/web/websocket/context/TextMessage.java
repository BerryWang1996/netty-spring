package com.github.berrywang1996.netty.spring.web.websocket.context;

import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class TextMessage extends AbstractMessage<TextWebSocketFrame> {

    private String content;

    public TextMessage(String content) {
        this.content = content;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    @Override
    public TextWebSocketFrame responseMsg() {
        return new TextWebSocketFrame(content);
    }
}
