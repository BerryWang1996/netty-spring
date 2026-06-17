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

import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Resolves the host a mesh node advertises to its peers (1.10.0-RC4a, M4). Auto-detecting and silently trusting a
 * local IP makes the mesh silently unreachable under multi-NIC / containers / NAT / k8s (the Kafka
 * {@code advertised.listeners} footgun), so this <b>fails fast</b> when it can only find a loopback address — the
 * deployment must then set {@code server.netty.websocket.cluster.mesh.advertised-host} explicitly.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public final class MeshAddressResolver {

    private MeshAddressResolver() {
    }

    /** Resolves the advertised host: the explicit value verbatim, else an auto-detected non-loopback site-local IPv4. */
    public static String resolve(String advertisedHostOrNull) {
        if (advertisedHostOrNull != null && !advertisedHostOrNull.trim().isEmpty()) {
            return advertisedHostOrNull.trim();
        }
        return resolve(advertisedHostOrNull, gatherCandidates());
    }

    /** Testable core: pick the advertised host from the given candidate addresses (fail fast if only loopback). */
    static String resolve(String advertisedHostOrNull, List<InetAddress> candidates) {
        if (advertisedHostOrNull != null && !advertisedHostOrNull.trim().isEmpty()) {
            return advertisedHostOrNull.trim();
        }
        for (InetAddress a : candidates) {
            if (!a.isLoopbackAddress() && a.isSiteLocalAddress() && a instanceof java.net.Inet4Address) {
                return a.getHostAddress();
            }
        }
        // Fall back to any non-loopback (e.g. public IPv4) before giving up.
        for (InetAddress a : candidates) {
            if (!a.isLoopbackAddress() && a instanceof java.net.Inet4Address) {
                return a.getHostAddress();
            }
        }
        throw new IllegalStateException("Mesh could not auto-detect a non-loopback advertised host — set "
                + "server.netty.websocket.cluster.mesh.advertised-host explicitly (required for containers/NAT/k8s).");
    }

    private static List<InetAddress> gatherCandidates() {
        List<InetAddress> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                out.addAll(Collections.list(ni.getInetAddresses()));
            }
        } catch (SocketException e) {
            // leave empty → resolve() will fail fast with the clear message
        }
        return out;
    }
}
