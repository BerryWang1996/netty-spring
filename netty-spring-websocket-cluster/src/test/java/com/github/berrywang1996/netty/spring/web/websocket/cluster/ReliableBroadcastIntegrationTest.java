package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisStreamsReliableBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterSubscription;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Real-Redis integration for reliable broadcast (Redis Streams). Skipped without localhost:16379. */
class ReliableBroadcastIntegrationTest {

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> conn;
    private static boolean redisAvailable;

    @BeforeAll
    static void check() {
        redisAvailable = ClusterTestRedis.available();
        if (!redisAvailable) {
            return;
        }
        client = ClusterTestRedis.newClient();
        conn = client.connect();
        wipe();
    }

    @AfterAll
    static void cleanup() {
        if (conn != null) { try { wipe(); conn.close(); } catch (Exception ignored) {} }
        if (client != null) { try { client.shutdown(); } catch (Exception ignored) {} }
    }

    private static void wipe() {
        conn.sync().eval("for _,k in ipairs(redis.call('keys','netty:cluster:rstream*')) do redis.call('del',k) end "
                + "redis.call('del','netty:cluster:rstreams')", io.lettuce.core.ScriptOutputType.STATUS);
    }

    private static ClusterEnvelope env(String origin, String uri, String text) {
        return new ClusterEnvelope(origin, uri, ClusterEnvelope.MessageKind.BROADCAST,
                ("T:" + text).getBytes(), null, null, System.currentTimeMillis());
    }

    @Test
    void publishThenSubscribeDeliversToOtherNode() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        RedisStreamsReliableBroker a = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        RedisStreamsReliableBroker b = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        List<ClusterEnvelope> bGot = new CopyOnWriteArrayList<>();
        ClusterSubscription bs = b.reliableSubscribe("/ws/r1", "node-B", bGot::add);
        Thread.sleep(300);
        a.reliablePublish("/ws/r1", env("node-A", "/ws/r1", "m1"));
        long deadline = System.currentTimeMillis() + 5000;
        while (bGot.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(1, bGot.size(), "node B should receive the reliable broadcast");
        assertEquals("node-A", bGot.get(0).getOriginNodeId());
        bs.unsubscribe(); a.shutdown(); b.shutdown();
    }

    @Test
    void offlineNodeCatchesUpOnResubscribe() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        RedisStreamsReliableBroker a = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        RedisStreamsReliableBroker b = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        List<ClusterEnvelope> bGot = new CopyOnWriteArrayList<>();

        ClusterSubscription first = b.reliableSubscribe("/ws/r2", "node-B", bGot::add);
        Thread.sleep(300);
        first.unsubscribe();
        Thread.sleep(200);

        for (int i = 0; i < 5; i++) a.reliablePublish("/ws/r2", env("node-A", "/ws/r2", "x" + i));
        Thread.sleep(300);
        assertTrue(bGot.isEmpty(), "nothing delivered while B was offline");

        ClusterSubscription second = b.reliableSubscribe("/ws/r2", "node-B", bGot::add);
        long deadline = System.currentTimeMillis() + 6000;
        while (bGot.size() < 5 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(5, bGot.size(), "B must replay ALL 5 broadcasts missed while offline (at-least-once)");

        second.unsubscribe(); a.shutdown(); b.shutdown();
    }

    @Test
    void destroyConsumerGroupsForNodeRemovesGroup() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        RedisStreamsReliableBroker a = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        ClusterSubscription s = a.reliableSubscribe("/ws/r3", "dead-node", e -> {});
        Thread.sleep(200);
        assertFalse(conn.sync().xinfoGroups("netty:cluster:rstream:/ws/r3").isEmpty());
        s.unsubscribe();
        a.destroyConsumerGroupsForNode("dead-node");
        assertTrue(conn.sync().xinfoGroups("netty:cluster:rstream:/ws/r3").isEmpty(),
                "dead node's consumer group must be destroyed");
        a.shutdown();
    }
}
