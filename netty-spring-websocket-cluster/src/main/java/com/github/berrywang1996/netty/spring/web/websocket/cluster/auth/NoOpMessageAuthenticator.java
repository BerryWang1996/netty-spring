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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.auth;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;

/**
 * No-op authenticator (auth disabled): does not sign. On receive it strips a well-formed {@code H1:}
 * tag WITHOUT verifying (so a not-yet-enabled node can still read messages signed by already-enabled
 * peers during a rolling upgrade); plain (untagged) data passes through unchanged.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
public class NoOpMessageAuthenticator implements MessageAuthenticator {

    static final String PREFIX = "H1:";

    @Override
    public String wrap(String encoded) {
        return encoded; // disabled: never sign
    }

    @Override
    public String unwrap(String wireData) {
        if (wireData != null && wireData.startsWith(PREFIX)) {
            int sep = wireData.indexOf(':', PREFIX.length());
            if (sep > 0) {
                return wireData.substring(sep + 1); // strip tag, do not verify
            }
        }
        return wireData;
    }
}
