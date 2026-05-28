package com.github.berrywang1996.netty.spring.web.mvc.context;

import com.github.berrywang1996.netty.spring.web.util.StringUtil;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Model class representing an HTTP cookie with support for serialization to
 * {@code Set-Cookie} header format and parsing from {@code Cookie} header strings.
 * <p>
 * This class holds all standard cookie attributes: name, value, domain, path,
 * expires, max-age, secure, and httpOnly. It provides static utility methods
 * to convert a {@code Cookie} instance into a {@code Set-Cookie} header value
 * and to parse an inbound {@code Cookie} header string into a name-value map.
 *
 * @author berrywang1996
 * @version V1.0.0
 */
public class Cookie {

    /** Separator between cookie attributes in the Set-Cookie header. */
    private static final String COOKIE_SEPARATOR = "; ";

    /** Equals sign used between attribute name and value. */
    private static final String COOKIE_EQUAL_MARK = "=";

    /** Date format pattern for the {@code expires} attribute, per the HTTP specification. */
    public static final String COOKIE_DATE_FORMAT_PATTERN = "EEE, dd MMM yyyy HH:mm:ss zzz";

    /** Timezone used for cookie date formatting. */
    public static final String COOKIE_DATE_GMT_TIMEZONE = "GMT";

    /** Pre-configured formatter for cookie expiration dates in GMT. */
    private static final DateTimeFormatter COOKIE_DATE_FORMATTER =
            DateTimeFormatter.ofPattern(COOKIE_DATE_FORMAT_PATTERN, Locale.US).withZone(ZoneId.of(COOKIE_DATE_GMT_TIMEZONE));

    /**
     * Constructs an empty cookie with no attributes set.
     */
    public Cookie() {
    }

    /**
     * Constructs a cookie with the given name.
     *
     * @param name the cookie name
     */
    public Cookie(String name) {
        this.name = name;
    }

    private String name;

    private String value;

    private String domain;

    private String path;

    private Date expires;

    private Integer maxAge;

    private Boolean secure;

    private Boolean httpOnly;

    /**
     * Sets the cookie name.
     *
     * @param name the cookie name
     */
    public void setName(String name) {
        this.name = name;
    }

    /**
     * Sets the cookie value.
     *
     * @param value the cookie value
     */
    public void setValue(String value) {
        this.value = value;
    }

    /**
     * Sets the domain scope of the cookie.
     *
     * @param domain the domain to which the cookie is sent
     */
    public void setDomain(String domain) {
        this.domain = domain;
    }

    /**
     * Sets the URL path scope of the cookie.
     *
     * @param path the path prefix for which the cookie is valid
     */
    public void setPath(String path) {
        this.path = path;
    }

    /**
     * Sets the expiration date of the cookie.
     *
     * @param expires the date when the cookie expires
     */
    public void setExpires(Date expires) {
        this.expires = expires;
    }

    /**
     * Sets the maximum age of the cookie in seconds.
     *
     * @param maxAge the max age in seconds; {@code null} means session cookie
     */
    public void setMaxAge(Integer maxAge) {
        this.maxAge = maxAge;
    }

    /**
     * Sets whether the cookie should only be sent over secure (HTTPS) connections.
     *
     * @param secure {@code true} to restrict to HTTPS only
     */
    public void setSecure(Boolean secure) {
        this.secure = secure;
    }

    /**
     * Sets whether the cookie is inaccessible to client-side scripts.
     *
     * @param httpOnly {@code true} to set the HTTPOnly flag
     */
    public void setHttpOnly(Boolean httpOnly) {
        this.httpOnly = httpOnly;
    }

    /**
     * Returns the cookie name.
     *
     * @return the cookie name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the cookie value.
     *
     * @return the cookie value
     */
    public String getValue() {
        return value;
    }

    /**
     * Returns the domain scope of the cookie.
     *
     * @return the cookie domain
     */
    public String getDomain() {
        return domain;
    }

    /**
     * Returns the URL path scope of the cookie.
     *
     * @return the cookie path
     */
    public String getPath() {
        return path;
    }

    /**
     * Returns the expiration date of the cookie.
     *
     * @return the expiration date, or {@code null} for session cookies
     */
    public Date getExpires() {
        return expires;
    }

    /**
     * Returns the maximum age of the cookie in seconds.
     *
     * @return the max age in seconds, or {@code null} if not set
     */
    public Integer getMaxAge() {
        return maxAge;
    }

