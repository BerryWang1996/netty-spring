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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** RC4d: the new mesh observability members on {@link ClusterRuntimeStats} (counters + fan-out sampler). */
class ClusterRuntimeStatsMeshTest {

    @Test
    void newMeshCountersIncrement() {
        ClusterRuntimeStats s = new ClusterRuntimeStats();
        s.incMeshFramesSent();
        s.incMeshFramesSent();
        s.incMeshIdleReaps();
        s.incMeshReconnectBackoffSkips();
        s.incMeshReconnectBackoffSkips();
        s.incMeshReconnectBackoffSkips();
        assertEquals(2, s.getMeshFramesSent());
        assertEquals(1, s.getMeshIdleReaps());
        assertEquals(3, s.getMeshReconnectBackoffSkips());
    }

    @Test
    void fanoutSamplerAveragesAndRemembersLast() {
        ClusterRuntimeStats s = new ClusterRuntimeStats();
        assertEquals(0.0, s.getMeshFanoutTargetsAvg());   // no samples yet
        s.recordMeshFanoutTargets(4);
        s.recordMeshFanoutTargets(2);
        assertEquals(3.0, s.getMeshFanoutTargetsAvg());   // (4 + 2) / 2
        assertEquals(2, s.getMeshFanoutTargetsLast());
    }
}
