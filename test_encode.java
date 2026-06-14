import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;

public class test_encode {
    public static void main(String[] args) {
        SimpleTextEnvelopeCodec codec = new SimpleTextEnvelopeCodec();
        
        // Simulate a BROADCAST with no room (typical no-room scenario)
        ClusterEnvelope env = new ClusterEnvelope(
            "node-A", "/ws/chat", ClusterEnvelope.MessageKind.BROADCAST,
            "payload".getBytes(), null, null, 1000L
        );
        
        String wire = codec.encode(env);
        System.out.println("Wire format (no room): " + wire);
        System.out.println("Field count (pipe count + 1): " + (wire.split("\|", -1).length));
        System.out.println("Version: " + env.getVersion());
        System.out.println("Room: " + env.getRoom());
    }
}
