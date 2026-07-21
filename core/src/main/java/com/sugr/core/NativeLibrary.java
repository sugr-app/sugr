package com.sugr.core;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * Thin facade over a loaded native library: wraps {@link Linker} +
 * {@link SymbolLookup} so callers write {@code lib.downcall(name, descriptor)}
 * instead of repeating {@code linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor)}
 * for every native symbol. The {@link Arena} stays caller-owned (passed in,
 * not created here) since callers typically need it for other allocations
 * beyond just this library's downcalls/upcalls.
 */
public final class NativeLibrary {

    private final Linker linker = Linker.nativeLinker();
    private final SymbolLookup lookup;
    private final Arena arena;

    public NativeLibrary(Path dllPath, Arena arena) {
        this.arena = arena;
        this.lookup = SymbolLookup.libraryLookup(dllPath, arena);
    }

    public MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        return linker.downcallHandle(lookup.find(name).orElseThrow(), descriptor);
    }

    /**
     * Binds {@code target.methodName(...)} as a native upcall stub matching {@code descriptor}.
     * {@code lookup} must come from the caller's own class (pass {@code MethodHandles.lookup()}
     * called at the call site) - a {@link MethodHandles.Lookup} is caller-sensitive, so one
     * captured here inside {@code NativeLibrary} would only have this class's own access
     * rights, not the target's (breaks as soon as the target is in a different package/module).
     */
    public MemorySegment upcall(MethodHandles.Lookup lookup, Object target, String methodName,
                                 MethodType methodType, FunctionDescriptor descriptor) throws Throwable {
        MethodHandle handle = lookup.bind(target, methodName, methodType);
        return linker.upcallStub(handle, descriptor, arena);
    }

    /** Copies a classpath resource (e.g. a bundled .dll) to a temp file that native-image/FFM can load from disk. */
    public static Path extractToTempFile(Class<?> owner, String classpathResource, String prefix, String suffix)
            throws IOException {
        try (InputStream in = owner.getResourceAsStream(classpathResource)) {
            if (in == null) {
                throw new IllegalStateException("Could not find " + classpathResource + " on the classpath");
            }
            Path temp = Files.createTempFile(prefix, suffix);
            temp.toFile().deleteOnExit();
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            return temp;
        }
    }
}
