package com.github.berrywang1996.netty.spring.web.mvc.context;

import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpRequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class HttpRequestContext {

    private String requestUri;

    private Map<String, String> requestParameters;

    private HttpRequest httpRequest;

    private HttpHeaders requestHeaders;

    private HttpHeaders responseHeaders;

    private Map<String, String> requestCookies = new HashMap<>();

    private List<Cookie> responseCookies = new ArrayList<>();

    private ChannelHandlerContext channelHandlerContext;

    public String getRequestUri() {
        return requestUri;
    }

    public void setRequestUri(String requestUri) {
        this.requestUri = requestUri;
    }

    public Map<String, String> getRequestParameters() {
        return requestParameters;
    }

    public void setRequestParameters(Map<String, String> requestParameters) {
        this.requestParameters = requestParameters;
    }

    public HttpRequest getHttpRequest() {
        return httpRequest;
    }

    public void setHttpRequest(HttpRequest httpRequest) {
        this.httpRequest = httpRequest;
        this.requestHeaders = httpRequest.headers();
        this.requestUri = httpRequest.uri();
        this.requestCookies = Cookie.parseCookieString(httpRequest.headers().get(HttpHeaderNames.COOKIE));
    }

    public HttpHeaders getRequestHeaders() {
        return requestHeaders;
    }

    public void setRequestHeaders(HttpHeaders requestHeaders) {
        this.requestHeaders = requestHeaders;
    }

    public HttpHeaders getResponseHeaders() {
        return responseHeaders;
    }

    public void setResponseHeaders(HttpHeaders responseHeaders) {
        this.responseHeaders = responseHeaders;
    }

    public Map<String, String> getRequestCookies() {
        return requestCookies;
    }

    public List<Cookie> getResponseCookies() {
        return responseCookies;
    }

    public void setResponseCookies(List<Cookie> responseCookies) {
        this.responseCookies = responseCookies;
    }

    public ChannelHandlerContext getChannelHandlerContext() {
        return channelHandlerContext;
    }

    public void setChannelHandlerContext(ChannelHandlerContext channelHandlerContext) {
        this.channelHandlerContext = channelHandlerContext;
    }
}
