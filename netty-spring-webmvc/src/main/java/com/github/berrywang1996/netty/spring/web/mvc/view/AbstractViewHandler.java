package com.github.berrywang1996.netty.spring.web.mvc.view;

import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;

/**
 * Abstract base class for view rendering in the Netty MVC framework.
 * <p>
 * Subclasses are responsible for converting a data object of type {@code T} into a
 * Netty {@link FullHttpResponse}. Each subclass defines the default HTTP status code,
 * content type, and character set. Concrete implementations include
 * {@link HtmlViewHandler} for HTML responses and
 * {@link com.github.berrywang1996.netty.spring.web.mvc.view.JsonViewHandler JsonViewHandler}
 * for JSON responses.
 *
 * @param <T> the type of data this view handler can render
 * @author berrywang1996
 * @version V1.0.0
 */
public abstract class AbstractViewHandler<T> {

    /** The HTTP response status code (e.g. 200 OK). */
    private HttpResponseStatus status;

    /** The MIME content type (e.g. "text/html", "application/json"). */
    private String contentType;

    /** The character encoding for the response body (e.g. "utf-8"). */
    private String charset;

    /** The data payload to be rendered (not always used directly). */
    private T data;

    /**
     * Returns the HTTP response status.
     *
     * @return the response status
     */
    public HttpResponseStatus getStatus() {
        return status;
    }

    /**
     * Sets the HTTP response status.
     *
     * @param status the HTTP response status to set
     */
    public void setStatus(HttpResponseStatus status) {
        this.status = status;
    }

    /**
     * Returns the MIME content type for the response.
     *
     * @return the content type string
     */
    public String getContentType() {
        return contentType;
    }

    /**
     * Sets the MIME content type for the response.
     *
     * @param contentType the content type to set (e.g. "application/json")
     */
    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    /**
     * Returns the character encoding for the response body.
     *
     * @return the charset name
     */
    public String getCharset() {
        return charset;
    }

    /**
     * Sets the character encoding for the response body.
     *
     * @param charset the charset name to set (e.g. "utf-8")
     */
    public void setCharset(String charset) {
        this.charset = charset;
    }

    /**
     * Renders the given data object into a Netty {@link FullHttpResponse}.
     * <p>
     * Implementations should encode the data using the configured content type
     * and charset, set appropriate response headers, and return a complete
     * HTTP response ready to be written to the channel.
     *
     * @param data the data object to render
     * @return a fully constructed HTTP response
     */
    public abstract FullHttpResponse handleView(T data);

}
