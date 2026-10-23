import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.DefaultMessagePayloadCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisPubSubBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisSessionRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisStreamsReliableBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisOfflineQueueStore;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.room.RedisUserRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterSubscription;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.StoredMessage;
import com.github.berrywang1996.netty.spring.web.websocket.context.AbstractMessage;
import com.github.berrywang1996.netty.spring.web.websocket.context.JsonMessage;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import io.lettuce.core.Range;
import io.lettuce.core.RedisClient;
import io.lettuce.core.StreamMessage;
import io.lettuce.core.api.StatefulRedisConnection;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.util.ReferenceCountUtil;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Standalone migration probe, compiled separately against each release's extracted jars.
 * No Spring application, test doubles for Redis, production route, or shared mixed-version classpath.
 * LocalSender is an observable local delivery boundary; this probe does not claim browser transport coverage.
 * Commands: request-id TAB operation TAB base64(UTF-8 argument)...; responses/events are prefixed JSON.
 */
public final class RedisStateCompatibilityHarness implements AutoCloseable {
    private static final String URI = "/state-compat";
    private static final long LOCK_MS = 1500;
    // Deliberately public test fixture, used only in the isolated migration Redis project.
    private static final byte[] TEST_KEY = "migration-state-fixture-key-32bytes".getBytes(StandardCharsets.UTF_8);
    private static final DefaultMessagePayloadCodec PAYLOAD = new DefaultMessagePayloadCodec();
    private final SimpleTextEnvelopeCodec envelopes = new SimpleTextEnvelopeCodec();
    private final HmacMessageAuthenticator authenticator = new HmacMessageAuthenticator(TEST_KEY, true);
    private final RedisClient client;
    private final StatefulRedisConnection<String, String> conn;
    private final RedisPubSubBroker pubsub;
    private final RedisStreamsReliableBroker reliable;
    private final RedisUserRegistry users;
    private final RedisSessionRegistry sessions;
    private final ClusterMessageSender sender;
    private final String nodeId;
    private final List<ClusterSubscription> subscriptions = new ArrayList<>();

    enum State { READY }

    private RedisStateCompatibilityHarness(String redisUri, String nodeId) {
        this.nodeId = nodeId;
        client = RedisClient.create(redisUri);
        conn = client.connect();
        pubsub = new RedisPubSubBroker(client, envelopes);
        reliable = new RedisStreamsReliableBroker(client, envelopes, 10000, 100, 32, 1024, authenticator);
        users = new RedisUserRegistry(conn);
        sessions = new RedisSessionRegistry(conn);
        ClusterNodeManager manager = new ClusterNodeManager(nodeId, 1000, 5000, 10000, 0,
                new RedisClusterNodeHeartbeat(conn), sessions);
        sender = new ClusterMessageSender(new LocalSender(), pubsub, sessions, manager, 0);
        sender.setReliableBroker(reliable);
        sender.setUserRegistry(users);
        // The persistent broker/store APIs are exercised directly; no heartbeat or application routes start.
    }

