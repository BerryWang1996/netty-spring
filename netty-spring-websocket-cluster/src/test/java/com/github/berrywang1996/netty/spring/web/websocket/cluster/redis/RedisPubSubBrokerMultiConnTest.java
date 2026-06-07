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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterMessageListener;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RedisPubSubBrokerMultiConnTest {

    private static final int N = 4;

    private static int expectedIndex(String uri) {
        return Math.floorMod(("netty:broadcast:" + uri).hashCode(), N);
    }

    @SuppressWarnings("unchecked")
    private static List<RedisPubSubAsyncCommands<String, String>> wireMockClient(RedisClient client) {
        when(client.connect()).thenReturn(mock(StatefulRedisConnection.class));
        List<StatefulRedisPubSubConnection<String, String>> conns = new ArrayList<>();
        List<RedisPubSubAsyncCommands<String, String>> asyncs = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            StatefulRedisPubSubConnection<String, String> conn = mock(StatefulRedisPubSubConnection.class);
            RedisPubSubAsyncCommands<String, String> async = mock(RedisPubSubAsyncCommands.class);
            when(conn.async()).thenReturn(async);
            conns.add(conn);
            asyncs.add(async);
        }
        when(client.connectPubSub()).thenReturn(conns.get(0), conns.get(1), conns.get(2), conns.get(3));
        return asyncs;
    }

    @Test
    void opensNConnectionsAndRoutesEachChannelToItsHashedConnection() {
        RedisClient client = mock(RedisClient.class);
        List<RedisPubSubAsyncCommands<String, String>> asyncs = wireMockClient(client);

        RedisPubSubBroker broker = new RedisPubSubBroker(client, new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator(), N);

        verify(client, times(N)).connectPubSub();

        // Six distinct URIs chosen so their broadcast channels hit >= 2 of the 4 connections. If a
        // future change to these strings makes them all share one index, pick others (compute
        // Math.floorMod(("netty:broadcast:"+uri).hashCode(), 4) to check).
        String[] uris = {"/ws/a", "/ws/b", "/ws/c", "/ws/d", "/ws/e", "/ws/f"};
        ClusterMessageListener noop = env -> { };
        Set<Integer> indicesUsed = new HashSet<>();
        for (String uri : uris) {
            int idx = expectedIndex(uri);
            indicesUsed.add(idx);
            broker.subscribe(uri, noop);
            verify(asyncs.get(idx)).subscribe("netty:broadcast:" + uri);
        }
        // each channel went ONLY to its hashed connection
        for (String uri : uris) {
            int idx = expectedIndex(uri);
            for (int j = 0; j < N; j++) {
                if (j != idx) {
                    verify(asyncs.get(j), never()).subscribe("netty:broadcast:" + uri);
                }
            }
        }
        assertTrue(indicesUsed.size() >= 2,
                "channels should spread across >= 2 connections, got " + indicesUsed);
    }

    @Test
    void sameChannelAlwaysRoutesToTheSameConnection() {
        RedisClient client = mock(RedisClient.class);
        List<RedisPubSubAsyncCommands<String, String>> asyncs = wireMockClient(client);

        RedisPubSubBroker broker = new RedisPubSubBroker(client, new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator(), N);

        int idx = expectedIndex("/ws/repeat");
        broker.subscribe("/ws/repeat", env -> { });
        broker.subscribe("/ws/repeat", env -> { });
        verify(asyncs.get(idx), times(2)).subscribe("netty:broadcast:/ws/repeat");
    }

    @Test
    void singleConnectionByDefault() {
        RedisClient client = mock(RedisClient.class);
        when(client.connect()).thenReturn(mock(StatefulRedisConnection.class));
        StatefulRedisPubSubConnection<String, String> conn = mock(StatefulRedisPubSubConnection.class);
        RedisPubSubAsyncCommands<String, String> async = mock(RedisPubSubAsyncCommands.class);
        when(conn.async()).thenReturn(async);
        when(client.connectPubSub()).thenReturn(conn);

        // 3-arg ctor must behave as a single connection (n=1).
        RedisPubSubBroker broker = new RedisPubSubBroker(client, new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator());

        verify(client, times(1)).connectPubSub();
        broker.subscribe("/ws/x", env -> { });
        broker.subscribe("/ws/y", env -> { });
        verify(async).subscribe("netty:broadcast:/ws/x");
        verify(async).subscribe("netty:broadcast:/ws/y");
    }
}
