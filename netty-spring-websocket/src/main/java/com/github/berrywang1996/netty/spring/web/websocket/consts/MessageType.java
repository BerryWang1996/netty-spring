/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.websocket.consts;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
public enum MessageType {

    /**
     * Close current session if the method throws an exception or returns false of Boolean type. The method marked by
     * \@MessageMapping(messageType=ON_HANDSHAKE) will be execute before handshake. The method marked by
     * \@MessageMapping(messageType=ERROR) will be execute If the method throws an exception.
     */
    ON_HANDSHAKE,

    /**
     * Ignore return value. The method marked by @MessageMapping(messageType=ON_CONNECTED) will be execute when
     * connected. The method marked by @MessageMapping(messageType=ERROR) will be execute If the method throws an
     * exception.
     */
    ON_CONNECTED,

    /**
     * Ignore return value. The method marked by @MessageMapping(messageType=ON_PING) will be execute when received ping
     * frame. If no method marked by this, the server will response a PongWebSocketFrame. The method marked by
     * \@MessageMapping(messageType=ERROR) will be execute If the method throws an exception.
     */
    ON_PING,

    /**
     * Ignore return value. The method marked by @MessageMapping(messageType=TEXT_MESSAGE) will be execute when
     * received text message. The method marked by @MessageMapping (messageType = ERROR) will be execute If the
     * method throws an exception.
     */
    TEXT_MESSAGE,

    /**
     * Ignore return value. The method marked by @MessageMapping(messageType=BINARY_MESSAGE) will be execute when
     * received binary message. The method marked by @MessageMapping (messageType = ERROR) will be execute If the
     * method throws an exception.
     */
    BINARY_MESSAGE,

    /**
     * If the method marked by @MessageMapping(messageType=ON_HANDSHAKE/ON_CONNECTED/ON_PING/TEXT_MESSAGE/BINARY_MESSAGE
     * /OTHER) throws exception. The method marked by @MessageMapping(messageType=ERROR)the will be execute. Also you
     * can throws exception to netty.
     */
    ON_ERROR,

    /**
     * Ignore return value and exception. The method marked by @MessageMapping(messageType=ON_CLOSE) will be execute
     * when close session.
     */
    ON_CLOSE,

    /**
     * Ignore return value. The method marked by @MessageMapping(messageType=OTHER) will be execute when received
     * undefined message type. The method marked by @MessageMapping (messageType = ERROR) will be execute If the
     * method throws an exception.
     */
    OTHER

}
