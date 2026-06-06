/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterMessageListener;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import io.lettuce.core.cluster.pubsub.api.async.RedisClusterPubSubAsyncCommands;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.lettuce.core.pubsub.api.async.RedisPubSubAsyncCommands;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * L3 — confirms the inbound-size guard on all 3 Redis brokers measures UTF-8 BYTE length, not
 * UTF-16 char count, so a payload of multi-byte chars whose char-length is under the cap but whose
 * UTF-8 byte length is over is correctly rejected. Mirrors {@code NatsClusterBroker.onInboundMessage}
 * which is already byte-correct.
 *
 * <p>The brokers expose {@code onInboundMessage} / {@code onClusterMessage} / {@code deliver}
 * privately, so each test invokes the relevant entry point via reflection — the goal is to prove
 * the size guard itself does the right thing, not to exercise the full Lettuce pipeline.
 */
class RedisBrokerInboundSizingTest {

    /**
     * Crafts a UTF-8 BYTE-heavy payload whose char count is BELOW the cap and whose UTF-8 byte
     * count is ABOVE the cap. Latin small letter y with diaeresis (U+00FF) is 1 char but 2 UTF-8
     * bytes (C3 BF). 60 chars = 120 UTF-8 bytes, comfortably above a 100-byte cap.
     */
    private static String oversizedUtf8Payload() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append('ÿ');
        }
        return sb.toString();
    }

    @Test
    void utf8Payload_underByteCount_butOverBytes_isProvablyConstructedCorrectly() {
        String payload = oversizedUtf8Payload();
        int chars = payload.length();
        int utf8 = payload.getBytes(StandardCharsets.UTF_8).length;
        // The premise of the test: a 100-byte cap MUST reject this payload by bytes but would have
        // been wrongly accepted by the old char-based guard.
        assertTrue(chars <= 100, "fixture must be <= 100 chars so the OLD char check would PASS; got chars=" + chars);
        assertTrue(utf8 > 100, "fixture must be > 100 UTF-8 bytes so the NEW byte check REJECTS it; got bytes=" + utf8);
    }

    // ---------- RedisPubSubBroker ----------

    @SuppressWarnings("unchecked")
    private static RedisPubSubBroker buildStandaloneBrokerWithSubscription(List<ClusterEnvelope> sink, String channel) {
        RedisClient client = mock(RedisClient.class);
        when(client.connect()).thenReturn(mock(StatefulRedisConnection.class));
        StatefulRedisPubSubConnection<String, String> sub = mock(StatefulRedisPubSubConnection.class);
        RedisPubSubAsyncCommands<String, String> subAsync = mock(RedisPubSubAsyncCommands.class);
        when(sub.async()).thenReturn(subAsync);
        when(client.connectPubSub()).thenReturn(sub);

        RedisPubSubBroker broker = new RedisPubSubBroker(client, new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator(), 1);
        broker.setInboundMaxBytes(100);
        // Register a listener on the broadcast channel so the guard either drops (sink empty) or
        // permits delivery (sink grows) — both branches are observable.
        broker.subscribe(channel.substring("netty:broadcast:".length()), sink::add);
        return broker;
    }

    @Test
    void standaloneBroker_dropsOversizedUtf8InboundMessage() throws Exception {
        List<ClusterEnvelope> got = new ArrayList<>();
        RedisPubSubBroker broker = buildStandaloneBrokerWithSubscription(got, "netty:broadcast:/ws/t");

        Method onInbound = RedisPubSubBroker.class.getDeclaredMethod("onInboundMessage", String.class, String.class);
        onInbound.setAccessible(true);
        onInbound.invoke(broker, "netty:broadcast:/ws/t", oversizedUtf8Payload());

        assertEquals(0, got.size(), "oversized UTF-8 payload must be dropped before decode");
    }

    @Test
    void standaloneBroker_acceptsSmallInboundMessage() throws Exception {
        List<ClusterEnvelope> got = new ArrayList<>();
        RedisPubSubBroker broker = buildStandaloneBrokerWithSubscription(got, "netty:broadcast:/ws/t");

        // Build a valid encoded envelope via the codec so the guard's success branch flows through
        // decode + listener dispatch unobstructed.
        ClusterEnvelope env = new ClusterEnvelope("node-A", "/ws/t", ClusterEnvelope.MessageKind.BROADCAST,
                "T:ok".getBytes(), null, null, System.currentTimeMillis());
        String encoded = new SimpleTextEnvelopeCodec().encode(env);

        Method onInbound = RedisPubSubBroker.class.getDeclaredMethod("onInboundMessage", String.class, String.class);
        onInbound.setAccessible(true);
        onInbound.invoke(broker, "netty:broadcast:/ws/t", encoded);

        assertEquals(1, got.size(), "in-bounds encoded envelope must be delivered");
    }

    // ---------- RedisClusterModePubSubBroker ----------

    @SuppressWarnings("unchecked")
    private static RedisClusterModePubSubBroker buildClusterBrokerWithSubscription(List<ClusterEnvelope> sink,
                                                                                   String channel) {
        RedisClusterClient client = mock(RedisClusterClient.class);
        StatefulRedisClusterConnection<String, String> publishConn = mock(StatefulRedisClusterConnection.class);
        StatefulRedisClusterPubSubConnection<String, String> subscribeConn = mock(StatefulRedisClusterPubSubConnection.class);
        RedisAdvancedClusterAsyncCommands<String, String> publishAsync = mock(RedisAdvancedClusterAsyncCommands.class);
        RedisClusterPubSubAsyncCommands<String, String> subscribeAsync = mock(RedisClusterPubSubAsyncCommands.class);

        when(client.connect()).thenReturn(publishConn);
        when(client.connectPubSub()).thenReturn(subscribeConn);
        when(publishConn.async()).thenReturn(publishAsync);
        when(subscribeConn.async()).thenReturn(subscribeAsync);

        // subscribeAsync.subscribe(...) returns a RedisFuture; stub a completed future so the broker
        // chain (.subscribe + downstream) doesn't NPE.
        RedisFuture<Void> subscribeFuture = completedRedisFuture(null);
        when(subscribeAsync.subscribe(anyString())).thenReturn(subscribeFuture);

        RedisClusterModePubSubBroker broker = new RedisClusterModePubSubBroker(client, new SimpleTextEnvelopeCodec());
        broker.setInboundMaxBytes(100);
        broker.subscribe(channel.substring("netty:broadcast:".length()), sink::add);
        return broker;
    }

    @Test
    void clusterModeBroker_dropsOversizedUtf8InboundMessage() throws Exception {
        List<ClusterEnvelope> got = new ArrayList<>();
        RedisClusterModePubSubBroker broker = buildClusterBrokerWithSubscription(got, "netty:broadcast:/ws/t");

        Method onCluster = RedisClusterModePubSubBroker.class
                .getDeclaredMethod("onClusterMessage", String.class, String.class);
        onCluster.setAccessible(true);
        onCluster.invoke(broker, "netty:broadcast:/ws/t", oversizedUtf8Payload());

        assertEquals(0, got.size(), "oversized UTF-8 payload must be dropped before decode (cluster-mode broker)");
    }

    @Test
    void clusterModeBroker_acceptsSmallInboundMessage() throws Exception {
        List<ClusterEnvelope> got = new ArrayList<>();
        RedisClusterModePubSubBroker broker = buildClusterBrokerWithSubscription(got, "netty:broadcast:/ws/t");

        ClusterEnvelope env = new ClusterEnvelope("node-A", "/ws/t", ClusterEnvelope.MessageKind.BROADCAST,
                "T:ok".getBytes(), null, null, System.currentTimeMillis());
        String encoded = new SimpleTextEnvelopeCodec().encode(env);

        Method onCluster = RedisClusterModePubSubBroker.class
                .getDeclaredMethod("onClusterMessage", String.class, String.class);
        onCluster.setAccessible(true);
        onCluster.invoke(broker, "netty:broadcast:/ws/t", encoded);

        assertEquals(1, got.size(), "in-bounds encoded envelope must be delivered (cluster-mode broker)");
    }

    // ---------- RedisStreamsReliableBroker ----------

    @SuppressWarnings("unchecked")
    private static RedisStreamsReliableBroker buildReliableBroker() {
        RedisClient client = mock(RedisClient.class);
        StatefulRedisConnection<String, String> conn = mock(StatefulRedisConnection.class);
        // commandConnection is built in the ctor; the ack path uses commandConnection.async().xack(...)
        // — stub the async so an ack inside deliver() does not NPE.
        io.lettuce.core.api.async.RedisAsyncCommands<String, String> async = mock(io.lettuce.core.api.async.RedisAsyncCommands.class);
        when(conn.async()).thenReturn(async);
        when(client.connect()).thenReturn(conn);

        return new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(),
                10000, 1000, 64, 1024, new NoOpMessageAuthenticator());
    }

    @Test
    void reliableBroker_dropsOversizedUtf8StreamEntry() throws Exception {
        RedisStreamsReliableBroker broker = buildReliableBroker();
        broker.setInboundMaxBytes(100);

        List<ClusterEnvelope> got = new ArrayList<>();
        ClusterMessageListener listener = got::add;

        io.lettuce.core.StreamMessage<String, String> oversized = new io.lettuce.core.StreamMessage<>(
                "netty:cluster:rstream:/ws/t", "1-0",
                java.util.Collections.singletonMap("e", oversizedUtf8Payload()));

        Method deliver = RedisStreamsReliableBroker.class.getDeclaredMethod(
                "deliver", String.class, String.class, io.lettuce.core.StreamMessage.class,
                ClusterMessageListener.class, java.util.Map.class);
        deliver.setAccessible(true);
        java.util.Map<String, Boolean> seen = new java.util.LinkedHashMap<>();
        deliver.invoke(broker, "netty:cluster:rstream:/ws/t", "g:node-B", oversized, listener, seen);

        assertEquals(0, got.size(), "oversized UTF-8 reliable entry must be dropped before unwrap/decode");
        // The entry must have been recorded as seen + ACKed (cleared from PEL).
        assertTrue(seen.containsKey("1-0"), "the oversized id must be tracked as seen so it is never re-delivered");
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private static <T> RedisFuture<T> completedRedisFuture(T value) {
        RedisFuture<T> f = mock(RedisFuture.class);
        CompletableFuture<T> cf = CompletableFuture.completedFuture(value);
        when(f.exceptionally(any())).thenAnswer(inv -> cf.exceptionally(inv.getArgument(0)));
        return f;
    }
}
