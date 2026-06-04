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

import com.github.berrywang1996.netty.spring.web.util.MdcUtil;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterTraceContext;
import org.slf4j.MDC;

/**
 * Zero-dependency, MDC-based {@link ClusterTraceContext}. Works with any tracer that writes the
 * conventional {@code traceId}/{@code spanId} MDC keys (Sleuth, Brave). On send it reads an explicit
 * {@code traceparent} MDC key, else synthesizes a W3C value from {@code traceId}+{@code spanId}; on
 * receive it parses the traceparent back into {@code traceId}/{@code spanId} (so existing
 * {@code %X{traceId}} log patterns light up) plus {@code netty.traceparent}.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
public class MdcClusterTraceContext implements ClusterTraceContext {

    private static final String TRACEPARENT = "traceparent";
    private static final String TRACE_ID = "traceId";
    private static final String SPAN_ID = "spanId";
    private static final String ZERO_PAD_16 = "0000000000000000";

    @Override
    public String currentTraceparent() {
        String explicit = MDC.get(TRACEPARENT);
        if (explicit != null && !explicit.isEmpty()) {
            return explicit;
        }
        return synthesize(MDC.get(TRACE_ID), MDC.get(SPAN_ID));
    }

    /** Build a W3C {@code 00-{trace32}-{span16}-01} from MDC trace/span ids, or null if unusable. */
    static String synthesize(String traceId, String spanId) {
        if (traceId == null || spanId == null) {
            return null;
        }
        String span = spanId.trim().toLowerCase();
        String trace = traceId.trim().toLowerCase();
        if (span.length() != 16 || !isHex(span)) {
            return null;
        }
        if (trace.length() == 16) {
            trace = ZERO_PAD_16 + trace;
        }
        if (trace.length() != 32 || !isHex(trace)) {
            return null;
        }
        return "00-" + trace + "-" + span + "-01";
    }

    @Override
    public Scope restore(String traceparent) {
        if (traceparent == null) {
            return NOOP;
        }
        String tp = traceparent.trim();
        String[] p = tp.split("-");
        if (p.length != 4 || p[1].length() != 32 || p[2].length() != 16 || !isHex(p[1]) || !isHex(p[2])) {
            return NOOP;
        }
        MDC.put(TRACE_ID, p[1]);
        MDC.put(SPAN_ID, p[2]);
        MDC.put(MdcUtil.KEY_TRACEPARENT, tp);
        return () -> {
            MDC.remove(TRACE_ID);
            MDC.remove(SPAN_ID);
            MDC.remove(MdcUtil.KEY_TRACEPARENT);
        };
    }

    private static boolean isHex(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }
}
