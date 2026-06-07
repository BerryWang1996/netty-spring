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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import io.lettuce.core.SetArgs;
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RedisClusterModeNodeHeartbeatTest {

    @Test
    @SuppressWarnings("unchecked")
    void registerSetsTtlKeyAndNodesHash() {
        StatefulRedisClusterConnection<String, String> conn = mock(StatefulRedisClusterConnection.class);
        RedisAdvancedClusterCommands<String, String> sync = mock(RedisAdvancedClusterCommands.class);
        when(conn.sync()).thenReturn(sync);

        new RedisClusterModeNodeHeartbeat(conn).register("node-A", 10000);

        verify(sync).set(eq("netty:cluster:heartbeat:node-A"), anyString(), any(SetArgs.class));
        verify(sync).hset(eq("netty:cluster:nodes"), eq("node-A"), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void deregisterDeletesKeyAndHashField() {
        StatefulRedisClusterConnection<String, String> conn = mock(StatefulRedisClusterConnection.class);
        RedisAdvancedClusterCommands<String, String> sync = mock(RedisAdvancedClusterCommands.class);
        when(conn.sync()).thenReturn(sync);

        new RedisClusterModeNodeHeartbeat(conn).deregister("node-A");

        verify(sync).del("netty:cluster:heartbeat:node-A");
        verify(sync).hdel("netty:cluster:nodes", "node-A");
    }
}
