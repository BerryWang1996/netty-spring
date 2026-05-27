package com.github.berrywang1996.netty.spring.web.mvc.consts;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HttpRequestMethodTest {

    @Test
    void getInstanceReturnsCorrectEnumForAllMethods() {
        assertEquals(HttpRequestMethod.GET, HttpRequestMethod.getInstance("GET"));
        assertEquals(HttpRequestMethod.POST, HttpRequestMethod.getInstance("POST"));
        assertEquals(HttpRequestMethod.PUT, HttpRequestMethod.getInstance("PUT"));
        assertEquals(HttpRequestMethod.DELETE, HttpRequestMethod.getInstance("DELETE"));
        assertEquals(HttpRequestMethod.PATCH, HttpRequestMethod.getInstance("PATCH"));
        assertEquals(HttpRequestMethod.HEAD, HttpRequestMethod.getInstance("HEAD"));
        assertEquals(HttpRequestMethod.OPTIONS, HttpRequestMethod.getInstance("OPTIONS"));
        assertEquals(HttpRequestMethod.TRACE, HttpRequestMethod.getInstance("TRACE"));
        assertEquals(HttpRequestMethod.ALL, HttpRequestMethod.getInstance("ALL"));
    }

    @Test
    void getInstanceIsCaseInsensitive() {
        assertEquals(HttpRequestMethod.GET, HttpRequestMethod.getInstance("get"));
        assertEquals(HttpRequestMethod.POST, HttpRequestMethod.getInstance("post"));
        assertEquals(HttpRequestMethod.PUT, HttpRequestMethod.getInstance("Put"));
    }

    @Test
    void getInstanceReturnsNullForUnknownMethod() {
        assertNull(HttpRequestMethod.getInstance("UNKNOWN"));
        assertNull(HttpRequestMethod.getInstance("CONNECT"));
    }
}
