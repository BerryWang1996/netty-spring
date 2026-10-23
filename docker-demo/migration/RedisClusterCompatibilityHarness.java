import com.github.berrywang1996.netty.spring.boot.configure.NettyWebSocketClusterConfigure;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.DefaultMessagePayloadCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.*;
import com.github.berrywang1996.netty.spring.web.websocket.context.AbstractMessage;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.SlotHash;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real three-master Redis Cluster probe, using each release's actual Spring auto-configuration.
 * The unsupported feature flags are intentionally true: missing beans and public API rejection are tested.
 * No embedded HTTP/WebSocket server is created. Redis operations and Pub/Sub are real, never mocks.
 */
public final class RedisClusterCompatibilityHarness implements AutoCloseable {
    private static final String PREFIX = "server.netty.websocket.cluster.";
    private static final DefaultMessagePayloadCodec PAYLOAD = new DefaultMessagePayloadCodec();
    private final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
    private final RedisClusterClient client;
    private final StatefulRedisClusterConnection<String, String> connection;
    private final SessionRegistry registry;
    private final ClusterNodeHeartbeat heartbeat;
    private final ClusterReaper reaper;
    private final ClusterBroker broker;
    private final ClusterMessageSender sender;
    private final String node;
    private final List<ClusterSubscription> subscriptions = new ArrayList<>();

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties
    public static class BindingConfiguration {}

