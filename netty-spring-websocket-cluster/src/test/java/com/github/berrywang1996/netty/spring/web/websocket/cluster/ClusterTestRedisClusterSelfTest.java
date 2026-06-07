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

import io.lettuce.core.cluster.RedisClusterClient;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTestRedisClusterSelfTest {

    @Test
    void connectsToSingleNodeCluster() {
        Assumptions.assumeTrue(ClusterTestRedisCluster.available(), "no Redis Cluster and no Docker");
        RedisClusterClient client = ClusterTestRedisCluster.newClient();
        try (StatefulRedisClusterConnection<String, String> conn = client.connect()) {
            assertEquals("PONG", conn.sync().ping().toUpperCase());
            conn.sync().set("netty:clustertest:probe", "v");
            assertEquals("v", conn.sync().get("netty:clustertest:probe"));
        } finally {
            client.shutdown();
        }
    }
}
