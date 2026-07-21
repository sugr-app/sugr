package com.sugr.core;

import com.sugr.bridge.Bridge;

import java.lang.foreign.Arena;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Public entry point. {@code Application.builder()...run()} creates and runs
 * the app's first ("main") window, blocking the calling thread on its native
 * event loop - call it from your app's main thread (see
 * {@code docs/how-it-works.md}'s Thread model section for why). Everything
 * about an individual window (title, size, chrome, bind/bindAsync/on,
 * close/focus/resize hooks) lives on {@link Window} now - {@code Application}
 * just owns the shared native library/arena and the window registry, and
 * forwards the main window's builder methods so single-window apps don't
 * need to change anything. Call {@link #openWindow} (from {@code onReady} or
 * a bind handler - both already run on the right thread) for more windows.
 */
public final class Application {

    private final Arena arena = Arena.ofShared();
    private final List<Window> windows = Collections.synchronizedList(new ArrayList<>());
    private NativeLibrary nativeLib;
    private Window mainWindow;

    private Application() {
    }

    public static Builder builder() {
        return new Builder();
    }

    /** The window created by {@code builder().run()}. */
    public Window mainWindow() {
        return mainWindow;
    }

    /** Every currently open window, main window first. */
    public List<Window> windows() {
        return List.copyOf(windows);
    }

    /**
     * Opens an additional window sharing this app's native library/arena/UI
     * thread. Closing it doesn't close the app - only closing the main window
     * does (same as the underlying event loop, which only runs for the main
     * window - see {@code Window.runMainLoop}).
     *
     * <p>Creation happens on a later, non-reentrant tick of the shared message
     * loop rather than synchronously - {@code onOpened} (nullable) is called
     * with the new {@link Window} once it's ready. This is necessary, not
     * just cautious: {@code webview_create()} pumps its own nested message
     * loop waiting for WebView2's async environment/controller callback, and
     * doing that reentrantly from a call stack already nested inside another
     * window's message dispatch (which a bind handler always is) corrupted
     * state badly enough to crash the process - see {@link WindowNative}'s
     * javadoc.
     */
    public void openWindow(Window.Builder builder, Consumer<Window> onOpened) throws Throwable {
        WindowNative.runLater(() -> {
            try {
                Window window = Window.create(builder, nativeLib, arena, false);
                windows.add(window);
                if (onOpened != null) {
                    onOpened.accept(window);
                }
            } catch (Throwable t) {
                System.err.println("[sugr] failed to open a new window:");
                t.printStackTrace();
            }
        });
    }

    /** Sends an event to the main window's JS listeners - see {@link Window#emit}. */
    public void emit(String event, String payloadJson) {
        mainWindow.emit(event, payloadJson);
    }

    private void run(Window.Builder mainWindowBuilder, Consumer<Application> onReady) throws Throwable {
        Path dllPath = NativeLibrary.extractToTempFile(Application.class, "/native/webview.dll", "webview", ".dll");
        nativeLib = new NativeLibrary(dllPath, arena);
        mainWindowBuilder.onReady(window -> {
            mainWindow = window;
            windows.add(window);
            if (onReady != null) {
                onReady.accept(this);
            }
        });
        Window window = Window.create(mainWindowBuilder, nativeLib, arena, true);
        window.runMainLoop();
    }

    public static final class Builder {
        private final Window.Builder windowBuilder = new Window.Builder();
        private Consumer<Application> onReady;

        private Builder() {
        }

        public Builder title(String title) {
            windowBuilder.title(title);
            return this;
        }

        public Builder size(int width, int height) {
            windowBuilder.size(width, height);
            return this;
        }

        public Builder minSize(int width, int height) {
            windowBuilder.minSize(width, height);
            return this;
        }

        public Builder maxSize(int width, int height) {
            windowBuilder.maxSize(width, height);
            return this;
        }

        public Builder resizable(boolean resizable) {
            windowBuilder.resizable(resizable);
            return this;
        }

        /** Keeps the main window above other windows. Windows only for now - a no-op elsewhere. */
        public Builder alwaysOnTop(boolean alwaysOnTop) {
            windowBuilder.alwaysOnTop(alwaysOnTop);
            return this;
        }

        /** Path to an icon file (.ico on Windows). Windows only for now - a no-op elsewhere. */
        public Builder icon(String iconPath) {
            windowBuilder.icon(iconPath);
            return this;
        }

        public Builder frontend(Frontend frontend) {
            windowBuilder.frontend(frontend);
            return this;
        }

        public Builder bind(String method, Bridge.Handler handler) {
            windowBuilder.bind(method, handler);
            return this;
        }

        /** Like {@link #bind}, but for methods whose result isn't ready immediately. */
        public Builder bindAsync(String method, Bridge.AsyncHandler handler) {
            windowBuilder.bindAsync(method, handler);
            return this;
        }

        /** Registers a Java-side listener for events emitted from JS via events.emit(). */
        public Builder on(String event, Consumer<String> payloadJsonListener) {
            windowBuilder.on(event, payloadJsonListener);
            return this;
        }

        /** See {@link Window.Builder#onCloseRequested}. */
        public Builder onCloseRequested(Predicate<Window> handler) {
            windowBuilder.onCloseRequested(handler);
            return this;
        }

        /** See {@link Window.Builder#onClosed}. */
        public Builder onClosed(Consumer<Window> handler) {
            windowBuilder.onClosed(handler);
            return this;
        }

        /** See {@link Window.Builder#onFocus}. */
        public Builder onFocus(Consumer<Window> handler) {
            windowBuilder.onFocus(handler);
            return this;
        }

        /** See {@link Window.Builder#onBlur}. */
        public Builder onBlur(Consumer<Window> handler) {
            windowBuilder.onBlur(handler);
            return this;
        }

        /** See {@link Window.Builder#onResize}. */
        public Builder onResize(Window.ResizeListener handler) {
            windowBuilder.onResize(handler);
            return this;
        }

        /**
         * Called once the main window and bridge are fully wired, just before the event loop
         * starts blocking. Use it to stash the {@link Application} handle so background
         * code can call {@link Application#emit}/{@link Application#openWindow} later.
         */
        public Builder onReady(Consumer<Application> callback) {
            this.onReady = callback;
            return this;
        }

        public void run() throws Throwable {
            new Application().run(windowBuilder, onReady);
        }
    }
}
