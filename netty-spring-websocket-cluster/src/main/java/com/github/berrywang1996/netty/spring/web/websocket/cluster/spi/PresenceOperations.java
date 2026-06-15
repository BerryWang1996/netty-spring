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

import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;

import java.util.concurrent.CompletionStage;

/**
 * Multi-device presence operations (1.10.0-RC3), a small sub-interface implemented by {@code ClusterMessageSender}.
 * The base {@code MessageSender} is untouched; cast to {@code PresenceOperations} when presence is enabled.
 *
 * <p>All methods throw {@link IllegalStateException} when {@code presence.enable=false} (explicit, not a silent drop),
 * mirroring {@code RoomOperations}/{@code UserOperations}.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public interface PresenceOperations {

    /**
     * Sets THIS connection's status (the session being handled). The userId is resolved from the session via the
     * configured {@code UserIdResolver}; an anonymous (unresolved) session is a no-op.
     */
    CompletionStage<Void> setPresence(MessageSession session, PresenceStatus status);

    /** Sets ALL of a user's connections to a status (the "set me away" convenience). */
    CompletionStage<Void> setPresenceForUser(String userId, PresenceStatus status);

    /**
     * Aggregate presence read for a user. Reflects last-known state and is <b>ADVISORY</b> (bounded by the
     * reconciliation interval after a crash), NOT a liveness probe — see {@link UserPresence}.
     */
    CompletionStage<UserPresence> getPresence(String userId);
}
