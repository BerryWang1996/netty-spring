package com.github.berrywang1996.netty.spring.web.startup;

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.context.WebMappingSupporter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Collections;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NettyServerBootstrapTest {

    @Test
    void stopBeforeStartDoesNothing() {
        NettyServerBootstrap bootstrap = new NettyServerBootstrap(null);
        bootstrap.stop();
    }

    @Test
    void stopShutsDownSupporterAndOwnedContext() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        WebMappingSupporter supporter = newSupporter(executor);
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup(1);
        GenericApplicationContext applicationContext = new GenericApplicationContext();
        applicationContext.refresh();
        EmbeddedChannel serverChannel = new EmbeddedChannel();
        NettyServerBootstrap bootstrap = new NettyServerBootstrap(null);

        try {
            setField(NettyServerBootstrap.class, bootstrap, "bossGroup", bossGroup);
            setField(NettyServerBootstrap.class, bootstrap, "workerGroup", workerGroup);
            setField(NettyServerBootstrap.class, bootstrap, "applicationContext", applicationContext);
            setField(NettyServerBootstrap.class, bootstrap, "ownsApplicationContext", true);
            setField(NettyServerBootstrap.class, bootstrap, "serverChannel", serverChannel);
            setField(NettyServerBootstrap.class, bootstrap, "webMappingSupporter", supporter);
            ((AtomicBoolean) getField(NettyServerBootstrap.class, bootstrap, "stopped")).set(false);

            bootstrap.stop();

            assertTrue(executor.isShutdown());
            assertTrue(bossGroup.isShutdown() || bossGroup.isTerminated() || bossGroup.isShuttingDown());
            assertTrue(workerGroup.isShutdown() || workerGroup.isTerminated() || workerGroup.isShuttingDown());
            assertFalse(applicationContext.isActive());
            assertFalse(serverChannel.isOpen());
        } finally {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup.shutdownGracefully().syncUninterruptibly();
            serverChannel.finishAndReleaseAll();
        }
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static WebMappingSupporter newSupporter(ThreadPoolExecutor executor) throws Exception {
        Constructor<WebMappingSupporter> constructor = WebMappingSupporter.class.getDeclaredConstructor(
                NettyServerStartupProperties.class,
                org.springframework.context.ApplicationContext.class,
                java.util.Map.class,
                ThreadPoolExecutor.class,
                Semaphore.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                new NettyServerStartupProperties(),
                null,
                Collections.<String, AbstractMappingResolver>emptyMap(),
                executor,
                new Semaphore(1));
    }

    private static Object getField(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }
}
