package com.github.berrywang1996.netty.spring.web.websocket.context;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class BinaryMessage extends AbstractMessage<BinaryWebSocketFrame> {

    private ByteBuf binaryData;

    public BinaryMessage(ByteBuf binaryData) {
        this.binaryData = binaryData;
    }

    public ByteBuf getBinaryData() {
        return binaryData;
    }

    public void setBinaryData(ByteBuf binaryData) {
        this.binaryData = binaryData;
    }

    @Override
    public BinaryWebSocketFrame responseMsg() {
        return new BinaryWebSocketFrame(binaryData);
    }

}
