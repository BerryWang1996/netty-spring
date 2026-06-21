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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.mesh;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies the M4 advertised-host policy: an explicit host is used verbatim; otherwise a non-loopback site-local IPv4
 * is auto-detected; and when only loopback is available the resolver FAILS FAST (the deployment must set the host).
 */
class MeshAddressResolverTest {

    @Test
    void explicitHost_usedVerbatim() throws Exception {
        assertEquals("10.1.2.3",
                MeshAddressResolver.resolve("10.1.2.3", Collections.singletonList(InetAddress.getByName("127.0.0.1"))));
        // explicit wins even with no candidates
        assertEquals("mesh-host.internal", MeshAddressResolver.resolve("  mesh-host.internal  ", Collections.emptyList()));
    }

    @Test
    void autoDetect_picksSiteLocalIpv4() throws Exception {
        List<InetAddress> candidates = java.util.Arrays.asList(
                InetAddress.getByName("127.0.0.1"),     // loopback — skipped
                InetAddress.getByName("192.168.1.50"));  // site-local — chosen
        assertEquals("192.168.1.50", MeshAddressResolver.resolve(null, candidates));
    }

    @Test
    void onlyLoopback_failsFast() throws Exception {
        List<InetAddress> candidates = Collections.singletonList(InetAddress.getByName("127.0.0.1"));
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> MeshAddressResolver.resolve(null, candidates));
        assertEquals(true, ex.getMessage().contains("advertised-host"));
    }

    /** The public single-arg entry point returns an explicit host verbatim (trimmed) WITHOUT touching NIC detection —
     *  the common production path (containers/NAT/k8s set the host explicitly). */
    @Test
    void publicResolve_explicitHost_usedVerbatim() {
        assertEquals("203.0.113.7", MeshAddressResolver.resolve("203.0.113.7"));
        assertEquals("mesh.internal", MeshAddressResolver.resolve("  mesh.internal  "));
    }
}
