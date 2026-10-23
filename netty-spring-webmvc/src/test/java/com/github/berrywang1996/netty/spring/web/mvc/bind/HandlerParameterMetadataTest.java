package com.github.berrywang1996.netty.spring.web.mvc.bind;

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.mvc.consts.HttpRequestMethod;
import com.github.berrywang1996.netty.spring.web.mvc.view.HtmlViewHandler;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Compiles real controller bytecode to exercise the Spring 7 parameter discovery boundary. */
class HandlerParameterMetadataTest {

    @TempDir
    Path classes;

    @Test
    void debugOnlyControllerIsRejectedWhenResolversAreCreated() throws Exception {
        try (URLClassLoader loader = compileController(false)) {
            Class<?> controllerType = loader.loadClass("ParameterMetadataFixture");
            Object controller = controllerType.getConstructor().newInstance();
            Method method = controllerType.getMethod("echo", String.class);
            assertFalse(method.getParameters()[0].isNamePresent());

            IllegalStateException baseFailure = assertThrows(IllegalStateException.class,
                    () -> new BaseResolver(method, controller));
            IllegalStateException mvcFailure = assertThrows(IllegalStateException.class,
                    () -> mvcResolver(method, controller));
            for (IllegalStateException failure : List.of(baseFailure, mvcFailure)) {
                assertTrue(failure.getMessage().contains("ParameterMetadataFixture.echo"));
                assertTrue(failure.getMessage().contains("javac -parameters"));
                assertTrue(failure.getMessage().contains("<parameters>true</parameters>"));
            }
        }
    }

    @Test
    void controllerCompiledWithParametersKeepsItsBindingNames() throws Exception {
        try (URLClassLoader loader = compileController(true)) {
            Class<?> controllerType = loader.loadClass("ParameterMetadataFixture");
            Object controller = controllerType.getConstructor().newInstance();
            Method method = controllerType.getMethod("echo", String.class);
            assertTrue(method.getParameters()[0].isNamePresent());
            assertEquals(Collections.singletonMap("value", String.class),
                    new BaseResolver(method, controller).getMethodParamType("echo"));
            assertEquals(Collections.singletonMap("value", String.class),
                    mvcResolver(method, controller).getMethodParamType(HttpRequestMethod.GET));
        }
    }

    @Test
    void noArgumentControllerDoesNotRequireParameterMetadata() throws Exception {
        try (URLClassLoader loader = compileController(false)) {
            Class<?> controllerType = loader.loadClass("ParameterMetadataFixture");
            Object controller = controllerType.getConstructor().newInstance();
            Method method = controllerType.getMethod("noArguments");
            assertTrue(new BaseResolver(method, controller).getMethodParamType("echo").isEmpty());
            assertTrue(mvcResolver(method, controller).getMethodParamType(HttpRequestMethod.GET).isEmpty());
        }
    }

    private URLClassLoader compileController(boolean parameterMetadata) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler, "Run parameter metadata regression tests with a JDK");
        Path source = classes.resolve("ParameterMetadataFixture.java");
        Files.writeString(source, """
                public class ParameterMetadataFixture {
                    public String echo(String value) { return value; }
                    public String noArguments() { return "ok"; }
                }
                """, StandardCharsets.UTF_8);
        List<String> options = new ArrayList<>(List.of("-g", "--release", "17", "-d", classes.toString()));
        if (parameterMetadata) {
            options.add("-parameters");
        }
        options.add(source.toString());
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        assertEquals(0, compiler.run(null, diagnostics, diagnostics, options.toArray(String[]::new)),
                diagnostics.toString(StandardCharsets.UTF_8));
        return new URLClassLoader(new URL[]{classes.toUri().toURL()}, getClass().getClassLoader());
    }

    private RequestMappingResolver mvcResolver(Method method, Object controller) {
        return new RequestMappingResolver("/metadata", Collections.singletonMap(HttpRequestMethod.GET, method),
                controller, new HtmlViewHandler());
    }

    private static class BaseResolver extends AbstractMappingResolver<Object, String> {
        BaseResolver(Method method, Object controller) {
            super("/metadata", Collections.singletonMap("echo", method), controller);
        }

        @Override
        public void resolve(ChannelHandlerContext context, Object message) {
        }

        @Override
        public void removeSession(String sessionId) {
        }
    }
}
