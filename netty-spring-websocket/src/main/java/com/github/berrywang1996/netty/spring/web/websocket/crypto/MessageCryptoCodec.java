package com.github.berrywang1996.netty.spring.web.websocket.crypto;

import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

/**
 * Extension point for application-level websocket message encryption.
 */
public interface MessageCryptoCodec {

    WebSocketFrame encrypt(MessageSession session, WebSocketFrame plainFrame) throws Exception;

    WebSocketFrame decrypt(MessageSession session, WebSocketFrame encryptedFrame) throws Exception;
}
