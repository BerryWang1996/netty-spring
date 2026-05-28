package com.github.berrywang1996.netty.spring.web.mvc.context;

import org.junit.jupiter.api.Test;

import java.util.Date;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CookieTest {

    @Test
    void toHeaderStringsWithNameAndValue() {
        Cookie cookie = new Cookie("session");
        cookie.setValue("abc123");
        String header = Cookie.toHeaderStrings(cookie);
        assertNotNull(header);
        assertTrue(header.startsWith("session=abc123"));
    }

    @Test
    void toHeaderStringsReturnsNullForBlankName() {
        Cookie cookie = new Cookie();
        assertNull(Cookie.toHeaderStrings(cookie));
    }

    @Test
    void toHeaderStringsIncludesDomain() {
        Cookie cookie = new Cookie("id");
        cookie.setValue("1");
        cookie.setDomain("example.com");
        String header = Cookie.toHeaderStrings(cookie);
        assertTrue(header.contains("domain=example.com"));
    }

    @Test
    void toHeaderStringsIncludesPath() {
        Cookie cookie = new Cookie("id");
        cookie.setValue("1");
        cookie.setPath("/api");
        String header = Cookie.toHeaderStrings(cookie);
        assertTrue(header.contains("path=/api"));
    }

    @Test
    void toHeaderStringsIncludesExpires() {
        Cookie cookie = new Cookie("id");
        cookie.setValue("1");
        cookie.setExpires(new Date(1700000000000L)); // 2023-11-14
        String header = Cookie.toHeaderStrings(cookie);
        assertNotNull(header);
        assertTrue(header.contains("expires="));
        // Should contain GMT timezone date format
        assertTrue(header.contains("GMT"));
    }

    @Test
    void toHeaderStringsIncludesMaxAge() {
        Cookie cookie = new Cookie("id");
        cookie.setValue("1");
        cookie.setMaxAge(3600);
        String header = Cookie.toHeaderStrings(cookie);
        assertTrue(header.contains("max-age=3600"));
    }

    @Test
    void toHeaderStringsIncludesSecureAndHttpOnly() {
        Cookie cookie = new Cookie("id");
        cookie.setValue("1");
        cookie.setSecure(true);
        cookie.setHttpOnly(true);
        String header = Cookie.toHeaderStrings(cookie);
        assertTrue(header.contains("Secure"));
        assertTrue(header.contains("HTTPOnly"));
    }

    @Test
    void parseCookieStringWithMultipleCookies() {
        Map<String, String> cookies = Cookie.parseCookieString("name=value; session=abc123");
        assertEquals(2, cookies.size());
        assertEquals("value", cookies.get("name"));
        assertEquals("abc123", cookies.get("session"));
    }

    @Test
    void parseCookieStringReturnsEmptyMapForNull() {
        Map<String, String> cookies = Cookie.parseCookieString(null);
        assertTrue(cookies.isEmpty());
    }

    @Test
    void parseCookieStringHandlesSingleCookie() {
        Map<String, String> cookies = Cookie.parseCookieString("token=xyz");
        assertEquals(1, cookies.size());
        assertEquals("xyz", cookies.get("token"));
    }

    @Test
    void toHeaderStringsSanitizesCrlfInValue() {
        Cookie cookie = new Cookie("session");
        cookie.setValue("abc\r\nSet-Cookie: evil=injected");
        String header = Cookie.toHeaderStrings(cookie);
        assertNotNull(header);
        // CRLF characters must be stripped to prevent header injection
        assertFalse(header.contains("\r"));
        assertFalse(header.contains("\n"));
        assertTrue(header.contains("session=abcSet-Cookie: evil=injected"));
    }

    @Test
    void toHeaderStringsSanitizesCrlfInDomain() {
        Cookie cookie = new Cookie("id");
        cookie.setValue("1");
        cookie.setDomain("example.com\r\nX-Injected: true");
        String header = Cookie.toHeaderStrings(cookie);
        assertFalse(header.contains("\r"));
        assertFalse(header.contains("\n"));
    }

    @Test
    void toHeaderStringsSanitizesNullByteInName() {
        Cookie cookie = new Cookie("ses\0sion");
        cookie.setValue("abc");
        String header = Cookie.toHeaderStrings(cookie);
        assertNotNull(header);
        assertFalse(header.contains("\0"));
        assertTrue(header.startsWith("session=abc"));
    }

    @Test
    void toHeaderStringsIncludesSameSite() {
        Cookie cookie = new Cookie("id");
        cookie.setValue("1");
        cookie.setSameSite("Strict");
        String header = Cookie.toHeaderStrings(cookie);
        assertTrue(header.contains("SameSite=Strict"));
    }

    @Test
    void toHeaderStringsSameSiteLaxAndNone() {
        Cookie cookie = new Cookie("tok");
        cookie.setValue("x");
        cookie.setSameSite("Lax");
        assertTrue(Cookie.toHeaderStrings(cookie).contains("SameSite=Lax"));

        cookie.setSameSite("None");
        cookie.setSecure(true);
        String header = Cookie.toHeaderStrings(cookie);
        assertTrue(header.contains("SameSite=None"));
        assertTrue(header.contains("Secure"));
    }

    @Test
    void toHeaderStringsOmitsSameSiteWhenNull() {
        Cookie cookie = new Cookie("id");
        cookie.setValue("1");
        // sameSite not set (null)
        String header = Cookie.toHeaderStrings(cookie);
        assertFalse(header.contains("SameSite"));
    }
}
