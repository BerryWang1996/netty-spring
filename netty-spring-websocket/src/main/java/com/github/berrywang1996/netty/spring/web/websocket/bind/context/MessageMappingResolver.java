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

package com.github.berrywang1996.netty.spring.web.websocket.bind.context;

import com.github.berrywang1996.netty.spring.web.context.MappingResolver;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;

import java.lang.reflect.Method;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
public class MessageMappingResolver extends MappingResolver<WebSocketFrame> {

    public MessageMappingResolver(Method method, Object invokeRef) {
        super(method, invokeRef);
    }

    @Override
    public void resolve(ChannelHandlerContext ctx, WebSocketFrame msg) {

    }

}
