/*
 * Isolated cross-release transport probe. Compile against EACH release's extracted
 * fat-jar dependencies; never put old and new libraries on the same classpath.
 * Run only on the private Docker network created by verify-transports.cjs.
 * The HTTP command endpoint is a test control plane, not a production service.
 */
import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.DefaultMessagePayloadCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.mesh.MeshBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.mesh.MeshFrames;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.mesh.RedisMeshNodeDirectory;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsClusterBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsJetStreamReliableBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.*;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import com.sun.net.httpserver.HttpServer;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.nats.client.*;
import io.nats.client.api.ConsumerInfo;
import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

public final class TransportCompatibilityProbe {
    private static final String SECRET = "migration-probe-only-shared-hmac-2026";
    private final String node;
    private final String version;
    private final SimpleTextEnvelopeCodec codec = new SimpleTextEnvelopeCodec();
    private final DefaultMessagePayloadCodec payloadCodec = new DefaultMessagePayloadCodec();
    private final HmacMessageAuthenticator auth = new HmacMessageAuthenticator(SECRET.getBytes(StandardCharsets.UTF_8), true);
    private final NatsClusterBroker core = new NatsClusterBroker(codec, auth);
    private final Connection connection;
    private final NatsJetStreamReliableBroker reliable;
    private final RedisClient redis;
    private final StatefulRedisConnection<String, String> redisConnection;
    private final MeshBroker mesh;
    private final Map<String, ClusterSubscription> subscriptions = new ConcurrentHashMap<>();
    private final List<String> events = new CopyOnWriteArrayList<>();
    private enum State { READY }

    TransportCompatibilityProbe(String node, String advertisedHost, String version) throws Exception {
        this.node = node;
        this.version = version;
        String resource = "/META-INF/maven/io.github.berrywang1996/netty-spring-websocket-cluster/pom.properties";
        Properties properties = new Properties();
        try (InputStream in = MeshBroker.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("Missing release identity");
            properties.load(in);
        }
        if (!version.equals(properties.getProperty("version"))) throw new IllegalStateException("Wrong runtime version: " + properties);
        connection = Nats.connect(Options.builder().server("nats://nats:4222")
                .connectionTimeout(Duration.ofSeconds(10)).maxReconnects(-1)
                .reconnectWait(Duration.ofMillis(100)).connectionListener(core::onConnectionEvent).build());
        core.attach(connection);
        reliable = new NatsJetStreamReliableBroker(connection, codec, auth, node, 10000L, 200L, 32, 1024);
        redis = RedisClient.create("redis://redis:6379/0");
        redisConnection = redis.connect();
        mesh = new MeshBroker(node, new RedisMeshNodeDirectory(redisConnection), codec, auth,
                new ClusterRuntimeStats(), "0.0.0.0", 19091, advertisedHost,
                8192, 3000L, 32768, 65536, 1000L);
        mesh.setReconnectBackoff(100L, 1000L);
        mesh.start();
        core.subscribeUnicast(node, e -> received("core", e));
        mesh.subscribeUnicast(node, e -> received("mesh", e));
        connection.flush(Duration.ofSeconds(5));
    }

    private void received(String transport, ClusterEnvelope e) {
        AbstractMessage decoded = payloadCodec.decode(e.getPayload());
        byte[] reencoded = payloadCodec.encode(decoded);
        WebSocketFrame frame = (WebSocketFrame) decoded.responseMsg();
        byte[] response = new byte[frame.content().readableBytes()];
        try { frame.content().getBytes(frame.content().readerIndex(), response); }
        finally { frame.release(); }
        events.add("{\"transport\":" + q(transport) + ",\"wire\":" + q(codec.encode(e))
                + ",\"payload\":" + q(b64(e.getPayload())) + ",\"reencoded\":" + q(b64(reencoded))
                + ",\"messageClass\":" + q(decoded.getClass().getSimpleName())
                + ",\"response\":" + q(b64(response)) + "}");
    }

    private ClusterEnvelope envelope(Map<String, String> p) {
        byte[] payload;
        String token = p.getOrDefault("token", "probe");
        switch (p.getOrDefault("type", "text")) {
            case "binary": payload = payloadCodec.encode(new BinaryMessage(Base64.getDecoder().decode(p.get("binary")))); break;
            case "json":
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("token", token); value.put("text", "雪/迁移"); value.put("createdAt", new Date(0));
                value.put("state", State.READY); value.put("optional", null);
                value.put("values", Arrays.asList(1, true, null));
                payload = payloadCodec.encode(new JsonMessage(value)); break;
            default: payload = payloadCodec.encode(new TextMessage(token + ":雪|\ntext"));
        }
        return new ClusterEnvelope(Integer.parseInt(p.getOrDefault("wireVersion", "2")), node,
                p.getOrDefault("uri", "/compat/transport"),
                ClusterEnvelope.MessageKind.valueOf(p.getOrDefault("kind", "BROADCAST")), payload,
                p.getOrDefault("session", "session-compat"),
                "00-0123456789abcdef0123456789abcdef-0123456789abcdef-01",
                Long.parseLong(p.getOrDefault("timestamp", String.valueOf(System.currentTimeMillis()))),
                p.getOrDefault("room", "room-雪"));
    }

    private ClusterBroker broker(String transport) {
        if (transport.equals("core")) return core;
        if (transport.equals("mesh")) return mesh;
        throw new IllegalArgumentException("Not an at-most-once broker: " + transport);
    }

