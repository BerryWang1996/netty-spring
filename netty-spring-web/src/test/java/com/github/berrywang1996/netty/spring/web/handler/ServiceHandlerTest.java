package com.github.berrywang1996.netty.spring.web.handler;

import com.github.berrywang1996.netty.spring.web.context.HttpRuntimeRecorder;
import com.github.berrywang1996.netty.spring.web.context.WebMappingSupporter;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServiceHandlerTest {

    @TempDir
    Path tempDir;

    @Test
    void resolveStaticFileAllowsFilesUnderRoot() throws Exception {
        Path root = tempDir.resolve("public");
        Path css = root.resolve("css");
        Files.createDirectories(css);
        Path file = css.resolve("app.css");
        Files.write(file, "body{}".getBytes(StandardCharsets.UTF_8));

        assertEquals(
                file.toFile().getCanonicalFile(),
                ServiceHandler.resolveStaticFile(root.toString(), "/css/app.css"));
    }

    @Test
    void resolveStaticFileRejectsPlainTraversalOutsideRoot() throws Exception {
        Path root = tempDir.resolve("public");
        Files.createDirectories(root);

        assertNull(ServiceHandler.resolveStaticFile(root.toString(), "/../secret.txt"));
    }

    @Test
    void resolveStaticFileRejectsEncodedTraversalOutsideRoot() throws Exception {
        Path root = tempDir.resolve("public");
        Files.createDirectories(root);

        assertNull(ServiceHandler.resolveStaticFile(root.toString(), "/%2e%2e/secret.txt"));
        assertNull(ServiceHandler.resolveStaticFile(root.toString(), "/%5c..%5csecret.txt"));
    }

    @Test
    void resolveStaticFileRejectsInvalidEncodedPath() throws Exception {
        Path root = tempDir.resolve("public");
        Files.createDirectories(root);

        assertNull(ServiceHandler.resolveStaticFile(root.toString(), "/%"));
    }

    @Test
    void staticFileWriteFailureListenerClosesChannel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        ChannelPromise promise = channel.newPromise();
        HttpRuntimeRecorder recorder = new HttpRuntimeRecorder();

        ServiceHandler.addStaticFileWriteFailureListener(promise, "/app.css", "last-content", recorder);
        promise.setFailure(new IOException("boom"));
        channel.runPendingTasks();

        assertFalse(channel.isOpen());
        assertEquals(1L, recorder.getRuntimeStats().getStaticFileWriteFailureCount());
        channel.finishAndReleaseAll();
    }

    @Test
    void idleStateEventClosesChannel() {
        WebMappingSupporter supporter = new WebMappingSupporter(new NettyServerStartupProperties(), null);
        EmbeddedChannel channel = new EmbeddedChannel(new ServiceHandler(supporter));

        try {
            channel.pipeline().fireUserEventTriggered(IdleStateEvent.ALL_IDLE_STATE_EVENT);
            channel.runPendingTasks();

            assertFalse(channel.isOpen());
            assertEquals(1L, supporter.getHttpRuntimeStats().getIdleCloseCount());
        } finally {
            supporter.shutdown();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void managementHealthEndpointReturnsUpWhenEnabled() throws Exception {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getManagement().setEnable(true);
        WebMappingSupporter supporter = new WebMappingSupporter(properties, null);
        EmbeddedChannel channel = new EmbeddedChannel(new ServiceHandler(supporter));

        try {
            channel.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    "/netty/health"));

            FullHttpResponse response = awaitResponse(channel);
            try {
                assertEquals(HttpResponseStatus.OK, response.status());
                assertEquals("{\"status\":\"UP\"}", response.content().toString(CharsetUtil.UTF_8));
            } finally {
                ReferenceCountUtil.release(response);
            }
        } finally {
            supporter.shutdown();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void managementEndpointFallsThroughWhenDisabled() throws Exception {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        WebMappingSupporter supporter = new WebMappingSupporter(properties, null);
        EmbeddedChannel channel = new EmbeddedChannel(new ServiceHandler(supporter));

        try {
            channel.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    "/netty/health"));

            FullHttpResponse response = awaitResponse(channel);
            try {
                assertEquals(HttpResponseStatus.NOT_FOUND, response.status());
            } finally {
                ReferenceCountUtil.release(response);
            }
        } finally {
            supporter.shutdown();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void managementEndpointRejectsNonGetRequests() throws Exception {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getManagement().setEnable(true);
        WebMappingSupporter supporter = new WebMappingSupporter(properties, null);
        EmbeddedChannel channel = new EmbeddedChannel(new ServiceHandler(supporter));

        try {
            channel.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.POST,
                    "/netty/health"));

            FullHttpResponse response = awaitResponse(channel);
            try {
                assertEquals(HttpResponseStatus.METHOD_NOT_ALLOWED, response.status());
            } finally {
                ReferenceCountUtil.release(response);
            }
        } finally {
            supporter.shutdown();
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void managementStatusEndpointReturnsRuntimeSnapshotsWhenEnabled() throws Exception {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getManagement().setEnable(true);
        WebMappingSupporter supporter = new WebMappingSupporter(properties, null);
        EmbeddedChannel channel = new EmbeddedChannel(new ServiceHandler(supporter));

        try {
            channel.writeInbound(new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1,
                    HttpMethod.GET,
                    "/netty/status"));

            FullHttpResponse response = awaitResponse(channel);
            try {
                String content = response.content().toString(CharsetUtil.UTF_8);
                assertEquals(HttpResponseStatus.OK, response.status());
                assertTrue(content.contains("\"status\":\"UP\""));
                assertTrue(content.contains("\"handler\""));
                assertTrue(content.contains("\"http\""));
                assertTrue(content.contains("\"websocket\""));
                assertTrue(content.contains("\"activeSessionCount\""));
                assertTrue(content.contains("\"mappingCount\""));
            } finally {
                ReferenceCountUtil.release(response);
            }
        } finally {
            supporter.shutdown();
            channel.finishAndReleaseAll();
        }
    }

    private static FullHttpResponse awaitResponse(EmbeddedChannel channel) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            Object outbound = channel.readOutbound();
            if (outbound instanceof FullHttpResponse) {
                return (FullHttpResponse) outbound;
            }
            ReferenceCountUtil.release(outbound);
            Thread.sleep(10L);
        }
        Object outbound = channel.readOutbound();
        assertTrue(outbound instanceof FullHttpResponse);
        return (FullHttpResponse) outbound;
    }
}
