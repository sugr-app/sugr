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
 * Exercises {@link BindProcessor} through a real (in-memory) javac invocation rather than
 * unit-testing its pieces in isolation - {@code TypeCodec} needs a live {@code TypeMirror}
 * from an actual compilation round, which can't be constructed by hand. {@code -proc:only}
 * skips the final bytecode-generation pass, but javac still type-checks the generated
 * {@code *Bridge.java} (which references {@code com.sugr.core.Application}) as part of
 * running the annotation processing rounds - hence {@code core} as a test-only dependency.
 */
final class BindProcessorTest {

    @TempDir
    Path outputDir;

    @Test
    void generatesBridgeClassAndTsStubForEachBindMethodKind() throws IOException {
        String source = """
                package com.example;

                import com.sugr.bridge.Bind;
                import java.util.List;
                import java.util.Map;
                import java.util.concurrent.CompletableFuture;

                public class Greeter {
                    @Bind
                    public String hello(String name) {
                        return "Hello, " + name;
                    }

                    @Bind
                    public void ping() {
                    }

                    @Bind
                    public CompletableFuture<List<String>> namesAsync() {
                        return CompletableFuture.completedFuture(List.of("a", "b"));
                    }

                    @Bind
                    public Map<String, String> stats() {
                        return Map.of();
                    }
                }
                """;

        compile("com.example.Greeter", source);

        String bridgeSource = readGenerated("com/example/GreeterBridge.java");
        assertTrue(bridgeSource.contains("public final class GreeterBridge {"), bridgeSource);
        assertTrue(bridgeSource.contains("public void bindTo(com.sugr.core.Application.Builder builder)"), bridgeSource);
        assertTrue(bridgeSource.contains("builder.bind(\"hello\""), bridgeSource);
        assertTrue(bridgeSource.contains("builder.bind(\"ping\""), bridgeSource);
        assertTrue(bridgeSource.contains("builder.bindAsync(\"namesAsync\""), bridgeSource);
        assertTrue(bridgeSource.contains("builder.bind(\"stats\""), bridgeSource);

        String tsStub = readGenerated("com/example/Greeter.generated.ts");
        assertTrue(tsStub.contains("import { invoke } from '@sugr/runtime'"), tsStub);
        assertTrue(tsStub.contains("export const Greeter = {"), tsStub);
        assertTrue(tsStub.contains("hello(name: string): Promise<string>"), tsStub);
        assertTrue(tsStub.contains("ping(): Promise<void>"), tsStub);
        assertTrue(tsStub.contains("namesAsync(): Promise<string[]>"), tsStub);
        assertTrue(tsStub.contains("stats(): Promise<Record<string, string>>"), tsStub);
    }

    @Test
    void honorsExplicitBindNameOverride() throws IOException {
        String source = """
                package com.example;

                import com.sugr.bridge.Bind;

                public class Renamed {
                    @Bind("customName")
                    public String original() {
                        return "hi";
                    }
                }
                """;

        compile("com.example.Renamed", source);

        String bridgeSource = readGenerated("com/example/RenamedBridge.java");
        assertTrue(bridgeSource.contains("builder.bind(\"customName\""), bridgeSource);

        String tsStub = readGenerated("com/example/Renamed.generated.ts");
        assertTrue(tsStub.contains("customName(): Promise<string>"), tsStub);
        assertTrue(tsStub.contains("invoke('customName', [])"), tsStub);
    }

    @Test
    void generatesNestedInterfaceForRecordReturnType() throws IOException {
        String source = """
                package com.example;

                import com.sugr.bridge.Bind;

                public class Users {
                    public record User(String name, int age) {}

                    @Bind
                    public User currentUser() {
                        return new User("Alice", 30);
                    }
                }
                """;

        compile("com.example.Users", source);

        String tsStub = readGenerated("com/example/Users.generated.ts");
        assertTrue(tsStub.contains("export interface User {"), tsStub);
        assertTrue(tsStub.contains("name: string"), tsStub);
        assertTrue(tsStub.contains("age: number"), tsStub);
        assertTrue(tsStub.contains("currentUser(): Promise<User>"), tsStub);
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
        task.setProcessors(List.of(new BindProcessor()));

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
