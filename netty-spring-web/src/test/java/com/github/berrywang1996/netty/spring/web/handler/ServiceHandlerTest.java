package com.github.berrywang1996.netty.spring.web.handler;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
