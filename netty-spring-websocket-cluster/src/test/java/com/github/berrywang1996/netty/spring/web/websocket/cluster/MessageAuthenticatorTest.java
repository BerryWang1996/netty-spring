package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MessageAuthenticatorTest {

    private static final String SECRET = "this-is-a-32+char-cluster-secret!!";
    private static final String PAYLOAD = "T:hello|/ws/chat|node-A|123"; // a codec-like string with ':' and '|'

    @Test
    void hmacRoundTripsAndIsTagged() {
        HmacMessageAuthenticator a = new HmacMessageAuthenticator(SECRET.getBytes(), true);
        String wire = a.wrap(PAYLOAD);
        assertTrue(wire.startsWith("H1:"), "wrapped wire must carry the H1 tag");
        assertNotEquals(PAYLOAD, wire);
        assertEquals(PAYLOAD, a.unwrap(wire), "unwrap of a valid tag returns the original payload");
        assertEquals(0, a.getRejectedCount());
    }

    @Test
    void tamperedPayloadIsRejected() {
        HmacMessageAuthenticator a = new HmacMessageAuthenticator(SECRET.getBytes(), true);
        String wire = a.wrap(PAYLOAD);
        String tampered = wire + "X"; // mutate the payload after the tag
        assertNull(a.unwrap(tampered), "a tampered payload must be rejected");
        assertEquals(1, a.getRejectedCount());
    }

    @Test
    void wrongSecretRejects() {
        HmacMessageAuthenticator signer = new HmacMessageAuthenticator(SECRET.getBytes(), true);
        HmacMessageAuthenticator verifier = new HmacMessageAuthenticator("a-different-secret-key-32-chars!!".getBytes(), true);
        assertNull(verifier.unwrap(signer.wrap(PAYLOAD)), "a different secret must reject");
        assertEquals(1, verifier.getRejectedCount());
    }

    @Test
    void missingTagRejectedWhenStrictAcceptedWhenPermissive() {
        HmacMessageAuthenticator strict = new HmacMessageAuthenticator(SECRET.getBytes(), true); // requireSigned=true
        assertNull(strict.unwrap(PAYLOAD), "strict rejects an untagged (unsigned) message");
        assertEquals(1, strict.getRejectedCount());

        HmacMessageAuthenticator permissive = new HmacMessageAuthenticator(SECRET.getBytes(), false); // requireSigned=false
        assertEquals(PAYLOAD, permissive.unwrap(PAYLOAD), "permissive accepts an unsigned message");
        assertEquals(0, permissive.getRejectedCount());
        assertEquals(PAYLOAD, permissive.unwrap(permissive.wrap(PAYLOAD)));
    }

    @Test
    void noOpStripsTagWithoutVerifyingAndPassesPlain() {
        NoOpMessageAuthenticator noop = new NoOpMessageAuthenticator();
        assertEquals(PAYLOAD, noop.wrap(PAYLOAD), "no-op never signs");
        String signed = new HmacMessageAuthenticator(SECRET.getBytes(), true).wrap(PAYLOAD);
        assertEquals(PAYLOAD, noop.unwrap(signed));
        assertEquals(PAYLOAD, noop.unwrap(PAYLOAD), "plain passes through");
    }
}
