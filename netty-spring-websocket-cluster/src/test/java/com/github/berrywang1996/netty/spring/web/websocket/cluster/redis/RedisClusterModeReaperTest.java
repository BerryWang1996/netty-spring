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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RedisClusterModeReaper} — the Redis-Cluster twin of {@code RedisClusterReaper}.
 * Verifies the single-key {@code SET key val NX PX} claim semantics over a cluster connection.
 */
class RedisClusterModeReaperTest {

    @Test
    @SuppressWarnings("unchecked")
    void tryClaim_setReturnsOk_winsAndIssuesSetNxPx() {
        StatefulRedisClusterConnection<String, String> conn = mock(StatefulRedisClusterConnection.class);
        RedisAdvancedClusterCommands<String, String> sync = mock(RedisAdvancedClusterCommands.class);
        when(conn.sync()).thenReturn(sync);
        when(sync.set(anyString(), anyString(), any(SetArgs.class))).thenReturn("OK");

        RedisClusterModeReaper reaper = new RedisClusterModeReaper(conn);

        assertTrue(reaper.tryClaim("dead", "me", 1000));
        verify(sync).set(eq("netty:cluster:reaping:dead"), eq("me"), any(SetArgs.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void tryClaim_setReturnsNull_loses() {
        StatefulRedisClusterConnection<String, String> conn = mock(StatefulRedisClusterConnection.class);
        RedisAdvancedClusterCommands<String, String> sync = mock(RedisAdvancedClusterCommands.class);
        when(conn.sync()).thenReturn(sync);
        when(sync.set(anyString(), anyString(), any(SetArgs.class))).thenReturn(null);

        RedisClusterModeReaper reaper = new RedisClusterModeReaper(conn);

        assertFalse(reaper.tryClaim("dead", "me", 1000));
    }
}