    /**
     * Returns whether the cookie is restricted to HTTPS connections.
     *
     * @return {@code true} if the Secure flag is set
     */
    public Boolean getSecure() {
        return secure;
    }

    /**
     * Returns whether the cookie is inaccessible to client-side scripts.
     *
     * @return {@code true} if the HTTPOnly flag is set
     */
    public Boolean getHttpOnly() {
        return httpOnly;
    }

    /**
     * Serializes a {@link Cookie} object into a {@code Set-Cookie} header string.
     * <p>
     * Appends each non-null attribute (domain, path, expires, max-age, Secure, HTTPOnly)
     * separated by {@code "; "}. Returns {@code null} if the cookie has no name.
     *
     * @param cookie the cookie to serialize
     * @return the formatted {@code Set-Cookie} header value, or {@code null} if the cookie name is blank
     */
    public static String toHeaderStrings(Cookie cookie) {
        if (StringUtil.isBlank(cookie.getName())) {
            return null;
        }
        // Build "name=value" followed by optional attributes
        StringBuilder sb = new StringBuilder(100);
        sb.append(cookie.getName());
        sb.append(COOKIE_EQUAL_MARK);
        if (cookie.getValue() != null) {
            sb.append(cookie.getValue());
        }
        sb.append(COOKIE_SEPARATOR);
        // Append domain attribute if present
        if (cookie.getDomain() != null) {
            sb.append("domain");
            sb.append(COOKIE_EQUAL_MARK);
            sb.append(cookie.getDomain());
            sb.append(COOKIE_SEPARATOR);
        }
        // Append path attribute if present
        if (cookie.getPath() != null) {
            sb.append("path");
            sb.append(COOKIE_EQUAL_MARK);
            sb.append(cookie.getPath());
            sb.append(COOKIE_SEPARATOR);
        }
        // Append expires attribute formatted in GMT
        if (cookie.getExpires() != null) {
            sb.append("expires");
            sb.append(COOKIE_EQUAL_MARK);
            sb.append(COOKIE_DATE_FORMATTER.format(cookie.getExpires().toInstant()));
            sb.append(COOKIE_SEPARATOR);
        }
        // Append max-age attribute if present
        if (cookie.getMaxAge() != null) {
            sb.append("max-age");
            sb.append(COOKIE_EQUAL_MARK);
            sb.append(cookie.getMaxAge());
            sb.append(COOKIE_SEPARATOR);
        }
        // Append Secure flag if enabled
        if (cookie.getSecure() != null && cookie.getSecure()) {
            sb.append("Secure");
            sb.append(COOKIE_SEPARATOR);
        }
        // Append HTTPOnly flag if enabled
        if (cookie.getHttpOnly() != null && cookie.getHttpOnly()) {
            sb.append("HTTPOnly");
            sb.append(COOKIE_SEPARATOR);
        }
        // Remove the trailing separator
        sb.delete(sb.length() - COOKIE_SEPARATOR.length(), sb.length());
        return sb.toString();
    }

    /**
     * Parses a raw {@code Cookie} header string into a map of cookie name-value pairs.
     * <p>
     * The header string is expected to contain semicolon-separated {@code name=value} pairs
     * (e.g. {@code "sessionId=abc123; theme=dark"}). Entries that do not contain exactly
     * one {@code =} sign are skipped.
     *
     * @param cookieString the raw {@code Cookie} header string, may be {@code null}
     * @return an unmodifiable empty map if the input is {@code null} or empty;
     *         otherwise a mutable map of cookie names to values
     */
    public static Map<String, String> parseCookieString(String cookieString) {
        if (cookieString == null) {
            return Collections.emptyMap();
        }
        // Split on semicolons to get individual cookie key-value pairs
        String[] cookieKVs = cookieString.split(";");
        if (cookieKVs.length == 0) {
            return Collections.emptyMap();
        }
        Map<String, String> cookies = new HashMap<>(cookieKVs.length);
        for (String cookieKV : cookieKVs) {
            cookieKV = cookieKV.trim();
            // Split on first "=" only — values may contain "=" (e.g. base64 tokens)
            String[] split = cookieKV.split("=", 2);
            if (split.length == 2) {
                cookies.put(split[0].trim(), split[1]);
            }
        }
        return cookies;
    }

}