    private String command(Map<String, String> p) throws Exception {
        String op = p.getOrDefault("op", "state"), transport = p.getOrDefault("transport", "core");
        String uri = p.getOrDefault("uri", "/compat/transport");
        switch (op) {
            case "state": return "{\"node\":" + q(node) + ",\"version\":" + q(version)
                    + ",\"java\":" + q(System.getProperty("java.runtime.version"))
                    + ",\"source\":" + q(MeshBroker.class.getProtectionDomain().getCodeSource().getLocation().toString())
                    + ",\"core\":" + q(core.state().name()) + ",\"reliable\":" + q(reliable.state().name())
                    + ",\"mesh\":" + q(mesh.state().name()) + ",\"peers\":" + mesh.knownPeerCount()
                    + ",\"outbound\":" + mesh.activeOutboundConnections() + ",\"rejected\":" + auth.getRejectedCount() + "}";
            case "events": return "[" + String.join(",", events) + "]";
            case "subscribe":
                subscriptions.computeIfAbsent(transport + uri, key -> transport.equals("reliable")
                        ? reliable.reliableSubscribe(uri, node, e -> received(transport, e))
                        : broker(transport).subscribe(uri, e -> received(transport, e)));
                connection.flush(Duration.ofSeconds(5));
                return "{\"subscribed\":true}";
            case "unsubscribe":
                ClusterSubscription sub = subscriptions.remove(transport + uri);
                if (sub != null) sub.unsubscribe();
                return "{\"unsubscribed\":true}";
            case "publish":
                ClusterEnvelope e = envelope(p);
                if (transport.equals("reliable")) reliable.reliablePublish(uri, e);
                else if (e.getKind() == ClusterEnvelope.MessageKind.UNICAST) broker(transport).unicast(p.get("target"), e);
                else broker(transport).publish(uri, e);
                if (!transport.equals("mesh")) connection.flush(Duration.ofSeconds(5));
                return "{\"wire\":" + q(codec.encode(e)) + ",\"payload\":" + q(b64(e.getPayload())) + "}";
            case "frame":
                String inner = codec.encode(envelope(p));
                String wrapped = auth.wrap(inner);
                if (p.containsKey("badHmac")) wrapped = wrapped.replaceFirst("H1:.", "H1:!");
                EmbeddedChannel channel = new EmbeddedChannel(MeshFrames.prepender());
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try {
                    channel.writeOutbound(MeshFrames.toPayload(wrapped));
                    ByteBuf b;
                    while ((b = channel.readOutbound()) != null) {
                        try { byte[] chunk = new byte[b.readableBytes()]; b.readBytes(chunk); bytes.write(chunk); }
                        finally { b.release(); }
                    }
                } finally { channel.finishAndReleaseAll(); }
                return "{\"frame\":" + q(b64(bytes.toByteArray())) + ",\"wire\":" + q(inner)
                        + ",\"wrapped\":" + q(wrapped) + "}";
            case "inspect":
                String stream = "netty-cluster-reliable-" + token(uri);
                ConsumerInfo info = connection.jetStreamManagement().getConsumerInfo(stream, "g_" + token(p.getOrDefault("consumer", node)));
                return "{\"stream\":" + q(stream) + ",\"consumer\":" + q(info.getName())
                        + ",\"messages\":" + connection.jetStreamManagement().getStreamInfo(stream).getStreamState().getMsgCount()
                        + ",\"pending\":" + info.getNumPending() + ",\"ackPending\":" + info.getNumAckPending()
                        + ",\"ackFloor\":" + info.getAckFloor().getStreamSequence()
                        + ",\"delivered\":" + info.getDelivered().getStreamSequence() + "}";
            default: throw new IllegalArgumentException("Unknown operation: " + op);
        }
    }

    private void close() {
        try { reliable.shutdown(); } catch (Exception ignored) {}
        try { mesh.shutdown(); } catch (Exception ignored) {}
        try { core.shutdown(); } catch (Exception ignored) {}
        try { connection.close(); } catch (Exception ignored) {}
        try { redisConnection.close(); } catch (Exception ignored) {}
        try { redis.shutdown(); } catch (Exception ignored) {}
    }
    private static String b64(byte[] b) { return Base64.getEncoder().encodeToString(b); }
    private static String token(String s) { return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }
    private static String q(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            if (c == '\\' || c == '"') b.append('\\').append(c);
            else if (c < 32) b.append(String.format("\\u%04x", (int)c));
            else b.append(c);
        }
        return b.append('"').toString();
    }
    private static Map<String, String> form(String body) {
        Map<String, String> result = new HashMap<>();
        for (String pair : body.split("&")) {
            String[] p = pair.split("=", 2);
            result.put(URLDecoder.decode(p[0], StandardCharsets.UTF_8), p.length < 2 ? "" : URLDecoder.decode(p[1], StandardCharsets.UTF_8));
        }
        return result;
    }
    public static void main(String[] args) throws Exception {
        TransportCompatibilityProbe probe = new TransportCompatibilityProbe(args[0], args[1], args[2]);
        Runtime.getRuntime().addShutdownHook(new Thread(probe::close, "probe-cleanup"));
        HttpServer server = HttpServer.create(new InetSocketAddress("0.0.0.0", 8080), 16);
        server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/command", exchange -> {
            int status = 200;
            String result;
            try { result = probe.command(form(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))); }
            catch (Throwable failure) { status = 500; result = "{\"error\":" + q(failure.toString()) + "}"; failure.printStackTrace(); }
            byte[] bytes = result.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) { out.write(bytes); }
        });
        server.start();
        System.out.println("TRANSPORT_PROBE_READY " + args[0] + " " + args[2]);
    }
}
