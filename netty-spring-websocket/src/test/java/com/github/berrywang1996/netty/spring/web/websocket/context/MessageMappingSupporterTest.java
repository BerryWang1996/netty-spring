package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.crypto.AesGcmMessageCryptoCodec;
import com.github.berrywang1996.netty.spring.web.websocket.crypto.MessageCryptoCodec;
import com.github.berrywang1996.netty.spring.web.websocket.crypto.MessageCryptoPolicy;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageMappingSupporterTest {

    @Test
    void uniqueMessageCryptoPolicyIsWiredIntoCreatedResolvers() throws Exception {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(TestEndpoint.class);
            context.registerBean(MessageCryptoCodec.class, PrefixMessageCryptoCodec::new);
            context.registerBean(MessageCryptoPolicy.class, LegacyClientPlainCryptoPolicy::new);
            context.refresh();
            MessageMappingResolver resolver = initSingleResolver(context, cryptoEnabledProperties());
            TestChannel testChannel = new TestChannel();
            MessageSession legacySession = newSession(testChannel.ctx, "/ws/test?client=legacy");
            MessageSession modernSession = newSession(testChannel.ctx, "/ws/test?client=modern");
            TextWebSocketFrame legacyFrame = new TextWebSocketFrame("hello");
            TextWebSocketFrame modernFrame = new TextWebSocketFrame("hello");
            WebSocketFrame legacyOutbound = null;
            WebSocketFrame modernOutbound = null;
            try {
                legacyOutbound = resolver.encryptOutboundFrame(legacySession, legacyFrame);
                modernOutbound = resolver.encryptOutboundFrame(modernSession, modernFrame);

                assertEquals("hello", ((TextWebSocketFrame) legacyOutbound).text());
                assertEquals("enc:hello", ((TextWebSocketFrame) modernOutbound).text());
            } finally {
                if (legacyOutbound == null) {
                    ReferenceCountUtil.release(legacyFrame);
                } else {
                    ReferenceCountUtil.release(legacyOutbound);
                }
                if (modernOutbound == null) {
                    ReferenceCountUtil.release(modernFrame);
                } else {
                    ReferenceCountUtil.release(modernOutbound);
                }
                legacySession.release();
                modernSession.release();
                testChannel.finish();
            }
        }
    }

    @Test
    void multipleMessageCryptoPoliciesFailFastWhenCryptoEnabled() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(TestEndpoint.class);
            context.registerBean(MessageCryptoCodec.class, PrefixMessageCryptoCodec::new);
            context.registerBean("firstPolicy", MessageCryptoPolicy.class, LegacyClientPlainCryptoPolicy::new);
            context.registerBean("secondPolicy", MessageCryptoPolicy.class, AlwaysCryptoPolicy::new);
            context.refresh();
            MessageMappingSupporter supporter = new MessageMappingSupporter();

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> supporter.initMappingResolverMap(cryptoEnabledProperties(), context));

            assertTrue(exception.getMessage().contains("at most one MessageCryptoPolicy"));
            assertTrue(exception.getMessage().contains("Action: keep one policy bean"));
        }
    }

    @Test
    void missingCustomCryptoCodecExplainsNextAction() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(TestEndpoint.class);
            context.refresh();
            MessageMappingSupporter supporter = new MessageMappingSupporter();

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> supporter.initMappingResolverMap(cryptoEnabledProperties(), context));

            assertTrue(exception.getMessage().contains("no MessageCryptoCodec bean"));
            assertTrue(exception.getMessage().contains("Action: define a MessageCryptoCodec bean"));
        }
    }

    @Test
    void missingAesGcmKeyProviderExplainsNextAction() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(TestEndpoint.class);
            context.refresh();
            MessageMappingSupporter supporter = new MessageMappingSupporter();
            NettyServerStartupProperties properties = cryptoEnabledProperties();
            properties.getWebSocket().getCrypto().setAlgorithm(AesGcmMessageCryptoCodec.ALGORITHM);
            properties.getWebSocket().getCrypto().setKeyId("main");

            IllegalStateException exception = assertThrows(IllegalStateException.class,
                    () -> supporter.initMappingResolverMap(properties, context));

            assertTrue(exception.getMessage().contains("MessageCryptoKeyProvider bean"));
            assertTrue(exception.getMessage().contains("Action: define a bean"));
        }
    }

    @Test
    void messageCryptoPoliciesAreIgnoredWhenCryptoDisabled() {
        try (GenericApplicationContext context = new GenericApplicationContext()) {
            context.registerBean(TestEndpoint.class);
            context.registerBean("firstPolicy", MessageCryptoPolicy.class, LegacyClientPlainCryptoPolicy::new);
            context.registerBean("secondPolicy", MessageCryptoPolicy.class, AlwaysCryptoPolicy::new);
            context.refresh();

            MessageMappingResolver resolver = initSingleResolver(context, new NettyServerStartupProperties());

            assertNotNull(resolver);
        }
    }

    private static MessageMappingResolver initSingleResolver(GenericApplicationContext context,
                                                             NettyServerStartupProperties properties) {
        MessageMappingSupporter supporter = new MessageMappingSupporter();
        Map<String, MessageMappingResolver> resolvers = supporter.initMappingResolverMap(properties, context);
        MessageMappingResolver resolver = resolvers.get("/ws/test");
        assertNotNull(resolver);
        return resolver;
    }

    private static NettyServerStartupProperties cryptoEnabledProperties() {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getWebSocket().getCrypto().setEnable(true);
        return properties;
    }

    private static MessageSession newSession(ChannelHandlerContext ctx, String uri) {
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
        try {
            return new MessageSession("session-" + uri, ctx, request);
        } finally {
            request.release();
        }
    }

    private static final class TestChannel {
        private final EmbeddedChannel channel;
        private final ChannelHandlerContext ctx;

        private TestChannel() {
            ContextHolder holder = new ContextHolder();
            this.channel = new EmbeddedChannel(holder);
            this.ctx = holder.ctx;
            assertNotNull(this.ctx);
        }

        private void finish() {
            channel.finishAndReleaseAll();
        }
    }

    private static final class ContextHolder extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
    }

    private static final class PrefixMessageCryptoCodec implements MessageCryptoCodec {
        @Override
        public WebSocketFrame encrypt(MessageSession session, WebSocketFrame plainFrame) {
            return new TextWebSocketFrame("enc:" + ((TextWebSocketFrame) plainFrame).text());
        }

        @Override
        public boolean canDecrypt(MessageSession session, WebSocketFrame encryptedFrame) {
            return encryptedFrame instanceof TextWebSocketFrame
                    && ((TextWebSocketFrame) encryptedFrame).text().startsWith("enc:");
        }

        @Override
        public WebSocketFrame decrypt(MessageSession session, WebSocketFrame encryptedFrame) {
            return new TextWebSocketFrame(((TextWebSocketFrame) encryptedFrame).text().substring("enc:".length()));
        }
    }

    private static final class LegacyClientPlainCryptoPolicy implements MessageCryptoPolicy {
        @Override
        public boolean shouldUseCrypto(MessageSession session) {
            return !"legacy".equals(session.getQueryParam("client"));
        }
    }

    private static final class AlwaysCryptoPolicy implements MessageCryptoPolicy {
        @Override
        public boolean shouldUseCrypto(MessageSession session) {
            return true;
        }
    }

    @Component
    public static final class TestEndpoint {
        @MessageMapping("/ws/test")
        public void onText(String payload) {
        }
    }
}
