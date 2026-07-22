package com.sugr.core;

import com.sugr.bridge.Bridge;
import com.sugr.bridge.BridgeException;
import com.sugr.bridge.EventBus;
import com.sugr.bridge.Json;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * A single native window: its own webview handle, bridge, event bus, and (if
 * using {@link Frontend.Embedded}) asset server. {@link Application} is the
 * app-level entry point - {@code Application.builder()...run()} creates and
 * blocks on the first ("main") window; {@link Application#openWindow} creates
 * additional windows that share the same native library/arena/UI thread but
 * run independently (closing one doesn't close the others - the app exits
 * once the main window closes, same as {@code webview_run}'s own loop).
 */
public final class Window {

    private static final int WV_HINT_NONE = 0;
    private static final int WV_HINT_MIN = 1;
    private static final int WV_HINT_MAX = 2;
    private static final int WV_HINT_FIXED = 3;

    /**
     * Bootstrap injected via webview_init, before any page (including the first) loads.
     * The trailing "ready ping" is what {@link #markWebviewReady} responds to.
     *
     * <p>This checks the ACTUAL rendered layout ({@code window.innerWidth/innerHeight}),
     * not a proxy for it - an earlier version pinged as soon as {@code window.invoke}
     * existed (proof the WebView2 controller had been created), which sounded right but
     * wasn't: controller creation and the renderer actually having *painted* at the
     * correct size are two different moments (WebView2's rendering is itself
     * asynchronous/out-of-process), so that signal fired too early and secondary windows
     * still visibly jittered into place after being revealed. Polling the real layout
     * size directly ties "ready" to the exact condition the user can actually see, so
     * there's nothing left to guess about.
     */
    private static final String EVENTS_BOOTSTRAP_JS = """
            window.__sugrEvents__ = (function () {
                const listeners = {};
                return {
                    on(name, cb) { (listeners[name] ||= []).push(cb); },
                    off(name, cb) {
                        if (!listeners[name]) return;
                        listeners[name] = listeners[name].filter((registered) => registered !== cb);
                    },
                    dispatch(name, payload) { (listeners[name] || []).forEach((cb) => cb(payload)); }
                };
            })();
            (function () {
                function ping() {
                    if (typeof window.invoke === 'function') {
                        window.invoke('__sugrReady', '[]');
                    } else {
                        setTimeout(ping, 0);
                    }
                }
                function waitForRealLayout() {
                    if (window.innerWidth > 50 && window.innerHeight > 50) {
                        ping();
                    } else {
                        setTimeout(waitForRealLayout, 30);
                    }
                }
                waitForRealLayout();
            })();
            """;

    @FunctionalInterface
    public interface ResizeListener {
        void onResize(Window window, int width, int height);
    }

    private final NativeLibrary webview;
    private final Arena arena;
    private final String title;
    private final int width;
    private final int height;
    private final Integer minWidth;
    private final Integer minHeight;
    private final Integer maxWidth;
    private final Integer maxHeight;
    private final boolean resizable;
    private final boolean alwaysOnTop;
    private final String iconPath;
    private final Menu menu;
    private final Frontend frontend;
    private final Bridge bridge;
    private final EventBus eventBus;
    private final Consumer<Window> onReady;
    private final Predicate<Window> onCloseRequested;
    private final Consumer<Window> onClosed;
    private final Consumer<Window> onFocus;
    private final Consumer<Window> onBlur;
    private final ResizeListener onResize;

    private MemorySegment handle = MemorySegment.NULL;
    private MethodHandle webviewSetTitle;
    private MethodHandle webviewSetSize;
    private MethodHandle webviewNavigate;
    private MethodHandle webviewBind;
    private MethodHandle webviewRun;
    private MethodHandle webviewDestroy;
    private MethodHandle webviewReturn;
    private MethodHandle webviewEval;
    private MethodHandle webviewGetWindow;
    private UiDispatcher uiDispatcher;
    private AssetServer assetServer;
    private String targetUrl;
    private long nativeWindowHandle;
    private final boolean isMain;
    private volatile boolean webviewReady = false;

    private Window(Builder builder, NativeLibrary webview, Arena arena, boolean isMain) {
        this.webview = webview;
        this.arena = arena;
        this.isMain = isMain;
        this.title = builder.title;
        this.width = builder.width;
        this.height = builder.height;
        this.minWidth = builder.minWidth;
        this.minHeight = builder.minHeight;
        this.maxWidth = builder.maxWidth;
        this.maxHeight = builder.maxHeight;
        this.resizable = builder.resizable;
        this.alwaysOnTop = builder.alwaysOnTop;
        this.iconPath = builder.iconPath;
        this.menu = builder.menu;
        this.frontend = builder.frontend;
        this.bridge = builder.bridge;
        this.eventBus = builder.eventBus;
        this.onReady = builder.onReady;
        this.onCloseRequested = builder.onCloseRequested;
        this.onClosed = builder.onClosed;
        this.onFocus = builder.onFocus;
        this.onBlur = builder.onBlur;
        this.onResize = builder.onResize;
    }

    static Window create(Builder builder, NativeLibrary webview, Arena arena, boolean isMain) throws Throwable {
        Window window = new Window(builder, webview, arena, isMain);
        window.open();
        return window;
    }

    /**
     * The OS's native handle for this window (an {@code HWND} on Windows, 0 elsewhere for
     * now). Useful as the "owner" for a native dialog (see {@link Dialogs}) so it's grouped
     * with this window in the taskbar/Alt-Tab instead of appearing as an unrelated window.
     */
    public long nativeHandle() {
        return nativeWindowHandle;
    }

    /** Sends an event to this window's JS listeners. Safe to call from any thread. */
    public void emit(String event, String payloadJson) {
        try {
            uiDispatcher.runOnUiThread(() -> {
                String js = "window.__sugrEvents__.dispatch(" + Json.quote(event) + "," + payloadJson + ")";
                try {
                    webviewEval.invoke(handle, arena.allocateFrom(js));
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            });
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Reloads this window's frontend (like a browser refresh) - re-navigates to the same
     * URL, re-running the page's JS from scratch. Java-side state (e.g. an open DB
     * connection held by the app's own bridge target) is untouched - only the page reloads.
     */
    public void reload() {
        try {
            uiDispatcher.runOnUiThread(() -> {
                try {
                    webviewNavigate.invoke(handle, arena.allocateFrom(targetUrl));
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            });
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /** Closes this window (same as the user clicking its close button - honors {@code onCloseRequested}). */
    public void close() throws Throwable {
        MemorySegment nativeWindow = (MemorySegment) webviewGetWindow.invoke(handle);
        WindowNative.requestClose(nativeWindow);
    }

    private void open() throws Throwable {
        MethodHandle webviewCreate = webview.downcall("webview_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        webviewSetTitle = webview.downcall("webview_set_title",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        webviewSetSize = webview.downcall("webview_set_size",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        webviewNavigate = webview.downcall("webview_navigate",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle webviewInit = webview.downcall("webview_init",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        webviewBind = webview.downcall("webview_bind",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        webviewRun = webview.downcall("webview_run",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        webviewDestroy = webview.downcall("webview_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        webviewReturn = webview.downcall("webview_return",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        webviewEval = webview.downcall("webview_eval",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        webviewGetWindow = webview.downcall("webview_get_window",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        handle = (MemorySegment) webviewCreate.invoke(1, MemorySegment.NULL);
        if (handle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("webview_create returned NULL");
        }
        uiDispatcher = new UiDispatcher(webview, handle);

        webviewSetTitle.invoke(handle, arena.allocateFrom(title));
        applyChrome();
        webviewInit.invoke(handle, arena.allocateFrom(EVENTS_BOOTSTRAP_JS));

        MemorySegment invokeStub = webview.upcall(MethodHandles.lookup(), this, "onInvoke",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        webviewBind.invoke(handle, arena.allocateFrom("invoke"), invokeStub, MemorySegment.NULL);

        MemorySegment emitStub = webview.upcall(MethodHandles.lookup(), this, "onEmitFromJs",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        webviewBind.invoke(handle, arena.allocateFrom("emit"), emitStub, MemorySegment.NULL);

        MemorySegment nativeWindow = (MemorySegment) webviewGetWindow.invoke(handle);
        nativeWindowHandle = nativeWindow.address();
        WindowNative.installSubclass(this, nativeWindow);

        targetUrl = switch (frontend) {
            case Frontend.DevServer dev -> dev.url();
            case Frontend.Embedded embedded -> {
                assetServer = new AssetServer(embedded.resourceRoot());
                yield assetServer.baseUrl();
            }
        };
        webviewNavigate.invoke(handle, arena.allocateFrom(targetUrl));

        if (onReady != null) {
            onReady.accept(this);
        }
    }

    /**
     * Called from {@link #onInvoke} when the JS-side "ready ping" (see
     * {@link #EVENTS_BOOTSTRAP_JS}) arrives, confirming {@code window.innerWidth/innerHeight}
     * are real rather than the stale/near-zero layout a secondary window can start with.
     *
     * <p>Hiding the window until this point (an earlier version of this fix) turned out to
     * make things worse, not better: WebView2 appears to defer actually committing a resize
     * while its host window is hidden, so revealing it later caused a visible snap back to
     * some earlier (wrong) size instead of preventing one. Keeping the window visible the
     * whole time and nudging its size once real layout is confirmed is a smaller, one-shot
     * correction instead of an open-ended guessing game - not perfectly invisible, but bounded
     * and reliable, which repeated guessing at delays was neither. Only matters for windows
     * opened via {@link Application#openWindow} - the main window never had this problem.
     */
    private void markWebviewReady() {
        if (webviewReady || isMain) {
            return;
        }
        webviewReady = true;
        try {
            webviewSetSize.invoke(handle, width + 1, height, WV_HINT_NONE);
            webviewSetSize.invoke(handle, width, height, WV_HINT_NONE);
        } catch (Throwable t) {
            System.err.println("[sugr] failed to nudge window layout after it was confirmed ready:");
            t.printStackTrace();
        }
    }

    private void applyChrome() throws Throwable {
        webviewSetSize.invoke(handle, width, height, WV_HINT_NONE);
        if (!resizable) {
            webviewSetSize.invoke(handle, width, height, WV_HINT_FIXED);
        } else {
            if (minWidth != null && minHeight != null) {
                webviewSetSize.invoke(handle, minWidth, minHeight, WV_HINT_MIN);
            }
            if (maxWidth != null && maxHeight != null) {
                webviewSetSize.invoke(handle, maxWidth, maxHeight, WV_HINT_MAX);
            }
        }
        MemorySegment nativeWindow = (MemorySegment) webviewGetWindow.invoke(handle);
        if (iconPath != null) {
            WindowNative.setIcon(nativeWindow, iconPath);
        }
        if (alwaysOnTop) {
            WindowNative.setAlwaysOnTop(nativeWindow, true);
        }
        if (menu != null) {
            MemorySegment nativeMenu = WindowNative.buildNativeMenu(menu, true);
            WindowNative.setWindowMenu(nativeWindow, nativeMenu);
        }
    }

    /** Blocks the calling thread on this window's native event loop - only the main window calls this. */
    void runMainLoop() throws Throwable {
        webviewRun.invoke(handle);
        webviewDestroy.invoke(handle);
        if (assetServer != null) {
            assetServer.stop();
        }
    }

    /** Called by {@link WindowNative} when the OS asks to close this window - return false to veto. */
    boolean fireCloseRequested() {
        return onCloseRequested == null || onCloseRequested.test(this);
    }

    /**
     * Called by {@link WindowNative} after this window's HWND has actually been
     * destroyed. The main window's underlying webview_t is cleaned up separately
     * by {@link #runMainLoop} once webview_run() returns (a point guaranteed to
     * be outside any WndProc dispatch); secondary windows have no such point, so
     * their cleanup is scheduled onto a later message-loop tick instead of
     * running here directly - calling webview_destroy() from inside the very
     * WM_DESTROY dispatch that's tearing down this window's HWND is reentrant
     * into webview's own destruction code and corrupted state for the next
     * window created (root-caused via a crash reproduced with this exact
     * create-close-create sequence - see WindowNative's javadoc).
     */
    void fireClosed() {
        if (onClosed != null) {
            onClosed.accept(this);
        }
        if (!isMain) {
            try {
                WindowNative.runLater(this::destroyNative);
            } catch (Throwable t) {
                System.err.println("[sugr] failed to schedule webview_destroy for a closed window:");
                t.printStackTrace();
            }
        }
        if (assetServer != null) {
            assetServer.stop();
        }
    }

    /** Called (on a later message-loop tick, never reentrantly) by {@link WindowNative} to finish cleanup. */
    void destroyNative() {
        try {
            webviewDestroy.invoke(handle);
        } catch (Throwable t) {
            System.err.println("[sugr] webview_destroy failed for a closed window:");
            t.printStackTrace();
        }
    }

    void fireFocusChanged(boolean focused) {
        Consumer<Window> listener = focused ? onFocus : onBlur;
        if (listener != null) {
            listener.accept(this);
        }
    }

    void fireResized(int newWidth, int newHeight) {
        if (onResize != null) {
            onResize.onResize(this, newWidth, newHeight);
        }
    }

    /**
     * Upcall target for the single "invoke" binding; routes to {@link Bridge#dispatch}.
     * The handler may resolve asynchronously (CompletableFuture-backed, e.g. generated
     * by the {@code processor} module) - whichever thread completes it, the reply hops
     * back onto the UI thread via webview_dispatch before calling webview_return.
     *
     * <p>{@code seq} is copied to a Java {@code String} immediately, synchronously,
     * before this method returns - see {@link #reply} for why: the native {@code seq}
     * pointer is only valid for the duration of this callback.
     */
    private static final String READY_PING_METHOD = "__sugrReady";

    private void onInvoke(MemorySegment seq, MemorySegment req, MemorySegment unusedArg) {
        String seqStr = seq.reinterpret(1 << 20).getString(0);
        String reqJson = req.reinterpret(1 << 20).getString(0);

        List<String> peekedArgs = Json.parseStringArray(reqJson);
        if (!peekedArgs.isEmpty() && READY_PING_METHOD.equals(peekedArgs.get(0))) {
            markWebviewReady();
            reply(seqStr, "null", false);
            return;
        }

        bridge.dispatch(reqJson).whenComplete((resultJson, error) -> {
            try {
                String finalResult;
                boolean isError;
                if (error == null) {
                    finalResult = resultJson;
                    isError = false;
                } else {
                    Throwable cause = error instanceof java.util.concurrent.CompletionException && error.getCause() != null
                            ? error.getCause() : error;
                    BridgeException bridgeError = cause instanceof BridgeException be
                            ? be
                            : new BridgeException(cause.getMessage() == null ? cause.toString() : cause.getMessage(), cause);
                    finalResult = bridgeError.toJson().encode();
                    isError = true;
                }
                reply(seqStr, finalResult, isError);
            } catch (Throwable t) {
                // whenComplete() swallows exceptions thrown here - log so async failures are visible.
                System.err.println("[sugr] onInvoke whenComplete failed:");
                t.printStackTrace();
            }
        });
    }

    /**
     * Calls webview_return via {@link UiDispatcher}, re-allocating a native buffer for
     * {@code seq} from the Java copy captured in {@link #onInvoke}. See {@code Application}'s
     * former javadoc here (unchanged behavior, just moved onto Window): the native
     * {@code seq} pointer is only valid for the duration of the synchronous invoke
     * callback, so it must be copied before any async work happens.
     */
    private void reply(String seq, String resultJson, boolean isError) {
        try {
            uiDispatcher.runOnUiThread(() -> {
                try {
                    MemorySegment seqNative = arena.allocateFrom(seq);
                    MemorySegment resultNative = arena.allocateFrom(resultJson);
                    webviewReturn.invoke(handle, seqNative, isError ? 1 : 0, resultNative);
                } catch (Throwable t) {
                    System.err.println("[sugr] webview_return failed:");
                    t.printStackTrace();
                }
            });
        } catch (Throwable t) {
            System.err.println("[sugr] webview_dispatch failed:");
            t.printStackTrace();
        }
    }

    /** Upcall target for the "emit" binding; JS -> Java events, routed to {@link EventBus}. */
    private void onEmitFromJs(MemorySegment seq, MemorySegment req, MemorySegment unusedArg) {
        String reqJson = req.reinterpret(1 << 20).getString(0);
        try {
            List<String> args = Json.parseStringArray(reqJson);
            if (!args.isEmpty() && args.get(0) != null) {
                String event = args.get(0);
                String payload = args.size() > 1 && args.get(1) != null ? args.get(1) : "null";
                eventBus.dispatch(event, payload);
            }
            webviewReturn.invoke(handle, seq, 0, arena.allocateFrom("null"));
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    public static final class Builder {
        private String title = "Sugr App";
        private int width = 900;
        private int height = 640;
        private Integer minWidth;
        private Integer minHeight;
        private Integer maxWidth;
        private Integer maxHeight;
        private boolean resizable = true;
        private boolean alwaysOnTop = false;
        private String iconPath;
        private Menu menu;
        private Frontend frontend = Frontend.embedded("/frontend");
        private final Bridge bridge = new Bridge();
        private final EventBus eventBus = new EventBus();
        private Consumer<Window> onReady;
        private Predicate<Window> onCloseRequested;
        private Consumer<Window> onClosed;
        private Consumer<Window> onFocus;
        private Consumer<Window> onBlur;
        private ResizeListener onResize;

        public Builder() {
        }

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        /** Only takes effect when {@link #resizable} isn't set to false. */
        public Builder minSize(int width, int height) {
            this.minWidth = width;
            this.minHeight = height;
            return this;
        }

        /** Only takes effect when {@link #resizable} isn't set to false. */
        public Builder maxSize(int width, int height) {
            this.maxWidth = width;
            this.maxHeight = height;
            return this;
        }

        public Builder resizable(boolean resizable) {
            this.resizable = resizable;
            return this;
        }

        /** Keeps the window above other windows. Windows only for now - a no-op elsewhere. */
        public Builder alwaysOnTop(boolean alwaysOnTop) {
            this.alwaysOnTop = alwaysOnTop;
            return this;
        }

        /** Path to an icon file (.ico on Windows). Windows only for now - a no-op elsewhere. */
        public Builder icon(String iconPath) {
            this.iconPath = iconPath;
            return this;
        }

        /** Sets this window's native menu bar. Windows only for now - a no-op elsewhere. */
        public Builder menu(Menu menu) {
            this.menu = menu;
            return this;
        }

        public Builder frontend(Frontend frontend) {
            this.frontend = frontend;
            return this;
        }

        public Builder bind(String method, Bridge.Handler handler) {
            bridge.register(method, handler);
            return this;
        }

        /** Like {@link #bind}, but for methods whose result isn't ready immediately. */
        public Builder bindAsync(String method, Bridge.AsyncHandler handler) {
            bridge.registerAsync(method, handler);
            return this;
        }

        /** Registers a Java-side listener for events emitted from JS via events.emit(). */
        public Builder on(String event, Consumer<String> payloadJsonListener) {
            eventBus.on(event, payloadJsonListener);
            return this;
        }

        /**
         * Called once the window and bridge are fully wired, just before the event loop
         * starts blocking (main window) or immediately after creation (other windows).
         * Use it to stash the {@link Window} handle so background code can call
         * {@link Window#emit} later.
         */
        public Builder onReady(Consumer<Window> callback) {
            this.onReady = callback;
            return this;
        }

        /**
         * Called when the user tries to close this window (clicking its close button) -
         * return false to veto the close and keep the window open. Windows only for now;
         * elsewhere the window always closes (this callback never fires, close can't be
         * vetoed). See docs/guide/window.md.
         */
        public Builder onCloseRequested(Predicate<Window> handler) {
            this.onCloseRequested = handler;
            return this;
        }

        /** Called after this window has actually closed (not vetoable - see {@link #onCloseRequested}). */
        public Builder onClosed(Consumer<Window> handler) {
            this.onClosed = handler;
            return this;
        }

        /** Called when this window gains keyboard focus. Windows only for now. */
        public Builder onFocus(Consumer<Window> handler) {
            this.onFocus = handler;
            return this;
        }

        /** Called when this window loses keyboard focus. Windows only for now. */
        public Builder onBlur(Consumer<Window> handler) {
            this.onBlur = handler;
            return this;
        }

        /** Called when this window is resized (by the user dragging its edge, or maximized/restored). Windows only for now. */
        public Builder onResize(ResizeListener handler) {
            this.onResize = handler;
            return this;
        }
    }
}
