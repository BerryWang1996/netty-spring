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

package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.NodeState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

/**
 * Spring Boot Actuator {@link org.springframework.boot.actuate.health.HealthIndicator}
 * for WebSocket cluster mode.
 *
 * <p>Answers the central operational question for a clustered deployment — "is this node
 * healthy in the cluster?" — and exposes the cluster runtime counters at
 * {@code GET /actuator/health} under {@code nettyCluster}.
 *
 * <p>Status mapping (chosen so a transient Redis blip never marks a node that is still
 * serving local traffic as {@code DOWN}, avoiding accidental orchestrator pod kills):
 * <ul>
 *   <li>{@code ACTIVE} + broker {@code ACTIVE} → {@code UP}</li>
 *   <li>{@code DEGRADED} / {@code RESYNC} → {@code UP} with {@code clusterState} detail
 *       (the node still does local fan-out; cross-node traffic is paused/recovering)</li>
 *   <li>{@code DRAINING} → {@code OUT_OF_SERVICE} (graceful shutdown in progress)</li>
 *   <li>{@code LEFT} → {@code DOWN}</li>
 * </ul>
 *
 * @author berrywang1996
 * @since V1.8.0
 */
public class ClusterHealthIndicator extends AbstractHealthIndicator {

    private final ClusterNodeManager nodeManager;
    private final ClusterBroker broker;
    private final ClusterMessageSender sender;

    public ClusterHealthIndicator(ClusterNodeManager nodeManager, ClusterBroker broker,
                                  ClusterMessageSender sender) {
        super("WebSocket cluster health check failed");
        this.nodeManager = nodeManager;
        this.broker = broker;
        this.sender = sender;
    }

    @Override
    protected void doHealthCheck(Health.Builder builder) {
        NodeState nodeState = nodeManager.getState();
        BrokerState brokerState = broker.state();
        ClusterRuntimeStats stats = sender.getClusterRuntimeStats();

        switch (nodeState) {
            case ACTIVE:
                builder.status(brokerState == BrokerState.ACTIVE ? Status.UP : Status.OUT_OF_SERVICE);
                break;
            case DEGRADED:
            case RESYNC:
                // Still serving local traffic — UP, but surface the degraded cluster state.
                builder.status(Status.UP);
                break;
            case DRAINING:
                builder.status(Status.OUT_OF_SERVICE);
                break;
            case JOINING:
                builder.status(Status.UP);
                break;
            case LEFT:
            default:
                builder.status(Status.DOWN);
                break;
        }

        builder.withDetail("nodeId", nodeManager.getNodeId())
                .withDetail("nodeState", nodeState.name())
                .withDetail("brokerState", brokerState.name())
                .withDetail("broadcastPublished", stats.getBroadcastPublished())
                .withDetail("crossNodeBroadcastReceived", stats.getCrossNodeBroadcastReceived())
                .withDetail("unicastSent", stats.getUnicastSent())
                .withDetail("selfDeliveryDropped", stats.getSelfDeliveryDropped())
                .withDetail("publishFailures", stats.getPublishFailures())
                .withDetail("cacheHitRatio", String.format("%.3f", stats.getCacheHitRatio()));
    }
}
