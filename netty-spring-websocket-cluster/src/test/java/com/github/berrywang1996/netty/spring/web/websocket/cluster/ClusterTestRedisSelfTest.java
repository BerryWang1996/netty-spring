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

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ClusterTestRedisSelfTest {

    @Test
    void resolvesAPingableRedis() {
        Assumptions.assumeTrue(ClusterTestRedis.available(), "no Redis and no Docker — nothing to resolve");
        String uri = ClusterTestRedis.uri();
        assertTrue(uri.startsWith("redis://"), "resolved uri must be a redis URI: " + uri);
        RedisClient c = ClusterTestRedis.newClient();
        try (StatefulRedisConnection<String, String> conn = c.connect()) {
            assertEquals("PONG", conn.sync().ping().toUpperCase());
        } finally {
            c.shutdown();
        }
    }
}
