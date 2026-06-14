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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.room;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserIdResolver;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link UserIdResolver} — reads the userId from a configured handshake source
 * ({@code query:<name>} or {@code header:<name>}; default {@code query:userId}), returning {@code null}
 * when the source is absent.
 *
 * <p><b>CONVENIENCE / TESTING ONLY — NOT FOR PRODUCTION.</b> This resolver trusts the configured
 * query-param/header <b>verbatim</b>; it does NOT authenticate. A client connecting with
 * {@code ?userId=bob} would be treated as {@code bob} and could read {@code bob}'s queued messages,
 * impersonate {@code bob}'s presence, and hijack delivery addressed to {@code bob}. See the SECURITY
 * CONTRACT on {@link UserIdResolver#resolve}.
 *
 * <p>Production IM MUST supply its own {@link UserIdResolver} {@code @Bean} that derives the userId from
 * the session's <b>authenticated</b> principal (verified JWT {@code sub}, OAuth, SAML NameID) — typically a
 * {@code WebSocketHandshakeInterceptor} authenticates the connection and the resolver reads the
 * already-verified principal. The auto-config registers this default only under
 * {@code @ConditionalOnMissingBean}, so a user-supplied resolver replaces it.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
@Slf4j
public class HandshakeUserIdResolver implements UserIdResolver {

    /** Where the userId is read from. */
    enum SourceType { QUERY, HEADER }

    private final SourceType sourceType;
    private final String key;

    /**
     * @param userIdSource {@code query:<name>} or {@code header:<name>} (e.g. {@code query:userId},
     *                     {@code header:X-User-Id}). Malformed/blank → defaults to {@code query:userId}.
     */
    public HandshakeUserIdResolver(String userIdSource) {
        SourceType type = SourceType.QUERY;
        String parsedKey = "userId";
        if (userIdSource != null && !userIdSource.trim().isEmpty()) {
            String s = userIdSource.trim();
            int colon = s.indexOf(':');
            if (colon > 0 && colon < s.length() - 1) {
                String prefix = s.substring(0, colon).trim().toLowerCase();
                String name = s.substring(colon + 1).trim();
                if (!name.isEmpty()) {
                    if ("header".equals(prefix)) {
                        type = SourceType.HEADER;
                        parsedKey = name;
                    } else if ("query".equals(prefix)) {
                        type = SourceType.QUERY;
                        parsedKey = name;
                    } else {
                        log.warn("Unrecognized user-id-source prefix '{}' in '{}' - expected 'query:<name>' "
                                + "or 'header:<name>'; defaulting to query:userId", prefix, userIdSource);
                    }
                } else {
                    log.warn("Empty name in user-id-source '{}' - defaulting to query:userId", userIdSource);
                }
            } else {
                log.warn("Malformed user-id-source '{}' - expected 'query:<name>' or 'header:<name>'; "
                        + "defaulting to query:userId", userIdSource);
            }
        }
        this.sourceType = type;
        this.key = parsedKey;
        log.warn("HandshakeUserIdResolver active (source={}:{}) - CONVENIENCE/TESTING ONLY: it trusts the "
                + "handshake value verbatim with NO authentication. For production, supply your own "
                + "UserIdResolver bean that derives userId from the AUTHENTICATED principal. "
                + "See docs/cluster-design.md Security section.", sourceType.name().toLowerCase(), key);
    }

    @Override
    public String resolve(MessageSession session) {
        if (session == null) {
            return null;
        }
        String value = sourceType == SourceType.QUERY
                ? session.getQueryParam(key)
                : session.getHeader(key);
        if (value == null) {
            return null;
        }
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    /** Test/diagnostic: the resolved source type. */
    SourceType getSourceType() {
        return sourceType;
    }

    /** Test/diagnostic: the resolved key name. */
    String getKey() {
        return key;
    }
}
