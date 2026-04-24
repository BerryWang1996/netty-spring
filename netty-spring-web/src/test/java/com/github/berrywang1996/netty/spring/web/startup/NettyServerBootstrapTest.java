package com.github.berrywang1996.netty.spring.web.startup;

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.context.WebMappingSupporter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.ServerSocket;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        WebMappingSupporter supporter = newSupporter(new NettyServerStartupProperties(),
                executor,
                Collections.<String, AbstractMappingResolver>emptyMap(),
                new Semaphore(1));
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

    @Test
    void ownedApplicationContextCanRestartAfterStop() throws Exception {
        NettyServerBootstrap bootstrap = new NettyServerBootstrap(null);
        NettyServerStartupProperties startupProperties = new NettyServerStartupProperties();
        startupProperties.setLoadSpringApplicationContext(true);
        startupProperties.setConfigLocation("classpath:restart-test-context.xml");
        startupProperties.setPort(findAvailablePort());

        ApplicationContext firstContext = null;
        try {
            bootstrap.start(startupProperties);
            firstContext = bootstrap.getApplicationContext();

            assertTrue(firstContext instanceof ConfigurableApplicationContext);
            assertTrue(((ConfigurableApplicationContext) firstContext).isActive());
            assertFalse(((AtomicBoolean) getField(NettyServerBootstrap.class, bootstrap, "stopped")).get());

            bootstrap.stop();

            assertNull(bootstrap.getApplicationContext());
            assertNull(bootstrap.getWebSockeMappingtResolverMap());

            startupProperties.setPort(findAvailablePort());
            bootstrap.start(startupProperties);
            ApplicationContext secondContext = bootstrap.getApplicationContext();

            assertTrue(secondContext instanceof ConfigurableApplicationContext);
            assertTrue(((ConfigurableApplicationContext) secondContext).isActive());
            assertNotSame(firstContext, secondContext);
        } finally {
            bootstrap.stop();
        }
    }

    @Test
    void stopContinuesWhenSupporterShutdownThrows() throws Exception {
        NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        NioEventLoopGroup workerGroup = new NioEventLoopGroup(1);
        GenericApplicationContext applicationContext = new GenericApplicationContext();
        applicationContext.refresh();
        EmbeddedChannel serverChannel = new EmbeddedChannel();
        AtomicBoolean shutdownCalled = new AtomicBoolean();
        ExplodingWebMappingSupporter supporter = new ExplodingWebMappingSupporter(applicationContext, shutdownCalled);
        NettyServerBootstrap bootstrap = new NettyServerBootstrap(null);

        try {
            setField(NettyServerBootstrap.class, bootstrap, "bossGroup", bossGroup);
            setField(NettyServerBootstrap.class, bootstrap, "workerGroup", workerGroup);
            setField(NettyServerBootstrap.class, bootstrap, "applicationContext", applicationContext);
            setField(NettyServerBootstrap.class, bootstrap, "ownsApplicationContext", true);
            setField(NettyServerBootstrap.class, bootstrap, "serverChannel", serverChannel);
            setField(NettyServerBootstrap.class, bootstrap, "webMappingSupporter", supporter);
            ((AtomicBoolean) getField(NettyServerBootstrap.class, bootstrap, "stopped")).set(false);

            assertDoesNotThrow(bootstrap::stop);

            assertTrue(shutdownCalled.get());
            assertTrue(supporter.getExecutor().isShutdown());
            assertTrue(bossGroup.isShutdown() || bossGroup.isTerminated() || bossGroup.isShuttingDown());
            assertTrue(workerGroup.isShutdown() || workerGroup.isTerminated() || workerGroup.isShuttingDown());
            assertFalse(applicationContext.isActive());
            assertFalse(serverChannel.isOpen());
            assertNull(getField(NettyServerBootstrap.class, bootstrap, "serverChannel"));
            assertNull(getField(NettyServerBootstrap.class, bootstrap, "webMappingSupporter"));
            assertNull(getField(NettyServerBootstrap.class, bootstrap, "startupProperties"));
        } finally {
            supporter.getExecutor().shutdownNow();
            bossGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup.shutdownGracefully().syncUninterruptibly();
            serverChannel.finishAndReleaseAll();
        }
    }

    @Test
    void startupFailureAfterPartialInitializationCleansOwnedRuntimeState() throws Exception {
        NettyServerBootstrap bootstrap = new NettyServerBootstrap(null);
        NettyServerStartupProperties startupProperties = new NettyServerStartupProperties();
        startupProperties.setLoadSpringApplicationContext(true);
        startupProperties.setConfigLocation("classpath:restart-test-context.xml");

        try (ServerSocket occupiedPort = new ServerSocket(0)) {
            startupProperties.setPort(occupiedPort.getLocalPort());

            assertThrows(Exception.class, () -> bootstrap.start(startupProperties));

            assertTrue(((AtomicBoolean) getField(NettyServerBootstrap.class, bootstrap, "stopped")).get());
            assertNull(bootstrap.getApplicationContext());
            assertNull(bootstrap.getWebSockeMappingtResolverMap());
            assertNull(getField(NettyServerBootstrap.class, bootstrap, "bossGroup"));
            assertNull(getField(NettyServerBootstrap.class, bootstrap, "workerGroup"));
            assertNull(getField(NettyServerBootstrap.class, bootstrap, "serverChannel"));
            assertNull(getField(NettyServerBootstrap.class, bootstrap, "webMappingSupporter"));
            assertNull(getField(NettyServerBootstrap.class, bootstrap, "startupProperties"));
            assertFalse((Boolean) getField(NettyServerBootstrap.class, bootstrap, "ownsApplicationContext"));
        }
    }

    private static void setField(Class<?> type, Object target, String name, Object value) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static WebMappingSupporter newSupporter(NettyServerStartupProperties startupProperties,
                                                    ThreadPoolExecutor executor,
                                                    Map<String, AbstractMappingResolver> mappingResolverMap,
                                                    Semaphore semaphore) throws Exception {
        Constructor<WebMappingSupporter> constructor = WebMappingSupporter.class.getDeclaredConstructor(
                NettyServerStartupProperties.class,
                org.springframework.context.ApplicationContext.class,
                java.util.Map.class,
                ThreadPoolExecutor.class,
                Semaphore.class);
        constructor.setAccessible(true);
        return constructor.newInstance(
                startupProperties,
                null,
                mappingResolverMap,
                executor,
                semaphore);
    }

    private static Object getField(Class<?> type, Object target, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int findAvailablePort() throws Exception {
        ServerSocket socket = new ServerSocket(0);
        try {
            return socket.getLocalPort();
        } finally {
            socket.close();
        }
    }

    private static final class ExplodingWebMappingSupporter extends WebMappingSupporter {

        private final AtomicBoolean shutdownCalled;

        private ExplodingWebMappingSupporter(ApplicationContext applicationContext, AtomicBoolean shutdownCalled) {
            super(new NettyServerStartupProperties(), applicationContext);
            this.shutdownCalled = shutdownCalled;
        }

        @Override
        public void shutdown() {
            this.shutdownCalled.set(true);
            super.shutdown();
            throw new IllegalStateException("boom");
        }
    }
}
