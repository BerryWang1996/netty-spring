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
 * Wraps the HTTP request and response metadata for MVC processing within the Netty pipeline.
 * <p>
 * This context object carries the raw {@link HttpRequest}, parsed request parameters,
 * request/response headers, cookies, and the underlying {@link ChannelHandlerContext}.
 * It is created per-request and passed through the handler chain so that controller
 * methods can access all relevant HTTP artifacts in a unified way.
 *
 * @author berrywang1996
 * @version V1.0.0
 */
public class HttpRequestContext {

    /** The raw URI string from the HTTP request line. */
    private String requestUri;

    /** Parsed query-string parameters as key-value pairs. */
    private Map<String, String> requestParameters;

    /** The original Netty HTTP request object. */
    private HttpRequest httpRequest;

    /** Headers extracted from the inbound HTTP request. */
    private HttpHeaders requestHeaders;

    /** Headers to be written into the outbound HTTP response. */
    private HttpHeaders responseHeaders;

    /** Cookies parsed from the request's {@code Cookie} header. */
    private Map<String, String> requestCookies = new HashMap<>();

    /** Cookies to be set in the response via {@code Set-Cookie} headers. */
    private List<Cookie> responseCookies = new ArrayList<>();

    /** Uploaded files parsed from multipart/form-data requests, keyed by field name. */
    private Map<String, List<MultipartFile>> multipartFiles = new HashMap<>();

    /** The Netty channel context associated with this request. */
    private ChannelHandlerContext channelHandlerContext;

    /**
     * Returns the raw request URI string.
     *
     * @return the request URI, including query string if present
     */
    public String getRequestUri() {
        return requestUri;
    }

    /**
     * Sets the raw request URI string.
     *
     * @param requestUri the request URI to set
     */
    public void setRequestUri(String requestUri) {
        this.requestUri = requestUri;
    }

    /**
     * Returns the parsed request parameters map.
     *
     * @return a map of parameter names to their values
     */
    public Map<String, String> getRequestParameters() {
        return requestParameters;
    }

    /**
     * Sets the parsed request parameters.
     *
     * @param requestParameters a map of parameter names to their values
     */
    public void setRequestParameters(Map<String, String> requestParameters) {
        this.requestParameters = requestParameters;
    }

    /**
     * Returns the underlying Netty {@link HttpRequest}.
     *
     * @return the HTTP request object
     */
    public HttpRequest getHttpRequest() {
        return httpRequest;
    }

    /**
     * Sets the HTTP request and automatically extracts headers, URI, and cookies from it.
     * <p>
     * When this method is called, the following fields are populated in one step:
     * <ul>
     *   <li>{@code requestHeaders} - from {@code httpRequest.headers()}</li>
     *   <li>{@code requestUri} - from {@code httpRequest.uri()}</li>
     *   <li>{@code requestCookies} - parsed from the {@code Cookie} header</li>
     * </ul>
     *
     * @param httpRequest the Netty HTTP request to set
     */
    public void setHttpRequest(HttpRequest httpRequest) {
        this.httpRequest = httpRequest;
        // Extract headers, URI, and cookies from the raw request in one step
        this.requestHeaders = httpRequest.headers();
        this.requestUri = httpRequest.uri();
        this.requestCookies = Cookie.parseCookieString(httpRequest.headers().get(HttpHeaderNames.COOKIE));
    }

    /**
     * Returns the HTTP request headers.
     *
     * @return the request headers
     */
    public HttpHeaders getRequestHeaders() {
        return requestHeaders;
    }

    /**
     * Sets the HTTP request headers directly.
     *
     * @param requestHeaders the request headers to set
     */
    public void setRequestHeaders(HttpHeaders requestHeaders) {
        this.requestHeaders = requestHeaders;
    }

    /**
     * Returns the HTTP response headers that will be sent back to the client.
     *
     * @return the response headers, or {@code null} if not yet set
     */
    public HttpHeaders getResponseHeaders() {
        return responseHeaders;
    }

    /**
     * Sets the HTTP response headers.
     *
     * @param responseHeaders the response headers to set
     */
    public void setResponseHeaders(HttpHeaders responseHeaders) {
        this.responseHeaders = responseHeaders;
    }

    /**
     * Returns the cookies parsed from the inbound request.
     *
     * @return a map of cookie names to their values
     */
    public Map<String, String> getRequestCookies() {
        return requestCookies;
    }

    /**
     * Returns the list of cookies to be included in the response.
     *
     * @return the list of response cookies
     */
    public List<Cookie> getResponseCookies() {
        return responseCookies;
    }

    /**
     * Sets the list of cookies to be included in the response.
     *
     * @param responseCookies the response cookies to set
     */
    public void setResponseCookies(List<Cookie> responseCookies) {
        this.responseCookies = responseCookies;
    }

    /**
     * Returns the map of uploaded files from a multipart request.
     * Each key is the form field name, and the value is a list of files
     * (supporting multiple file selection per field).
     *
     * @return the multipart files map (never {@code null})
     */
    public Map<String, List<MultipartFile>> getMultipartFiles() {
        return multipartFiles;
    }

    /**
     * Returns the first uploaded file for the given field name.
     *
     * @param name the form field name
     * @return the first file, or {@code null} if no file was uploaded with that name
     */
    public MultipartFile getMultipartFile(String name) {
        List<MultipartFile> files = multipartFiles.get(name);
        return (files != null && !files.isEmpty()) ? files.get(0) : null;
    }

    /**
     * Adds an uploaded file to this request context.
     *
     * @param file the multipart file to add
     */
    public void addMultipartFile(MultipartFile file) {
        multipartFiles.computeIfAbsent(file.getName(), k -> new ArrayList<>()).add(file);
    }

    /**
     * Returns the Netty {@link ChannelHandlerContext} associated with this request.
     *
     * @return the channel handler context
     */
    public ChannelHandlerContext getChannelHandlerContext() {
        return channelHandlerContext;
    }

    /**
     * Sets the Netty {@link ChannelHandlerContext} for this request.
     *
     * @param channelHandlerContext the channel handler context to set
     */
    public void setChannelHandlerContext(ChannelHandlerContext channelHandlerContext) {
        this.channelHandlerContext = channelHandlerContext;
    }
}
