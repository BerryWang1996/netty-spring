package com.github.berrywang1996.netty.spring.web.mvc.context;

import com.github.berrywang1996.netty.spring.web.util.StringUtil;

import java.text.SimpleDateFormat;
import java.util.*;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
public class Cookie {

    private static final String COOKIE_SEPARATOR = "; ";

    private static final String COOKIE_EQUAL_MARK = "=";

    public static final String COOKIE_DATE_FORMAT_PATTERN = "EEE, dd MMM yyyy HH:mm:ss zzz";

    public static final String COOKIE_DATE_GMT_TIMEZONE = "GMT";

    public Cookie() {
    }

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

    public void setName(String name) {
        this.name = name;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public void setDomain(String domain) {
        this.domain = domain;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public void setExpires(Date expires) {
        this.expires = expires;
    }

    public void setMaxAge(Integer maxAge) {
        this.maxAge = maxAge;
    }

    public void setSecure(Boolean secure) {
        this.secure = secure;
    }

    public void setHttpOnly(Boolean httpOnly) {
        this.httpOnly = httpOnly;
    }

    public String getName() {
        return name;
    }

    public String getValue() {
        return value;
    }

    public String getDomain() {
        return domain;
    }

    public String getPath() {
        return path;
    }

    public Date getExpires() {
        return expires;
    }

    public Integer getMaxAge() {
        return maxAge;
    }

    public Boolean getSecure() {
        return secure;
    }

    public Boolean getHttpOnly() {
        return httpOnly;
    }

    public static String toHeaderStrings(Cookie cookie) {
        if (StringUtil.isBlank(cookie.getName())) {
            return null;
        }
        StringBuilder sb = new StringBuilder(100);
        sb.append(cookie.getName());
        sb.append(COOKIE_EQUAL_MARK);
        if (cookie.getValue() != null) {
            sb.append(cookie.getValue());
        }
        sb.append(COOKIE_SEPARATOR);
        if (cookie.getDomain() != null) {
            sb.append("domain");
            sb.append(COOKIE_EQUAL_MARK);
            sb.append(cookie.getDomain());
            sb.append(COOKIE_SEPARATOR);
        }
        if (cookie.getPath() != null) {
            sb.append("path");
            sb.append(COOKIE_EQUAL_MARK);
            sb.append(cookie.getPath());
            sb.append(COOKIE_SEPARATOR);
        }
        if (cookie.getExpires() != null) {
            sb.append("expires");
            sb.append(COOKIE_EQUAL_MARK);
            SimpleDateFormat dateFormatter = new SimpleDateFormat(COOKIE_DATE_FORMAT_PATTERN, Locale.US);
            dateFormatter.setTimeZone(TimeZone.getTimeZone(COOKIE_DATE_GMT_TIMEZONE));
            sb.append(dateFormatter.format(cookie.getExpires()));
            sb.append(COOKIE_SEPARATOR);
        }
        if (cookie.getMaxAge() != null) {
            sb.append("max-age");
            sb.append(COOKIE_EQUAL_MARK);
            sb.append(cookie.getMaxAge());
            sb.append(COOKIE_SEPARATOR);
        }
        if (cookie.getSecure() != null && cookie.getSecure()) {
            sb.append("Secure");
            sb.append(COOKIE_SEPARATOR);
        }
        if (cookie.getHttpOnly() != null && cookie.getHttpOnly()) {
            sb.append("HTTPOnly");
            sb.append(COOKIE_SEPARATOR);
        }
        sb.delete(sb.length() - 2, sb.length() - 1);
        return sb.toString();
    }

    public static Map<String, String> parseCookieString(String cookieString) {
        if (cookieString == null) {
            return Collections.EMPTY_MAP;
        }
        String[] cookieKVs = cookieString.split(";");
        if (cookieKVs.length == 0) {
            return Collections.EMPTY_MAP;
        }
        Map<String, String> cookies = new HashMap<>(cookieKVs.length);
        for (String cookieKV : cookieKVs) {
            cookieKV = cookieKV.trim();
            String[] split = cookieKV.split("=");
            if (split.length == 2) {
                cookies.put(split[0], split[1]);
            }
        }
        return cookies;
    }

}
