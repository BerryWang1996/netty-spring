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
