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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;

/**
 * App callback fired once per user <b>aggregate</b> presence transition (1.10.0-RC3).
 *
 * <p>Supply a {@code @Bean} implementing this to react to presence changes. It fires once per transition
 * ({@code old != new}): <b>local-first</b> on the node where the change originated, and on receive on every other RC3
 * node (the origin self-suppresses its own broadcast echo, so it fires exactly once locally and once per remote node).
 *
 * <p>The library guarantees the <b>event</b>, not the subscription graph: the app owns the roster (who-watches-whom)
 * and pushes the update to watchers (typically via {@code sendToUser}). Dead-node reaps emit the {@code -> OFFLINE}
 * transition for a crashed user's connections, so watchers learn a user went offline even without a graceful close.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public interface PresenceChangeListener {

    /**
     * @param userId       the user whose aggregate presence changed
     * @param oldAggregate the previous aggregate
     * @param newAggregate the new aggregate ({@code != oldAggregate})
     */
    void onPresenceChange(String userId, PresenceStatus oldAggregate, PresenceStatus newAggregate);
}
