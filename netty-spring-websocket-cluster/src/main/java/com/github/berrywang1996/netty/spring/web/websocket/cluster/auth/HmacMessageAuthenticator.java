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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.auth;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicLong;

/**
 * HMAC-SHA256 authenticator. Wraps the codec-encoded envelope as {@code H1:{base64url(tag)}:{payload}};
 * verifies the tag on receive with a constant-time compare. {@code requireSigned} (= !permissive)
 * controls whether an untagged inbound message is rejected (strict) or accepted (permissive rollout).
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class HmacMessageAuthenticator implements MessageAuthenticator {

    private static final String PREFIX = "H1:";
    private static final String ALGO = "HmacSHA256";

    private final byte[] secret;
    private final boolean requireSigned;
    private final AtomicLong rejectedCount = new AtomicLong();
    /** Mac is not thread-safe — one per thread, keyed once. */
    private final ThreadLocal<Mac> macTl;

    public HmacMessageAuthenticator(byte[] secret, boolean requireSigned) {
        this.secret = secret.clone();
        this.requireSigned = requireSigned;
        this.macTl = ThreadLocal.withInitial(() -> {
            try {
                Mac m = Mac.getInstance(ALGO);
                m.init(new SecretKeySpec(this.secret, ALGO));
                return m;
            } catch (Exception e) {
                throw new IllegalStateException("Failed to init " + ALGO, e);
            }
        });
    }

    /** Number of inbound messages rejected for a missing/invalid tag (observable for tests/health). */
    public long getRejectedCount() { return rejectedCount.get(); }

    @Override
    public String wrap(String encoded) {
        return PREFIX + tag(encoded) + ":" + encoded;
    }

    @Override
    public String unwrap(String wireData) {
        if (wireData == null) { rejectedCount.incrementAndGet(); return null; }
        if (wireData.startsWith(PREFIX)) {
            int sep = wireData.indexOf(':', PREFIX.length());
            if (sep < 0) { rejectedCount.incrementAndGet(); return null; } // malformed
            String tag = wireData.substring(PREFIX.length(), sep);
            String payload = wireData.substring(sep + 1);
            String expected = tag(payload);
            if (MessageDigest.isEqual(tag.getBytes(StandardCharsets.UTF_8),
                    expected.getBytes(StandardCharsets.UTF_8))) {
                return payload;
            }
            rejectedCount.incrementAndGet();
            log.debug("HMAC verification failed — rejecting inbound message");
            return null; // bad MAC
        }
        // untagged (unsigned)
        if (requireSigned) { rejectedCount.incrementAndGet(); return null; }
        return wireData; // permissive: accept unsigned
    }

    private String tag(String payload) {
        Mac m = macTl.get();
        m.reset();
        byte[] raw = m.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
