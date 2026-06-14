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

import java.util.Objects;

/**
 * Immutable reference to one live session of a user: {@code (nodeId, uri, sessionId)} — the routing triple
 * the {@code UserRegistry} returns for {@code sessionsForUser}, used by {@code sendToUser} to unicast to a
 * user's online sessions across the cluster (1.10.0-RC2).
 *
 * @author berrywang1996
 * @since V1.10.0
 */
public final class SessionRef {

    private final String nodeId;
    private final String uri;
    private final String sessionId;

    public SessionRef(String nodeId, String uri, String sessionId) {
        this.nodeId = nodeId;
        this.uri = uri;
        this.sessionId = sessionId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getUri() {
        return uri;
    }

    public String getSessionId() {
        return sessionId;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof SessionRef)) {
            return false;
        }
        SessionRef that = (SessionRef) o;
        return Objects.equals(nodeId, that.nodeId)
                && Objects.equals(uri, that.uri)
                && Objects.equals(sessionId, that.sessionId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(nodeId, uri, sessionId);
    }

    @Override
    public String toString() {
        return "SessionRef{nodeId=" + nodeId + ", uri=" + uri + ", sessionId=" + sessionId + '}';
    }
}