    static Map<String, Object> map(Object... values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < values.length; i += 2) result.put((String) values[i], values[i + 1]);
        return result;
    }

    static synchronized void emit(Map<String, Object> value) {
        String json = new String(PAYLOAD.encode(new JsonMessage(value)), StandardCharsets.UTF_8);
        if (!json.startsWith("J:")) throw new IllegalStateException("Expected JSON result");
        System.out.println("STATE_COMPAT\t" + json.substring(2));
        System.out.flush();
    }

    static String version(String artifact) throws Exception {
        Properties p = new Properties();
        String resource = "META-INF/maven/io.github.berrywang1996/" + artifact + "/pom.properties";
        try (InputStream input = RedisStateCompatibilityHarness.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("No release metadata: " + resource);
            p.load(input);
        }
        return p.getProperty("version");
    }

    static JsonMessage fixture(String label, int sequence) {
        return new JsonMessage(map("kind", "state-compat", "label", label, "sequence", sequence,
                "unicode", "雪", "nullable", null, "date", new Date(0), "state", State.READY,
                "nested", map("list", Arrays.asList(true, sequence, "x"))));
    }

    static String frameText(AbstractMessage message) {
        Object frame = message.responseMsg();
        try {
            if (!(frame instanceof TextWebSocketFrame)) throw new AssertionError("Expected JSON text frame");
            return ((TextWebSocketFrame) frame).text();
        } finally { ReferenceCountUtil.release(frame); }
    }

    private Map<String, Object> envelope(ClusterEnvelope e) {
        return map("wireVersion", e.getVersion(), "origin", e.getOriginNodeId(), "uri", e.getUri(),
                "kind", e.getKind().name(), "timestamp", e.getTimestamp(),
                "payload", new String(e.getPayload(), StandardCharsets.UTF_8),
                "frame", frameText(PAYLOAD.decode(e.getPayload())));
    }

    private RedisOfflineQueueStore offline(long ttlSeconds) {
        return new RedisOfflineQueueStore(conn, envelopes, authenticator, nodeId, 1000,
                ttlSeconds, LOCK_MS, 100);
    }

    private static String userKey(String user, boolean lock) {
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(user.getBytes(StandardCharsets.UTF_8));
        return (lock ? "netty:offline-lock:{" : "netty:offline:{") + encoded + "}";
    }

    private List<Map<String, Object>> entries(String key) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (StreamMessage<String, String> entry : conn.sync().xrange(key, Range.unbounded())) {
            String wire = entry.getBody().get("e");
            Map<String, Object> row = map("id", entry.getId(), "wire", wire);
            String decoded = authenticator.unwrap(wire);
            if (decoded == null) throw new AssertionError("Stored HMAC was rejected");
            row.put("envelope", envelope(envelopes.decode(decoded)));
            result.add(row);
        }
        return result;
    }

    static void await(BooleanSupplier condition, String description) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= end) throw new AssertionError("Timed out: " + description);
            TimeUnit.MILLISECONDS.sleep(20); // bounded condition polling, never a correctness delay
        }
    }

    private Map<String, Object> command(String op, String[] a) throws Exception {
        switch (op) {
            case "R_SUB": {
                String uri = a[0], consumer = a[1];
                boolean block = Boolean.parseBoolean(a[2]);
                subscriptions.add(reliable.reliableSubscribe(uri, consumer, e -> {
                    Map<String, Object> event = envelope(e);
                    event.put("type", block ? "RELIABLE_BLOCKED" : "RELIABLE");
                    event.put("consumer", consumer);
                    emit(event);
                    if (block) {
                        try { new CountDownLatch(1).await(); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }
                }));
                return map("subscribed", uri, "consumer", consumer, "blockBeforeAck", block);
            }
            case "R_WRITE": {
                String key = "netty:cluster:rstream:" + a[0];
                long before = conn.sync().exists(key) == 0 ? 0 : conn.sync().xlen(key);
                int count = Integer.parseInt(a[2]);
                for (int i = 1; i <= count; i++) sender.reliableBroadcast(a[0], fixture(a[1], i));
                await(() -> conn.sync().xlen(key) == before + count, "durable reliable append");
                return map("written", count, "entries", entries(key));
            }
            case "R_STATE": {
                String key = "netty:cluster:rstream:" + a[0];
                return map("length", conn.sync().xlen(key),
                        "pending", conn.sync().xpending(key, "g:" + a[1]).getCount(), "entries", entries(key));
            }
            case "R_ACKED": {
                String key = "netty:cluster:rstream:" + a[0];
                await(() -> conn.sync().xpending(key, "g:" + a[1]).getCount() == 0, "reliable ACK");
                return map("pending", 0, "length", conn.sync().xlen(key));
            }
            case "O_WRITE": {
                RedisOfflineQueueStore store = offline(Long.parseLong(a[3]));
                try {
                    sender.setOfflineQueueStore(store);
                    String key = userKey(a[0], false);
                    long before = conn.sync().exists(key) == 0 ? 0 : conn.sync().xlen(key);
                    int count = Integer.parseInt(a[2]);
                    for (int i = 1; i <= count; i++) sender.sendToUser(a[0], fixture(a[1], i));
                    await(() -> conn.sync().xlen(key) == before + count && conn.sync().pttl(key) > 0,
                            "offline enqueue and TTL");
                    return map("written", count, "ttl", conn.sync().pttl(key), "entries", entries(key));
                } finally { store.shutdown(); }
            }
            case "O_DRAIN": {
                RedisOfflineQueueStore store = offline(Long.parseLong(a[2]));
                try {
                    List<StoredMessage> drained = store.drain(a[0]).toCompletableFuture().get(10, TimeUnit.SECONDS);
                    List<Map<String, Object>> messages = new ArrayList<>();
                    List<String> ids = new ArrayList<>();
                    for (StoredMessage message : drained) {
                        Map<String, Object> row = envelope(message.getEnvelope());
                        row.put("id", message.getId());
                        if (!sender.deliverOfflineMessage(URI, "compat-session", message))
                            throw new AssertionError("Offline local delivery failed");
                        if (org.slf4j.MDC.get(ClusterMessageSender.MDC_OFFLINE_MESSAGE_ID) != null)
                            throw new AssertionError("Offline MDC leaked after dispatch");
                        ids.add(message.getId()); messages.add(row);
                    }
                    if (Boolean.parseBoolean(a[1])) store.delete(a[0], ids).toCompletableFuture().get(10, TimeUnit.SECONDS);
                    return map("messages", messages, "acknowledged", Boolean.parseBoolean(a[1]),
                            "ttl", conn.sync().pttl(userKey(a[0], false)), "lockTtl", conn.sync().pttl(userKey(a[0], true)));
                } finally { store.shutdown(); }
            }
            case "O_STATE": {
                String key = userKey(a[0], false);
                return map("ttl", conn.sync().pttl(key), "lockTtl", conn.sync().pttl(userKey(a[0], true)),
                        "entries", entries(key));
            }
            case "O_UNLOCK":
                await(() -> conn.sync().exists(userKey(a[0], true)) == 0, "crashed drain lock expiry");
                return map("lockExpired", true);
            case "O_EXPIRED":
                await(() -> conn.sync().exists(userKey(a[0], false)) == 0, "offline queue expiry");
                return map("expired", true);
            case "O_TTL_BELOW":
                await(() -> conn.sync().pttl(userKey(a[0], false)) <= Long.parseLong(a[1]), "TTL reaches threshold");
                if (conn.sync().pttl(userKey(a[0], false)) <= 0) throw new AssertionError("Queue expired too early");
                return map("ttl", conn.sync().pttl(userKey(a[0], false)));
            case "O_ENTRY_AGE":
                await(() -> System.currentTimeMillis() - Long.parseLong(a[0].split("-")[0]) >= Long.parseLong(a[1]),
                        "entry retention age");
                return map("age", System.currentTimeMillis() - Long.parseLong(a[0].split("-")[0]));
            case "SENTINEL":
                if (a.length > 0) conn.sync().set("state-compat:sentinel", a[0]);
                return map("value", conn.sync().get("state-compat:sentinel"));
            case "STOP": return map("stopped", true);
            default: throw new IllegalArgumentException("Unknown command " + op);
        }
    }

    private static final class LocalSender implements MessageSender {
        public int getSessionNums() { return 1; }
        public int getSessionNums(String uri) { return 1; }
        public Set<String> getRegisteredUri() { return Collections.singleton(URI); }
        public boolean isSessionAlive(String uri, String... sessionIds) { return true; }
        public void topicMessage(String uri, AbstractMessage message) { frameText(message); }
        public void sendMessage(String uri, AbstractMessage message, String... sessionIds) {
            emit(map("type", "OFFLINE_DELIVERY", "uri", uri, "sessions", Arrays.asList(sessionIds),
                    "offlineId", org.slf4j.MDC.get(ClusterMessageSender.MDC_OFFLINE_MESSAGE_ID),
                    "frame", frameText(message)));
        }
    }

    @Override public void close() {
        for (ClusterSubscription subscription : subscriptions) subscription.unsubscribe();
        sender.shutdown(); reliable.shutdown(); users.shutdown(); sessions.shutdown(); pubsub.shutdown();
        conn.close(); client.shutdown();
    }

    public static void main(String[] args) throws Exception {
        String actual = version("netty-spring-websocket-cluster");
        if (!actual.equals(args[2]) || !version("netty-spring-websocket").equals(args[2]))
            throw new AssertionError("Incorrect release classpath: " + actual);
        try (RedisStateCompatibilityHarness harness = new RedisStateCompatibilityHarness(args[0], args[1]);
             BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            emit(map("type", "READY", "version", actual, "node", args[1], "java", System.getProperty("java.runtime.version"),
                    "redis", harness.conn.sync().info("server"), "hmac", "strict", "lockMs", LOCK_MS));
            String line;
            while ((line = input.readLine()) != null) {
                String[] fields = line.split("\t", -1);
                String[] values = new String[fields.length - 2];
                for (int i = 2; i < fields.length; i++) values[i - 2] = new String(Base64.getDecoder().decode(fields[i]), StandardCharsets.UTF_8);
                try {
                    Map<String, Object> result = harness.command(fields[1], values);
                    result.put("type", "RESULT"); result.put("requestId", fields[0]); result.put("status", "OK"); emit(result);
                } catch (Throwable error) {
                    error.printStackTrace(System.err);
                    emit(map("type", "RESULT", "requestId", fields[0], "status", "ERROR", "error", error.toString()));
                    throw error;
                }
                if ("STOP".equals(fields[1])) break;
            }
        }
    }
}
