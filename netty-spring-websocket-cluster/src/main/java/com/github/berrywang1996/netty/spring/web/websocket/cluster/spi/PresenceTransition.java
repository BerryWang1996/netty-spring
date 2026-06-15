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
 * An aggregate presence transition {@code (oldAggregate -> newAggregate)} for a user (1.10.0-RC3).
 *
 * <p>Returned by every {@link PresenceRegistry} mutation. The caller publishes a {@code PRESENCE_CHANGE} event
 * <b>only when {@link #changed()}</b> (old != new). {@code userId} is {@code null} for the single-user setters (the
 * caller already knows the userId); the dead-node reap fills it in for each changed user via {@link #withUserId}.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public final class PresenceTransition {

    private final String userId;
    private final PresenceStatus oldAggregate;
    private final PresenceStatus newAggregate;

    public PresenceTransition(String userId, PresenceStatus oldAggregate, PresenceStatus newAggregate) {
        this.userId = userId;
        this.oldAggregate = oldAggregate;
        this.newAggregate = newAggregate;
    }

    public String getUserId() {
        return userId;
    }

    public PresenceStatus getOldAggregate() {
        return oldAggregate;
    }

    public PresenceStatus getNewAggregate() {
        return newAggregate;
    }

    /** True when the aggregate actually changed (the only case that publishes an event). */
    public boolean changed() {
        return oldAggregate != newAggregate;
    }

    /** Returns a copy carrying the given userId (used by {@link PresenceRegistry#removeAllForNode}). */
    public PresenceTransition withUserId(String resolvedUserId) {
        return new PresenceTransition(resolvedUserId, oldAggregate, newAggregate);
    }

    @Override
    public String toString() {
        return "PresenceTransition{userId=" + userId + ", " + oldAggregate + "->" + newAggregate + "}";
    }
}
