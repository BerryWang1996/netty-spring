package com.github.berrywang1996.netty.spring.web.util;

import org.junit.jupiter.api.Test;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServiceHandlerUtilMimeTest {

    @Test
    void jakartaActivationPreservesCommonTypesFromThe1101Baseline() {
        assertEquals("text/html", ServiceHandlerUtil.getContentType(new File("index.html")));
        assertEquals("text/plain", ServiceHandlerUtil.getContentType(new File("readme.txt")));
        assertEquals("image/jpeg", ServiceHandlerUtil.getContentType(new File("photo.jpg")));
        assertEquals("image/png", ServiceHandlerUtil.getContentType(new File("icon.png")));
    }

    @Test
    void unknownExtensionsStillUseTheBinaryFallback() {
        assertEquals("application/octet-stream",
                ServiceHandlerUtil.getContentType(new File("unknown.nettyunknown")));
    }
}
