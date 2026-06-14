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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression gate for the RC2 design-review SECURITY must-fix: the impersonation footgun must NOT be able to
 * silently ship. Asserts that (a) the {@code UserIdResolver} SPI javadoc carries the auth contract verbatim
 * (userId MUST be from the authenticated principal, with the WRONG {@code getQueryParam("userId")} / RIGHT
 * {@code verifiedJwt(...).getSubject()} examples), and (b) the default {@code HandshakeUserIdResolver} is
 * documented as convenience/testing-only, NOT for production.
 *
 * <p>It reads the source files (the contract is documentation, not runtime behavior — the test guards that the
 * documentation is present), resolving the module root from the working directory so it runs from both the
 * module dir and the reactor root.
 */
class HandshakeUserIdResolverSecurityTest {

    private static final String MODULE = "netty-spring-websocket-cluster";
    private static final String PKG = "src/main/java/com/github/berrywang1996/netty/spring/web/websocket/cluster";

    @Test
    void userIdResolverJavadocCarriesAuthContract() throws IOException {
        String src = readSource("spi/UserIdResolver.java");
        assertTrue(src.contains("SECURITY CONTRACT"),
                "UserIdResolver javadoc must label the SECURITY CONTRACT");
        assertTrue(src.contains("authenticated"),
                "the contract must require the userId to come from the AUTHENTICATED principal");
        // The WRONG / RIGHT examples (the concrete impersonation footgun + the fix) must both ship.
        assertTrue(src.contains("getQueryParam(\"userId\")"),
                "the WRONG (impersonation) example must be present");
        assertTrue(src.contains("getSubject()"),
                "the RIGHT (verified-JWT subject) example must be present");
        assertTrue(src.contains("never a raw") || src.contains("client-controllable"),
                "the contract must forbid a raw/client-controllable userId");
    }

    @Test
    void defaultResolverIsLabeledTestingOnly() throws IOException {
        String src = readSource("room/HandshakeUserIdResolver.java");
        String lower = src.toLowerCase();
        assertTrue(lower.contains("testing only") || lower.contains("testing-only"),
                "the default resolver must be documented as testing-only");
        assertTrue(lower.contains("not for production") || lower.contains("convenience"),
                "the default resolver must be documented as convenience/not-for-production");
        // It must also state the production requirement to supply an authenticating resolver.
        assertTrue(src.contains("authenticated") || src.contains("MUST supply"),
                "the default resolver must point to a production resolver that validates identity");
    }

    private static String readSource(String relative) throws IOException {
        Path candidate = moduleRoot().resolve(PKG).resolve(relative);
        return new String(Files.readAllBytes(candidate), StandardCharsets.UTF_8);
    }

    /** Resolves the cluster module root whether tests run from the module dir or the reactor root. */
    private static Path moduleRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        if (Files.exists(cwd.resolve(PKG))) {
            return cwd;                       // running from the module directory
        }
        Path nested = cwd.resolve(MODULE);
        if (Files.exists(nested.resolve(PKG))) {
            return nested;                    // running from the reactor root
        }
        // Fallback: if cwd ends with the module name, use it; else assume module dir.
        return cwd.getFileName() != null && MODULE.equals(cwd.getFileName().toString()) ? cwd : nested;
    }
}
