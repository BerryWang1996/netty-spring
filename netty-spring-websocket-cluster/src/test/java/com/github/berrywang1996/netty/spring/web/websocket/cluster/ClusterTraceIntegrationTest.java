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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisPubSubBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import io.lettuce.core.RedisClient;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Real-Redis: a traceparent set on a published envelope survives the wire to a subscriber on another node. */
class ClusterTraceIntegrationTest {

    private static final String TP = "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01";

    private static ClusterEnvelope env(String origin, String uri, String traceparent) {
        return new ClusterEnvelope(origin, uri, ClusterEnvelope.MessageKind.BROADCAST,
                "T:hello".getBytes(), null, traceparent, System.currentTimeMillis());
    }

    @Test
    void traceparentSurvivesCrossNodeDelivery() throws Exception {
        Assumptions.assumeTrue(ClusterTestRedis.available(), "no Redis and no Docker");
        RedisClient ca = ClusterTestRedis.newClient();
        RedisClient cb = ClusterTestRedis.newClient();
        RedisPubSubBroker a = new RedisPubSubBroker(ca, new SimpleTextEnvelopeCodec());
        RedisPubSubBroker b = new RedisPubSubBroker(cb, new SimpleTextEnvelopeCodec());
        List<ClusterEnvelope> got = new CopyOnWriteArrayList<>();
        b.subscribe("/ws/trace", got::add);
        Thread.sleep(300);
        a.publish("/ws/trace", env("node-A", "/ws/trace", TP));
        long deadline = System.currentTimeMillis() + 4000;
        while (got.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(1, got.size(), "node B should receive the broadcast");
        assertEquals(TP, got.get(0).getTraceparent(), "traceparent must survive the cross-node wire");
        a.shutdown();
        b.shutdown();
    }
}
