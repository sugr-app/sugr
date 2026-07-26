package com.sugr.processor;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises {@link EmitsProcessor} through a real (in-memory) javac invocation - see
 * {@code BindProcessorTest}'s javadoc for why (the generated {@code *Emitter} class
 * references {@code com.sugr.core.Window}, hence {@code core} as a test-only dependency).
 */
final class EmitsProcessorTest {

    @TempDir
    Path outputDir;

    @Test
    void generatesEmitterClassAndTsStubForEachEmitsMethodKind() throws IOException {
        String source = """
                package com.example;

                import com.sugr.bridge.Emits;

                public interface AppEvents {
                    @Emits
                    void menuLoadDb(String path);

                    @Emits
                    void refreshed();
                }
                """;

        compile("com.example.AppEvents", source);

        String emitterSource = readGenerated("com/example/AppEventsEmitter.java");
        assertTrue(emitterSource.contains("public final class AppEventsEmitter implements AppEvents {"), emitterSource);
        assertTrue(emitterSource.contains("public AppEventsEmitter(com.sugr.core.Window window)"), emitterSource);
        assertTrue(emitterSource.contains("public void menuLoadDb(java.lang.String path)"), emitterSource);
        assertTrue(emitterSource.contains("window.emit(\"menuLoadDb\", com.sugr.bridge.Json.of(path).encode());"), emitterSource);
        assertTrue(emitterSource.contains("public void refreshed()"), emitterSource);
        assertTrue(emitterSource.contains("window.emit(\"refreshed\", \"null\");"), emitterSource);

        String tsStub = readGenerated("com/example/AppEvents.generated.ts");
        assertTrue(tsStub.contains("import { events } from '@sugr/runtime'"), tsStub);
        assertTrue(tsStub.contains("export const AppEvents = {"), tsStub);
        assertTrue(tsStub.contains("onMenuLoadDb(listener: (payload: string) => void): () => void {"), tsStub);
        assertTrue(tsStub.contains("events.on<string>('menuLoadDb', listener)"), tsStub);
        assertTrue(tsStub.contains("return () => events.off('menuLoadDb', listener)"), tsStub);
        assertTrue(tsStub.contains("onRefreshed(listener: () => void): () => void {"), tsStub);
        assertTrue(tsStub.contains("events.on<null>('refreshed', listener)"), tsStub);
    }

    @Test
    void honorsExplicitEmitsNameOverride() throws IOException {
        String source = """
                package com.example;

                import com.sugr.bridge.Emits;

                public interface RenamedEvents {
                    @Emits("customName")
                    void original(String value);
                }
                """;

        compile("com.example.RenamedEvents", source);

        String emitterSource = readGenerated("com/example/RenamedEventsEmitter.java");
        assertTrue(emitterSource.contains("window.emit(\"customName\","), emitterSource);

        String tsStub = readGenerated("com/example/RenamedEvents.generated.ts");
        assertTrue(tsStub.contains("onCustomName(listener: (payload: string) => void): () => void {"), tsStub);
        assertTrue(tsStub.contains("events.on<string>('customName', listener)"), tsStub);
    }

    private void compile(String qualifiedName, String source) throws IOException {
        JavaFileObject sourceFile = new SimpleJavaFileObject(
                URI.create("string:///" + qualifiedName.replace('.', '/') + ".java"), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return source;
            }
        };

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(diagnostics, null, null);

        List<File> classpath = Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator))
                .map(File::new)
                .toList();
        fileManager.setLocation(javax.tools.StandardLocation.CLASS_PATH, classpath);

        StringWriter out = new StringWriter();
        JavaCompiler.CompilationTask task = compiler.getTask(
                out, fileManager, diagnostics,
                List.of("-proc:only", "-s", outputDir.toString()),
                null, List.of(sourceFile));
        task.setProcessors(List.of(new EmitsProcessor()));

        boolean success = task.call();
        if (!success) {
            String errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(Object::toString)
                    .collect(Collectors.joining("\n"));
            throw new AssertionError("annotation processing failed:\n" + errors + "\n" + out);
        }
    }

    private String readGenerated(String relativePath) throws IOException {
        Path file = outputDir.resolve(relativePath);
        assertTrue(Files.exists(file), () -> file + " was not generated");
        return Files.readString(file);
    }
}
