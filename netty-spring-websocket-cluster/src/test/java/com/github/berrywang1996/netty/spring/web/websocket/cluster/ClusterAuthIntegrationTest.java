package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisPubSubBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Real-Redis integration for HMAC envelope auth over the Pub/Sub broker. Skipped without localhost:16379. */
class ClusterAuthIntegrationTest {

    private static String REDIS_URI;
    private static final String SECRET = "this-is-a-32+char-cluster-secret!!";
    private static boolean redisAvailable;
    private static RedisClient probe;

    @BeforeAll
    static void check() {
        redisAvailable = ClusterTestRedis.available();
        if (redisAvailable) {
            REDIS_URI = ClusterTestRedis.uri();
            probe = RedisClient.create(REDIS_URI);
        }
    }
    @AfterAll static void done() { if (probe != null) try { probe.shutdown(); } catch (Exception ignored) {} }

    private static ClusterEnvelope env() {
        return new ClusterEnvelope("node-A", "/ws/auth", ClusterEnvelope.MessageKind.BROADCAST,
                "T:secret-msg".getBytes(), null, null, System.currentTimeMillis());
    }

    @Test
    void sameSecretAccepts_differentSecretRejects() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        RedisClient ca = RedisClient.create(REDIS_URI);
        RedisClient cb = RedisClient.create(REDIS_URI);
        HmacMessageAuthenticator authB = new HmacMessageAuthenticator(SECRET.getBytes(), true);
        RedisPubSubBroker a = new RedisPubSubBroker(ca, new SimpleTextEnvelopeCodec(),
                new HmacMessageAuthenticator(SECRET.getBytes(), true));
        RedisPubSubBroker b = new RedisPubSubBroker(cb, new SimpleTextEnvelopeCodec(), authB);
        List<ClusterEnvelope> got = new CopyOnWriteArrayList<>();
        b.subscribe("/ws/auth", got::add);
        Thread.sleep(300);
        a.publish("/ws/auth", env());
        long deadline = System.currentTimeMillis() + 4000;
        while (got.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(1, got.size(), "same-secret message is accepted");
        assertEquals(0, authB.getRejectedCount());

        RedisClient ca2 = RedisClient.create(REDIS_URI);
        RedisPubSubBroker a2 = new RedisPubSubBroker(ca2, new SimpleTextEnvelopeCodec(),
                new HmacMessageAuthenticator("a-totally-different-secret-32ch!!".getBytes(), true));
        got.clear();
        a2.publish("/ws/auth", env());
        Thread.sleep(800);
        assertTrue(got.isEmpty(), "a foreign-secret message must be rejected");
        assertTrue(authB.getRejectedCount() >= 1, "rejection is counted");

        a.shutdown(); b.shutdown(); a2.shutdown(); ca.shutdown(); cb.shutdown(); ca2.shutdown();
    }
}
