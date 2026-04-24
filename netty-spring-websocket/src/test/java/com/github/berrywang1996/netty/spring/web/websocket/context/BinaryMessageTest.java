package com.github.berrywang1996.netty.spring.web.websocket.context;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BinaryMessageTest {

    @Test
    void createsIndependentBinaryFramesForRepeatedSends() {
        ByteBuf binaryData = Unpooled.wrappedBuffer(new byte[]{1, 2, 3});
        BinaryMessage message = new BinaryMessage(binaryData);
        BinaryWebSocketFrame firstFrame = message.responseMsg();
        BinaryWebSocketFrame secondFrame = message.responseMsg();

        try {
            assertEquals(3, firstFrame.content().readableBytes());
            firstFrame.release();

            assertEquals(3, secondFrame.content().readableBytes());
            assertEquals(1, secondFrame.content().getByte(0));
            assertEquals(2, secondFrame.content().getByte(1));
            assertEquals(3, secondFrame.content().getByte(2));
        } finally {
            if (firstFrame.refCnt() > 0) {
                firstFrame.release();
            }
            if (secondFrame.refCnt() > 0) {
                secondFrame.release();
            }
            if (binaryData.refCnt() > 0) {
                binaryData.release();
            }
        }
    }
}
