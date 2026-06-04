package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.pubsub.StatefulRedisClusterPubSubConnection;
import io.lettuce.core.cluster.pubsub.api.async.RedisClusterPubSubAsyncCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link RedisClusterModePubSubBroker} with a mocked {@link RedisClusterClient} (no live
 * cluster). Mirrors the standalone broker's contract: ACTIVE after construction, node-message
 * propagation enabled on the pub/sub connection, and {@code publish} fans out on the
 * {@code netty:broadcast:{uri}} channel via the publish connection's async commands.
 */
class RedisClusterModePubSubBrokerTest {

    @SuppressWarnings("unchecked")
    private final RedisAdvancedClusterAsyncCommands<String, String> publishAsync =
            mock(RedisAdvancedClusterAsyncCommands.class);

    @SuppressWarnings("unchecked")
    private final RedisClusterPubSubAsyncCommands<String, String> subscribeAsync =
            mock(RedisClusterPubSubAsyncCommands.class);

    @SuppressWarnings("unchecked")
    private final StatefulRedisClusterConnection<String, String> publishConn =
            mock(StatefulRedisClusterConnection.class);

    @SuppressWarnings("unchecked")
    private final StatefulRedisClusterPubSubConnection<String, String> subscribeConn =
            mock(StatefulRedisClusterPubSubConnection.class);

    private final RedisClusterClient client = mock(RedisClusterClient.class);

    private RedisClusterModePubSubBroker broker;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(client.connect()).thenReturn(publishConn);
        when(client.connectPubSub()).thenReturn(subscribeConn);
        when(publishConn.async()).thenReturn(publishAsync);
        when(subscribeConn.async()).thenReturn(subscribeAsync);
        // Build each RedisFuture stub as a completed local FIRST — rf() itself runs when(...) on a fresh
        // mock, and nesting that inside an outer when().thenReturn() corrupts Mockito's stubbing state
        // ("Unfinished stubbing"). publish() chains .exceptionally(...) on the returned future.
        RedisFuture<Long> publishFuture = rf(1L);
        RedisFuture<Void> subscribeFuture = rf(null);
        when(publishAsync.publish(anyString(), anyString())).thenReturn(publishFuture);
        when(subscribeAsync.subscribe(anyString())).thenReturn(subscribeFuture);

        broker = new RedisClusterModePubSubBroker(client, new SimpleTextEnvelopeCodec());
    }

    @Test
    void activeAfterConstructionAndPropagationEnabled() {
        assertEquals(BrokerState.ACTIVE, broker.state(), "broker is ACTIVE immediately after construction");
        // Critical for cluster receive: node-message propagation must be turned on before any subscribe.
        verify(subscribeConn).setNodeMessagePropagation(true);
        // Transport health listener must be attached to the cluster client.
        verify(client).addListener(any(io.lettuce.core.RedisConnectionStateListener.class));
    }

    @Test
    void publishFansOutOnBroadcastChannelViaPublishConnection() {
        ClusterEnvelope env = new ClusterEnvelope(
                "node-A", "/ws/x", ClusterEnvelope.MessageKind.BROADCAST,
                "T:hi".getBytes(), null, null, System.currentTimeMillis());

        broker.publish("/ws/x", env);

        // Same channel prefix as the standalone RedisPubSubBroker ("netty:broadcast:").
        verify(publishAsync).publish(eq("netty:broadcast:/ws/x"), anyString());
    }

    @SuppressWarnings("unchecked")
    private static <T> RedisFuture<T> rf(T value) {
        RedisFuture<T> f = mock(RedisFuture.class);
        CompletableFuture<T> cf = CompletableFuture.completedFuture(value);
        // publish() calls .exceptionally(...) on the future; delegate it to the completed CF.
        when(f.exceptionally(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> cf.exceptionally(inv.getArgument(0)));
        return f;
    }
}