    @SuppressWarnings("unchecked")
    RedisClusterCompatibilityHarness(String nodes, String node) {
        this.node = node;
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put(PREFIX + "enable", "true");
        properties.put(PREFIX + "node-id", node);
        properties.put(PREFIX + "redis.cluster-nodes", nodes);
        properties.put(PREFIX + "drain-timeout-seconds", "0");
        properties.put(PREFIX + "reconciliation-interval-seconds", "60");
        properties.put(PREFIX + "auth.enable", "true");
        properties.put(PREFIX + "auth.permissive", "false");
        properties.put(PREFIX + "auth.secret", "migration-state-fixture-key-32bytes");
        for (String feature : List.of("room", "reliable", "offline", "presence"))
            properties.put(PREFIX + feature + ".enable", "true");
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("migration-probe", properties));
        context.registerBean("messageSender", MessageSender.class, LocalBoundary::new);
        context.register(BindingConfiguration.class, NettyWebSocketClusterConfigure.class);
        context.refresh();
        client = context.getBean(RedisClusterClient.class);
        connection = (StatefulRedisClusterConnection<String, String>) context.getBean("nettyClusterRedisClusterConnection");
        registry = context.getBean(SessionRegistry.class);
        heartbeat = context.getBean(ClusterNodeHeartbeat.class);
        reaper = context.getBean(ClusterReaper.class);
        broker = context.getBean(ClusterBroker.class);
        sender = context.getBean(ClusterMessageSender.class);
        if (!registry.getClass().getSimpleName().equals("RedisClusterModeSessionRegistry") ||
                !broker.getClass().getSimpleName().equals("RedisClusterModePubSubBroker"))
            throw new AssertionError("Incorrect Redis mode auto-configuration");
    }

    private static Map<String, Object> map(Object... values) { return RedisStateCompatibilityHarness.map(values); }
    private static void emit(Map<String, Object> value) { RedisStateCompatibilityHarness.emit(value); }
    private Map<String, Object> envelope(ClusterEnvelope e) {
        return map("wireVersion", e.getVersion(), "origin", e.getOriginNodeId(), "uri", e.getUri(),
                "kind", e.getKind().name(), "target", e.getTargetSessionId(),
                "payload", new String(e.getPayload(), StandardCharsets.UTF_8),
                "frame", RedisStateCompatibilityHarness.frameText(PAYLOAD.decode(e.getPayload())));
    }
    private ClusterEnvelope outgoing(String uri, String label, int seq, boolean unicast) {
        return new ClusterEnvelope(node, uri, unicast ? ClusterEnvelope.MessageKind.UNICAST : ClusterEnvelope.MessageKind.BROADCAST,
                PAYLOAD.encode(RedisStateCompatibilityHarness.fixture(label, seq)), unicast ? "probe-session" : null,
                null, System.currentTimeMillis());
    }
    private static String sessionKey(String uri, String id) {
        return "netty:session:" + Base64.getUrlEncoder().withoutPadding().encodeToString(uri.getBytes(StandardCharsets.UTF_8)) + ":" + id;
    }
    private Map<String, Object> topology() {
        return map("nodes", connection.sync().clusterNodes(), "info", connection.sync().clusterInfo(),
                "partitions", client.getPartitions().size(), "registry", registry.getClass().getName(),
                "broker", broker.getClass().getName());
    }
    private Map<String, Object> gates() {
        Map<String, Object> result = map("flagsRequested", List.of("room", "reliable", "offline", "presence"));
        for (Class<?> type : List.of(ClusterRoomRegistry.class, ReliableBroker.class, OfflineQueueStore.class,
                UserRegistry.class, UserIdResolver.class, PresenceRegistry.class)) {
            int count = context.getBeansOfType(type).size();
            if (count != 0) throw new AssertionError("Unsupported Cluster feature bean: " + type);
            result.put(type.getSimpleName(), count);
        }
        if (sender.isOfflineEnabled() || sender.isPresenceEnabled()) throw new AssertionError("Unsupported feature enabled");
        result.put("roomRejection", rejected(() -> sender.joinRoom("/cluster", "room", "session")));
        result.put("reliableRejection", rejected(() -> sender.reliableBroadcast("/cluster", RedisStateCompatibilityHarness.fixture("disabled", 1))));
        result.put("offlineRejection", rejected(() -> sender.sendToUser("user", RedisStateCompatibilityHarness.fixture("disabled", 1))));
        result.put("presenceRejection", rejected(() -> sender.setPresenceForUser("user", PresenceStatus.ONLINE)));
        return result;
    }
    private static String rejected(Runnable action) {
        try { action.run(); throw new AssertionError("Unsupported operation did not reject"); }
        catch (IllegalStateException expected) { return expected.getMessage(); }
    }

    private Map<String, Object> command(String op, String[] a) throws Exception {
        switch (op) {
            case "TOPOLOGY": return topology();
            case "GATES": return gates();
            case "REGISTER": {
                List<Map<String, Object>> entries = new ArrayList<>();
                for (int i = 1; i <= Integer.parseInt(a[2]); i++) {
                    String id = a[1] + "-" + i;
                    registry.register(a[0], id, a[1], Map.of("sample", "雪", "sequence", String.valueOf(i)))
                            .toCompletableFuture().get(10, TimeUnit.SECONDS);
                    String key = sessionKey(a[0], id);
                    int slot = SlotHash.getSlot(key);
                    entries.add(map("id", id, "key", key, "slot", slot,
                            "master", client.getPartitions().getPartitionBySlot(slot).getNodeId(),
                            "data", connection.sync().hgetall(key)));
                }
                return map("entries", entries);
            }
            case "LOOKUP": {
                Map<String, Object> nodes = new LinkedHashMap<>();
                for (int i = 1; i <= Integer.parseInt(a[2]); i++) {
                    String id = a[1] + "-" + i;
                    nodes.put(id, registry.lookupNode(a[0], id).toCompletableFuture().get(10, TimeUnit.SECONDS));
                }
                return map("nodes", nodes, "ids", new TreeSet<>(registry.clusterSessionIds(a[0]).toCompletableFuture().get(10, TimeUnit.SECONDS)));
            }
            case "DEREGISTER":
                registry.deregister(a[0], a[1]).toCompletableFuture().get(10, TimeUnit.SECONDS);
                return map("removed", a[1]);
            case "REMOVE_NODE":
                registry.removeAllForNode(a[0]).toCompletableFuture().get(10, TimeUnit.SECONDS);
                return map("removed", a[0]);
            case "HB_REGISTER": heartbeat.register(a[0], Long.parseLong(a[1])); return map("registered", a[0]);
            case "HB_RENEW": heartbeat.renewHeartbeat(a[0], Long.parseLong(a[1])); return map("renewed", a[0]);
            case "HB_STATE": return map("ttl", connection.sync().pttl("netty:cluster:heartbeat:" + a[0]),
                    "expired", heartbeat.findExpiredNodes(100).contains(a[0]));
            case "HB_EXPIRED":
                RedisStateCompatibilityHarness.await(() -> heartbeat.findExpiredNodes(100).contains(a[0]), "heartbeat expiry");
                return map("expired", true, "ttl", connection.sync().pttl("netty:cluster:heartbeat:" + a[0]));
            case "HB_REMOVE": heartbeat.deregister(a[0]); return map("removed", a[0]);
            case "CLAIM": return map("claimed", reaper.tryClaim(a[0], node, Long.parseLong(a[1])));
            case "CLAIM_EXPIRED":
                RedisStateCompatibilityHarness.await(() -> connection.sync().exists("netty:cluster:reaping:" + a[0]) == 0, "reaper lease expiry");
                return map("expired", true);
            case "SUB": {
                String uri = a[0], target = a[1]; boolean unicast = !target.isEmpty();
                String probe = "subscription-ready-" + UUID.randomUUID();
                AtomicBoolean ready = new AtomicBoolean();
                ClusterMessageListener listener = e -> {
                    String payload = new String(e.getPayload(), StandardCharsets.UTF_8);
                    if (payload.contains("subscription-ready-")) {
                        if (payload.contains(probe)) ready.set(true);
                        return;
                    }
                    Map<String, Object> event = envelope(e); event.put("type", "CLUSTER_MESSAGE"); emit(event);
                };
                subscriptions.add(unicast ? broker.subscribeUnicast(target, listener) : broker.subscribe(uri, listener));
                // Wait for a real round-trip through the asynchronously established subscription.
                RedisStateCompatibilityHarness.await(() -> {
                    if (!ready.get()) {
                        ClusterEnvelope probeEnvelope = outgoing(uri, probe, 0, unicast);
                        if (unicast) broker.unicast(target, probeEnvelope); else broker.publish(uri, probeEnvelope);
                    }
                    return ready.get();
                }, "real subscription round-trip");
                return map("ready", true, "uri", uri, "target", target);
            }
            case "PUBLISH": {
                for (int i = 1; i <= Integer.parseInt(a[3]); i++) {
                    ClusterEnvelope e = outgoing(a[0], a[2], i, !a[1].isEmpty());
                    if (a[1].isEmpty()) broker.publish(a[0], e); else broker.unicast(a[1], e);
                }
                return map("published", Integer.parseInt(a[3]));
            }
            case "STOP": return map("stopped", true);
            default: throw new IllegalArgumentException("Unknown Cluster command " + op);
        }
    }

    private static final class LocalBoundary implements MessageSender {
        public int getSessionNums() { return 0; }
        public int getSessionNums(String uri) { return 0; }
        public Set<String> getRegisteredUri() { return Collections.emptySet(); }
        public boolean isSessionAlive(String uri, String... ids) { return false; }
        public void topicMessage(String uri, AbstractMessage message) { throw new AssertionError("Unexpected local delivery"); }
        public void sendMessage(String uri, AbstractMessage message, String... ids) { throw new AssertionError("Unexpected local delivery"); }
    }
    @Override public void close() {
        for (ClusterSubscription subscription : subscriptions) subscription.unsubscribe();
        context.close();
    }
    public static void main(String[] args) throws Exception {
        String version = RedisStateCompatibilityHarness.version("netty-spring-websocket-cluster");
        if (!version.equals(args[2]) || !RedisStateCompatibilityHarness.version("netty-spring-websocket").equals(args[2]))
            throw new AssertionError("Incorrect release classpath");
        try (RedisClusterCompatibilityHarness harness = new RedisClusterCompatibilityHarness(args[0], args[1]);
             BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            Map<String, Object> ready = harness.topology();
            ready.putAll(map("type", "READY", "version", version, "node", args[1],
                    "java", System.getProperty("java.runtime.version"), "hmac", "strict", "gates", harness.gates()));
            emit(ready);
            String line;
            while ((line = input.readLine()) != null) {
                String[] fields = line.split("\t", -1), values = new String[fields.length - 2];
                for (int i = 2; i < fields.length; i++) values[i - 2] = new String(Base64.getDecoder().decode(fields[i]), StandardCharsets.UTF_8);
                try {
                    Map<String, Object> result = harness.command(fields[1], values);
                    result.putAll(map("type", "RESULT", "requestId", fields[0], "status", "OK")); emit(result);
                } catch (Throwable error) {
                    error.printStackTrace(System.err);
                    emit(map("type", "RESULT", "requestId", fields[0], "status", "ERROR", "error", error.toString())); throw error;
                }
                if ("STOP".equals(fields[1])) break;
            }
        }
    }
}
