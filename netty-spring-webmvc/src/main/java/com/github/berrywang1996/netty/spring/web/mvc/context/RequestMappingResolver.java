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

package com.github.berrywang1996.netty.spring.web.mvc.context;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.berrywang1996.netty.spring.web.context.MappingResolver;
import com.github.berrywang1996.netty.spring.web.databind.DataBindUtil;
import com.github.berrywang1996.netty.spring.web.mvc.consts.HttpRequestMethod;
import com.github.berrywang1996.netty.spring.web.util.ServiceHandlerUtil;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class RequestMappingResolver extends MappingResolver<FullHttpRequest, HttpRequestMethod> {

    public RequestMappingResolver(Map<HttpRequestMethod, Method> methods,
                                  Object invokeRef) {
        super(methods, invokeRef);
    }

    @Override
    public void resolve(ChannelHandlerContext ctx, FullHttpRequest msg) {

        // check request method
        HttpRequestMethod requestMethod = HttpRequestMethod.getInstance(msg.method().name());
        Method invokeMethod = getMethod(requestMethod);
        if (invokeMethod == null) {
            requestMethod = HttpRequestMethod.ALL;
            invokeMethod = getMethod(requestMethod);
            if (invokeMethod == null) {
                ServiceHandlerUtil.HttpErrorMessage errorMsg =
                        new ServiceHandlerUtil.HttpErrorMessage(
                                HttpResponseStatus.METHOD_NOT_ALLOWED,
                                msg.uri(),
                                null,
                                null);
                ServiceHandlerUtil.sendError(ctx, msg, errorMsg);
                return;
            }
        }

        // create request context
        HttpRequestContext requestContext = new HttpRequestContext();
        requestContext.setChannelHandlerContext(ctx);
        requestContext.setHttpRequest(msg);

        // parse request parameters
        Map<String, String> requestParameterMap = ServiceHandlerUtil.parseRequestParameters(msg);
        requestContext.setRequestParameters(requestParameterMap);

        // request data bind
        Map<String, Class> methodParamType = getMethodParamType(requestMethod);
        Map<String, String> tempMethodParamType = new HashMap<>();
        List<Object> parameters = new ArrayList<>(methodParamType.size());
        for (Map.Entry<String, Class> methodParamEntry : methodParamType.entrySet()) {

            // TODO validate data
            // TODO path variable

            // if method parameter is request context object
            if (methodParamEntry.getValue() == HttpRequestContext.class) {
                parameters.add(requestContext);
            } else if (methodParamEntry.getValue() == HttpRequest.class) {
                parameters.add(requestContext.getHttpRequest());
            } else if (methodParamEntry.getValue() == HttpHeaders.class) {
                parameters.add(requestContext.getRequestHeaders());
            } else if (methodParamEntry.getValue() == ChannelHandlerContext.class) {
                parameters.add(requestContext.getChannelHandlerContext());
            } else if (methodParamEntry.getValue() == String.class) {
                parameters.add(requestParameterMap.get(methodParamEntry.getKey()));
            } else {
                // else if start with method parameter name, put into tempMethodParamType
                for (String requestKey : requestParameterMap.keySet()) {
                    if (requestKey.startsWith(methodParamEntry.getKey())) {
                        tempMethodParamType.put(requestKey, requestParameterMap.get(requestKey));
                    }
                }
                parameters.add(DataBindUtil.parseStringToObject(tempMethodParamType, methodParamEntry.getValue()));
                tempMethodParamType.clear();
            }

        }

        // invoke method reference
        Object result = null;
        try {
            result = invokeMethod.invoke(getInvokeRef(), parameters.toArray());
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }

        // TODO handle return value
        if (result == null) {
            log.debug("return value is null");
        } else {
            try {
                log.debug("return value: {}", new ObjectMapper().writeValueAsString(result));
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }

        // TODO temp page
        // handle return value
        ByteBuf content = null;
        FullHttpResponse response = null;
        // html response
        content = Unpooled.copiedBuffer("<html><head><title>this is template page</title></head><body>hello " +
                "netty</body></html>", CharsetUtil.UTF_8);
        response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, content);
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/html; charset=UTF-8");

        response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, "chunked");
        ctx.writeAndFlush(response);

    }

}
