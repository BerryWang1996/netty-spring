package com.github.berrywang1996.netty.spring.web.websocket.context;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;

/**
 * WebSocket binary message. This class copies the binary data on construction
 * so that the caller does not need to manage Netty reference counts.
 * Each call to {@link #responseMsg()} returns a new {@link BinaryWebSocketFrame}
 * backed by a fresh retained copy, which is safe for multi-session broadcast.
 *
 * @author berrywang1996
 * @version V1.0.0
 */
public class BinaryMessage extends AbstractMessage<BinaryWebSocketFrame> {

    private byte[] binaryData;

    /**
     * Create a BinaryMessage from a byte array. The array is copied internally.
     */
    public BinaryMessage(byte[] binaryData) {
        if (binaryData == null) {
            throw new IllegalArgumentException("binaryData must not be null");
        }
        this.binaryData = binaryData.clone();
    }

    /**
     * Create a BinaryMessage from a ByteBuf. The readable bytes are copied into
     * a byte array so the caller can release the original ByteBuf freely.
     */
    public BinaryMessage(ByteBuf binaryData) {
        if (binaryData == null) {
            throw new IllegalArgumentException("binaryData must not be null");
        }
        this.binaryData = new byte[binaryData.readableBytes()];
        binaryData.getBytes(binaryData.readerIndex(), this.binaryData);
    }

    public byte[] getBinaryData() {
        return binaryData;
    }

    public void setBinaryData(byte[] binaryData) {
        this.binaryData = binaryData;
    }

    @Override
    public BinaryWebSocketFrame responseMsg() {
        return new BinaryWebSocketFrame(Unpooled.wrappedBuffer(binaryData.clone()));
    }

}
