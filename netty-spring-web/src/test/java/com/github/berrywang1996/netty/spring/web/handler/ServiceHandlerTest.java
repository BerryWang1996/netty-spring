package com.github.berrywang1996.netty.spring.web.handler;

import com.github.berrywang1996.netty.spring.web.context.HttpRuntimeRecorder;
import com.github.berrywang1996.netty.spring.web.context.WebMappingSupporter;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.timeout.IdleStateEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
