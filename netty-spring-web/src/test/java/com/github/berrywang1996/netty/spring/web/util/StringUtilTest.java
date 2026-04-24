package com.github.berrywang1996.netty.spring.web.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StringUtilTest {

    @Test
    void detectsBlankCharSequences() {
        assertTrue(StringUtil.isBlank(null));
        assertTrue(StringUtil.isBlank(""));
        assertTrue(StringUtil.isBlank("   "));
        assertTrue(StringUtil.isBlank(new StringBuilder(" \t ")));

        assertFalse(StringUtil.isBlank("netty"));
        assertFalse(StringUtil.isBlank(new StringBuffer("spring")));
    }

    @Test
    void detectsNotBlankCharSequences() {
        assertTrue(StringUtil.isNotBlank("websocket"));
        assertFalse(StringUtil.isNotBlank(" "));
    }
}
