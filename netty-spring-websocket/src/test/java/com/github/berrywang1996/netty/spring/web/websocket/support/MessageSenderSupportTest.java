package com.github.berrywang1996.netty.spring.web.websocket.support;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.context.DefaultMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSenderRuntimeStats;
import com.github.berrywang1996.netty.spring.web.websocket.context.TextMessage;
import com.github.berrywang1996.netty.spring.web.websocket.exception.MessageUriNotDefinedException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.ThreadPoolExecutor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageSenderSupportTest {

    @Test
    void shutdownStopsCachedSender() throws Exception {
        MessageSenderSupport support = new MessageSenderSupport(new NettyServerBootstrap(null));
        DefaultMessageSender sender =
                new DefaultMessageSender(Collections.<String, MessageMappingResolver>emptyMap());
        setField(MessageSenderSupport.class, support, "messageSender", sender);

        ThreadPoolExecutor executor = extractExecutor(sender);
        support.shutdown();

        assertTrue(executor.isShutdown());
        assertNull(getField(MessageSenderSupport.class, support, "messageSender"));
    }

    @Test
    void shutdownIsSafeWhenSenderWasNeverInitialized() {
        MessageSenderSupport support = new MessageSenderSupport(new NettyServerBootstrap(null));

        assertDoesNotThrow(support::shutdown);
    }

    @Test
    void fallsBackToEmptySenderWhenNoWebsocketMappingsExist() {
        MessageSenderSupport support = new MessageSenderSupport(new NettyServerBootstrap(null));

        assertEquals(0, support.getSessionNums());
        assertEquals(0, support.getSessionNums("/ws/test"));
        assertTrue(support.getRegisteredUri().isEmpty());
        assertFalse(support.isSessionAlive("/ws/test", "session-1"));
        MessageSenderRuntimeStats stats = support.getRuntimeStats();
        assertEquals(0L, stats.getRejectedBroadcastCount());
        assertTrue(stats.getExecutor().isShutdown());
        assertThrows(MessageUriNotDefinedException.class,
                () -> support.sendMessage("/ws/test", new TextMessage("hello"), "session-1"));
        assertThrows(MessageUriNotDefinedException.class,
                () -> support.topicMessage("/ws/test", new TextMessage("hello")));
    }

    private static ThreadPoolExecutor extractExecutor(DefaultMessageSender sender) throws Exception {
        Field field = DefaultMessageSender.class.getDeclaredField("executorService");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(sender);
    }

    private static Object getField(Class<?> type, Object target, String fieldName) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    private static void setField(Class<?> type, Object target, String fieldName, Object value) throws Exception {
        Field field = type.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
