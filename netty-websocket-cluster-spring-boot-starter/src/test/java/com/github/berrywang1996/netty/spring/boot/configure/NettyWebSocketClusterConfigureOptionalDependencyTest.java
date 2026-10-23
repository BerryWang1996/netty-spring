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
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression pin for the 1.10.0 GA docker-demo startup crash.
 *
 * <p>{@code io.nats:jnats} is an OPTIONAL dependency of the cluster modules. In 1.10.0 GA the
 * NATS-typed {@code @Bean} methods were declared directly in {@link NettyWebSocketClusterConfigure};
 * on any classpath without jnats (i.e. EVERY default Redis/mesh deployment — including the
 * docker-demo fat jar) Spring's introspection of the class hit
 * {@code NoClassDefFoundError: io/nats/client/...} → "Failed to introspect Class" → both nodes
 * crashed at startup. Per-method {@code @ConditionalOnClass} did NOT protect against this: the
 * failure happens while the class's method signatures are enumerated, before any bean-method
 * condition is evaluated.
 *
 * <p>The fix (1.10.1) moved every NATS-typed member into the nested {@code NatsClusterConfiguration},
 * gated by a CLASS-level {@code @ConditionalOnClass} — evaluated from ASM metadata before the nested
 * class is ever class-loaded. These tests pin the invariant so the bug cannot silently come back:
 *
 * <ol>
 *   <li>the OUTER class must never take/return/hold an {@code io.nats.client.*} type (reflection
 *       check — the exact thing introspection resolves);</li>
 *   <li>any nested class that touches io.nats types must carry a class-level io.nats
 *       {@code @ConditionalOnClass};</li>
 *   <li>with jnats hidden from the classloader, the default Redis path still starts cleanly.</li>
 * </ol>
 */
class NettyWebSocketClusterConfigureOptionalDependencyTest {

    private static final String NATS_PREFIX = "io.nats.";

    /** The outer class is the class Spring introspects/enhances on EVERY cluster classpath — it must
     *  resolve without jnats. */
    @Test
    void outerClass_hasNoNatsTypedMembers() {
        Class<?> outer = NettyWebSocketClusterConfigure.class;
        for (Method method : outer.getDeclaredMethods()) {
            assertThat(method.getReturnType().getName())
                    .as("outer @Configuration method '%s' returns an optional io.nats type — this breaks "
                            + "introspection on every classpath without jnats (1.10.0 GA crash); move it into "
                            + "the nested NatsClusterConfiguration", method.getName())
                    .doesNotStartWith(NATS_PREFIX);
            for (Class<?> param : method.getParameterTypes()) {
                assertThat(param.getName())
                        .as("outer @Configuration method '%s' takes an optional io.nats parameter — this breaks "
                                + "introspection on every classpath without jnats (1.10.0 GA crash); move it into "
                                + "the nested NatsClusterConfiguration", method.getName())
                        .doesNotStartWith(NATS_PREFIX);
            }
        }
        for (Constructor<?> ctor : outer.getDeclaredConstructors()) {
            for (Class<?> param : ctor.getParameterTypes()) {
                assertThat(param.getName())
                        .as("outer @Configuration constructor takes an optional io.nats parameter")
                        .doesNotStartWith(NATS_PREFIX);
            }
        }
        for (Field field : outer.getDeclaredFields()) {
            assertThat(field.getType().getName())
                    .as("outer @Configuration field '%s' holds an optional io.nats type", field.getName())
                    .doesNotStartWith(NATS_PREFIX);
        }
    }

    /** Any nested class that touches io.nats types is only safe when its class-level condition keeps
     *  it from ever being class-loaded on a jnats-less classpath. */
    @Test
    void nestedClasses_touchingNatsTypes_areClassLevelConditionalOnClassGated() {
        for (Class<?> nested : NettyWebSocketClusterConfigure.class.getDeclaredClasses()) {
            if (!touchesNats(nested)) {
                continue;
            }
            ConditionalOnClass gate = nested.getAnnotation(ConditionalOnClass.class);
            assertThat(gate)
                    .as("nested class '%s' touches io.nats types and MUST carry a CLASS-LEVEL @ConditionalOnClass "
                            + "(evaluated from ASM metadata before the class loads — the only gate that protects "
                            + "optional-dependency-typed members)", nested.getSimpleName())
                    .isNotNull();
            boolean gatesOnNats = Arrays.stream(gate.value())
                    .anyMatch(c -> c.getName().startsWith(NATS_PREFIX))
                    || Arrays.stream(gate.name()).anyMatch(name -> name.startsWith(NATS_PREFIX));
            assertThat(gatesOnNats)
                    .as("nested class '%s' @ConditionalOnClass must name an io.nats type", nested.getSimpleName())
                    .isTrue();
        }
    }

    /** The user-facing outcome: the DEFAULT Redis deployment (no jnats, no nats.servers) starts cleanly.
     *  This is exactly the shape of the docker-demo / real-user classpath that crashed in 1.10.0 GA. */
    @Test
    void clusterEnabled_jnatsHidden_defaultRedisPath_contextStartsCleanly() {
        Assumptions.assumeTrue(ClusterTestRedis.available(), "No Redis available (configured URI, localhost or Docker)");
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        ConfigurationPropertiesAutoConfiguration.class,
                        NettyWebSocketClusterConfigure.class))
                .withUserConfiguration(NettyWebSocketClusterConfigureTest.LocalSenderConfig.class)
                .withClassLoader(new FilteredClassLoader("io.nats.client.Connection"))
                .withPropertyValues(
                        "server.netty.websocket.cluster.enable=true",
                        "server.netty.websocket.cluster.redis.uri=" + ClusterTestRedis.uri(),
                        "server.netty.websocket.cluster.node-id=ctx-jnats-absent-node",
                        "server.netty.websocket.cluster.heartbeat-interval-seconds=30",
                        "server.netty.websocket.cluster.drain-timeout-seconds=0")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean(
                            com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker.class))
                            .isInstanceOf(com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisPubSubBroker.class);
                    assertThat(context.getBean(MessageSender.class)).isInstanceOf(ClusterMessageSender.class);
                    // The NATS connection bean must NOT exist on a jnats-less classpath.
                    assertThat(context).doesNotHaveBean("nettyClusterNatsKvConnection");
                });
    }

    private static boolean touchesNats(Class<?> nested) {
        for (Method method : nested.getDeclaredMethods()) {
            if (method.getReturnType().getName().startsWith(NATS_PREFIX)) {
                return true;
            }
            for (Class<?> param : method.getParameterTypes()) {
                if (param.getName().startsWith(NATS_PREFIX)) {
                    return true;
                }
            }
        }
        for (Field field : nested.getDeclaredFields()) {
            if (field.getType().getName().startsWith(NATS_PREFIX)) {
                return true;
            }
        }
        return false;
    }
}
