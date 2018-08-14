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

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.databind.DataBindUtil;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.PathVariable;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestParam;
import com.github.berrywang1996.netty.spring.web.mvc.consts.HttpRequestMethod;
import com.github.berrywang1996.netty.spring.web.mvc.view.AbstractViewHandler;
import com.github.berrywang1996.netty.spring.web.mvc.view.JsonViewHandler;
import com.github.berrywang1996.netty.spring.web.util.ServiceHandlerUtil;
import com.github.berrywang1996.netty.spring.web.util.StringUtil;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.util.AntPathMatcher;
import org.springframework.util.PathMatcher;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class RequestMappingResolver extends AbstractMappingResolver<FullHttpRequest, HttpRequestMethod> {

    private final boolean isRestfulUrl;

    private final PathMatcher pathMatcher;

    private final String pathPattern;

    private final AbstractViewHandler viewHandler;

    public RequestMappingResolver(String url, Map<HttpRequestMethod, Method> methods, Object invokeRef,
                                  AbstractViewHandler viewHandler) {
        super(url, methods, invokeRef);
        this.viewHandler = viewHandler;

        boolean isRestfulUrlFlag = false;

        end:
        for (Method method : methods.values()) {
            Annotation[][] parameterAnnotations = method.getParameterAnnotations();
            if (parameterAnnotations.length > 0) {
                for (Annotation[] parameterAnnotation : parameterAnnotations) {
                    for (Annotation annotation : parameterAnnotation) {
                        if (annotation.annotationType() == PathVariable.class) {
                            isRestfulUrlFlag = true;
                            break end;
                        }
                    }
                }
            }
        }

        if (isRestfulUrlFlag) {
            isRestfulUrl = true;
            pathMatcher = new AntPathMatcher();
            pathPattern = url;

        } else {
            isRestfulUrl = false;
            pathMatcher = null;
            pathPattern = null;
        }

    }

    @Override
    public Map<HttpRequestMethod, Map<String, Class>> parseMethodParameters() {
        Map<HttpRequestMethod, Map<String, Class>> tempMethodParamTypes = new HashMap<>();
        for (Map.Entry<HttpRequestMethod, Method> kMethodEntry : getMethods().entrySet()) {
            LinkedHashMap<String, Class> methodParams = new LinkedHashMap<>();
            LocalVariableTableParameterNameDiscoverer u = new LocalVariableTableParameterNameDiscoverer();
            String[] params = u.getParameterNames(kMethodEntry.getValue());
            Annotation[][] parameterAnnotations = kMethodEntry.getValue().getParameterAnnotations();
            Class<?>[] parameterTypes = kMethodEntry.getValue().getParameterTypes();
            if (params != null && parameterTypes.length == params.length) {
                for (int i = 0; i < params.length; i++) {
                    String key = params[i];
                    // check RequestParam and PathVariable
                    for (Annotation annotation : parameterAnnotations[i]) {
                        if (annotation.annotationType() == PathVariable.class) {
                            String value = ((PathVariable) annotation).value();
                            // if set annotation value
                            if (StringUtil.isNotBlank(value)) {
                                key = ((PathVariable) annotation).value();
                            }
                            break;
                        } else if (annotation.annotationType() == RequestParam.class) {
                            String value = ((RequestParam) annotation).value();
                            // if set annotation value
                            if (StringUtil.isNotBlank(value)) {
                                key = ((RequestParam) annotation).value();
                            }
                            break;
                        }
                    }
                    methodParams.put(key, parameterTypes[i]);
                }
            }
            tempMethodParamTypes.put(kMethodEntry.getKey(), methodParams);
        }
        return Collections.unmodifiableMap(tempMethodParamTypes);
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

        // parse path variable
        Map<String, String> pathParameterMap = null;
        if (isRestfulUrl) {
            pathParameterMap = pathMatcher.extractUriTemplateVariables(pathPattern, msg.uri());
        }

        // request data bind
        Map<String, Class> methodParamType = getMethodParamType(requestMethod);
        Map<String, String> tempMethodParamType = new HashMap<>();
        List<Object> parameters = new ArrayList<>(methodParamType.size());
        for (Map.Entry<String, Class> methodParamEntry : methodParamType.entrySet()) {

            // if is path variable
            if (isRestfulUrl) {
                String value = pathParameterMap.get(methodParamEntry.getKey());
                if (StringUtil.isNotBlank(value)) {
                    parameters.add(DataBindUtil.parseStringToBasicType(value, methodParamEntry.getValue()));
                    continue;
                }
            }

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
            } else if (DataBindUtil.isBasicType(methodParamEntry.getValue())) {
                parameters.add(DataBindUtil.parseStringToBasicType(
                        requestParameterMap.get(methodParamEntry.getKey()), methodParamEntry.getValue()));
            } else {
                // else if start with method parameter name, put into tempMethodParamType
                for (String requestKey : requestParameterMap.keySet()) {
                    if (requestKey.startsWith(methodParamEntry.getKey())) {
                        tempMethodParamType.put(requestKey, requestParameterMap.get(requestKey));
                    }
                }
                // TODO validate data
                parameters.add(DataBindUtil.parseStringToObject(tempMethodParamType, methodParamEntry.getValue()));
                tempMethodParamType.clear();
            }

        }

        // invoke method reference
        Object result = null;
        try {
            result = invokeMethod.invoke(getInvokeRef(), parameters.toArray());
        } catch (IllegalAccessException | InvocationTargetException e) {
            log.warn("Invoke mapping method error, {}", e);
        }

        // handle return value
        if (result == null) {
            log.debug("return value is null");
        }
        ctx.writeAndFlush(viewHandler.handleView(result));

    }

}
