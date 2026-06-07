/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterTraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class MdcClusterTraceContextTest {

    private final ClusterTraceContext tc = new MdcClusterTraceContext();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void currentTraceparent_prefersExplicitMdcTraceparent() {
        MDC.put("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");
        assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", tc.currentTraceparent());
    }

    @Test
    void currentTraceparent_synthesizesFrom32HexTraceIdAndSpanId() {
        MDC.put("traceId", "0af7651916cd43dd8448eb211c80319c");
        MDC.put("spanId", "b7ad6b7169203331");
        assertEquals("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01", tc.currentTraceparent());
    }

    @Test
    void currentTraceparent_leftPads64BitTraceId() {
        MDC.put("traceId", "8448eb211c80319c");
        MDC.put("spanId", "b7ad6b7169203331");
        assertEquals("00-00000000000000008448eb211c80319c-b7ad6b7169203331-01", tc.currentTraceparent());
    }

    @Test
    void currentTraceparent_nullWhenAbsentOrMalformed() {
        assertNull(tc.currentTraceparent());
        MDC.put("traceId", "not-hex-xxxx");
        MDC.put("spanId", "b7ad6b7169203331");
        assertNull(tc.currentTraceparent());
    }

    @Test
    void restore_putsTraceKeys_andScopeClearsThem() {
        String tp = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";
        try (ClusterTraceContext.Scope s = tc.restore(tp)) {
            assertEquals("0af7651916cd43dd8448eb211c80319c", MDC.get("traceId"));
            assertEquals("b7ad6b7169203331", MDC.get("spanId"));
            assertEquals(tp, MDC.get("netty.traceparent"));
        }
        assertNull(MDC.get("traceId"));
        assertNull(MDC.get("spanId"));
        assertNull(MDC.get("netty.traceparent"));
    }

    @Test
    void restore_nullOrMalformed_isNoop() {
        assertSame(ClusterTraceContext.NOOP, tc.restore(null));
        assertSame(ClusterTraceContext.NOOP, tc.restore("garbage"));
        assertSame(ClusterTraceContext.NOOP, tc.restore("00-tooShort-x-01"));
        assertNull(MDC.get("traceId"));
    }
}
