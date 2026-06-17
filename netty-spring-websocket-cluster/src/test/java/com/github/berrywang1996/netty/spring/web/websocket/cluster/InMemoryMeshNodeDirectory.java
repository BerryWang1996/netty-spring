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

package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MeshNodeDirectory;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** In-process {@link MeshNodeDirectory} stub (test scope) — a nodeId → "host:port" map, no TTL. */
public class InMemoryMeshNodeDirectory implements MeshNodeDirectory {

    private final Map<String, String> addrs = new ConcurrentHashMap<>();

    @Override
    public CompletionStage<Void> advertise(String nodeId, String host, int port, long ttlMs) {
        addrs.put(nodeId, host + ":" + port);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletionStage<Map<String, String>> peers(String selfNodeId) {
        Map<String, String> out = new HashMap<>(addrs);
        out.remove(selfNodeId);
        return CompletableFuture.completedFuture(out);
    }

    @Override
    public CompletionStage<Void> remove(String nodeId) {
        addrs.remove(nodeId);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void shutdown() {
        addrs.clear();
    }
}
