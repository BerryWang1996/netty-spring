package com.github.berrywang1996.netty.spring.web.mvc.view;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public abstract class AbstractViewHandler<T> {

    private HttpResponseStatus status;

    private String contentType;

    private String charset;

    private T data;

    public HttpResponseStatus getStatus() {
        return status;
    }

    public void setStatus(HttpResponseStatus status) {
        this.status = status;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public String getCharset() {
        return charset;
    }

    public void setCharset(String charset) {
        this.charset = charset;
    }

    public abstract FullHttpResponse handleView(T data);

}
