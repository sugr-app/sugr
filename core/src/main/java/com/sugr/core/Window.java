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

    /**
     * Builds the init script that shows a full-viewport splash overlay (default spinner +
     * app title, or {@link #splashHtml} if the app supplied its own) as early as possible -
     * injected via {@code webview_init} like {@link #EVENTS_BOOTSTRAP_JS}, so it runs before
     * the page's own content loads, on every navigation. It removes itself on the window's
     * {@code load} event, which fires once the document and its sub-resources have finished
     * loading - good enough for the common case without requiring the frontend to explicitly
     * signal readiness.
     */
    private String buildSplashScript() {
        String content = splashHtml != null ? splashHtml : defaultSplashHtml();
        return """
                (function () {
                    function inject() {
                        if (document.getElementById('__sugrSplash__')) return;
                        var target = document.body || document.documentElement;
                        if (!target) { setTimeout(inject, 0); return; }
                        var el = document.createElement('div');
                        el.id = '__sugrSplash__';
                        el.style.cssText = 'position:fixed;inset:0;z-index:2147483647;' +
                            'display:flex;align-items:center;justify-content:center;' +
                            'flex-direction:column;background:#fff;';
                        el.innerHTML = %s;
                        target.appendChild(el);
                    }
                    inject();
                    window.addEventListener('load', function () {
                        var el = document.getElementById('__sugrSplash__');
                        if (!el) return;
                        el.style.transition = 'opacity 200ms ease';
                        el.style.opacity = '0';
                        setTimeout(function () { el.remove(); }, 220);
                    });
                })();
                """.formatted(Json.quote(content));
    }

    private String defaultSplashHtml() {
        return """
                <style>
                    #__sugrSplash__ .sugr-spinner {
                        width: 32px; height: 32px; border-radius: 50%%;
                        border: 3px solid rgba(0,0,0,0.15);
                        border-top-color: rgba(0,0,0,0.55);
                        animation: sugr-spin 0.8s linear infinite;
                    }
                    @keyframes sugr-spin { to { transform: rotate(360deg); } }
                </style>
                <div class="sugr-spinner"></div>
                <div style="margin-top:16px;font:14px system-ui,sans-serif;color:#333;">%s</div>
                """.formatted(title.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"));
    }

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
    private final boolean splashScreen;
    private final String splashHtml;
    private final Frontend frontend;
    private final Bridge bridge;
    private final EventBus eventBus;
    private final Consumer<Window> onReady;
    private final Predicate<Window> onCloseRequested;
    private final Consumer<Window> onClosed;
    private final Consumer<Window> onFocus;
    private final Consumer<Window> onBlur;
    private final ResizeListener onResize;

    private WindowStatePersistor windowStatePersistor;
    private boolean windowStateRestored = false;

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
        this.splashScreen = builder.splashScreen;
        this.splashHtml = builder.splashHtml;
        this.frontend = builder.frontend;
        this.bridge = builder.bridge;
        this.eventBus = builder.eventBus;
        this.onReady = builder.onReady;
        this.onCloseRequested = builder.onCloseRequested;
        this.onClosed = builder.onClosed;
        this.onFocus = builder.onFocus;
        this.onBlur = builder.onBlur;
        this.onResize = builder.onResize;
        if (builder.restoreWindowState && builder.appName != null && !builder.appName.isBlank()) {
            this.windowStatePersistor = new WindowStatePersistor(builder.appName);
        }
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

    /** Minimizes this window to the taskbar (Windows only - no-op elsewhere). Safe from any thread. */
    public void minimize() {
        runOnUi(this::nativeMinimize);
    }

    /** Maximizes this window (Windows only - no-op elsewhere). Safe from any thread. */
    public void maximize() {
        runOnUi(this::nativeMaximize);
    }

    /** Restores this window from minimized/maximized state (Windows only - no-op elsewhere). */
    public void restore() {
        runOnUi(this::nativeRestore);
    }

    /** Returns whether this window is currently maximized. */
    public boolean isMaximized() {
        return !Os.isWindows() ? false : nativeState(WindowNative::isMaximized);
    }

    /** Returns whether this window is currently minimized (hidden to taskbar). */
    public boolean isMinimized() {
        return !Os.isWindows() ? false : nativeState(WindowNative::isMinimized);
    }

    /** Toggles fullscreen mode on or off. Windows only for now. Safe from any thread. */
    public void setFullscreen(boolean fullscreen) {
        runOnUi(() -> nativeFullscreen(fullscreen));
    }

    /** Returns the window's on-screen position as {x, y} (Windows only - {0,0} elsewhere). */
    public int[] position() {
        return !Os.isWindows() ? new int[] {0, 0} : nativePosition();
    }

    /** Moves the window to {@code (x, y)}. Windows only for now. Safe from any thread. */
    public void setPosition(int x, int y) {
        runOnUi(() -> nativeSetPosition(x, y));
    }

    /** Returns the window's outer size as {width, height} (Windows only - {0,0} elsewhere). */
    public int[] size() {
        return !Os.isWindows() ? new int[] {0, 0} : nativeSize();
    }

    /** Resizes the window to {@code width}x{@code height}. Windows only for now. */
    public void setSize(int width, int height) {
        runOnUi(() -> nativeSetSize(width, height));
    }

    /** Sets whether this window stays above other windows at runtime. Windows only for now. */
    public void setAlwaysOnTop(boolean alwaysOnTop) {
        runOnUi(() -> nativeAlwaysOnTop(alwaysOnTop));
    }

    /** Hides this window from the taskbar/desktop (used for minimize-to-tray and splash-less startup). */
    public void hide() {
        runOnUi(this::nativeHide);
    }

    /** Shows this window again after {@link #hide()}. */
    public void show() {
        runOnUi(this::nativeShow);
    }

    /**
     * Small sugar over {@link #hide()}: collapses the window to (and thus "into")
     * the system tray by hiding it - the caller is expected to have created a
     * {@link Tray} whose "Show" action calls {@link #show()} to bring it back.
     * Windows-only in effect; a no-op elsewhere.
     */
    public void minimizeToTray() {
        runOnUi(this::nativeHide);
    }

    /** Returns the underlying native window HWND wrapped as a {@link MemorySegment}, or NULL when not ready. */
    MemorySegment nativeWindow() {
        if (handle.equals(MemorySegment.NULL)) {
            return MemorySegment.NULL;
        }
        try {
            return (MemorySegment) webviewGetWindow.invoke(handle);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void nativeMinimize() {
        try {
            WindowNative.minimize(nativeWindow());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void nativeMaximize() {
        try {
            WindowNative.maximize(nativeWindow());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void nativeRestore() {
        try {
            WindowNative.restore(nativeWindow());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void nativeFullscreen(boolean fullscreen) {
        try {
            WindowNative.setFullscreen(nativeWindow(), fullscreen);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private boolean nativeState(WindowStateQuery query) {
        try {
            return query.test(nativeWindow());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private int[] nativePosition() {
        try {
            return WindowNative.getPosition(nativeWindow());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void nativeSetPosition(int x, int y) {
        try {
            WindowNative.setPosition(nativeWindow(), x, y);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private int[] nativeSize() {
        try {
            return WindowNative.getSize(nativeWindow());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void nativeSetSize(int width, int height) {
        try {
            WindowNative.setSize(nativeWindow(), width, height);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void nativeAlwaysOnTop(boolean alwaysOnTop) {
        try {
            WindowNative.setAlwaysOnTop(nativeWindow(), alwaysOnTop);
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void nativeHide() {
        try {
            WindowNative.hide(nativeWindow());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    private void nativeShow() {
        try {
            WindowNative.show(nativeWindow());
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    @FunctionalInterface
    private interface WindowStateQuery {
        boolean test(MemorySegment hwnd) throws Throwable;
    }

    /** Runs {@code task} on the UI thread, preserving the module's runOnUiThread dispatch semantics. */
    private void runOnUi(Runnable task) {
        try {
            uiDispatcher.runOnUiThread(() -> {
                try {
                    task.run();
                } catch (Throwable t) {
                    throw new RuntimeException(t);
                }
            });
        } catch (Throwable t) {
            throw new RuntimeException(t);
        }
    }

    /**
     * Restores the window's last-session position/size/maximized state, if
     * persistence is enabled and a previous state was saved. Runs inline during
     * {@link #open()} - on the window's own creation/UI context, so native calls
     * are safe without a further UI-thread hop.
     */
    private void restoreSavedWindowState() {
        if (windowStatePersistor == null || windowStateRestored) {
            return;
        }
        windowStateRestored = true;
        WindowStatePersistor.State state = windowStatePersistor.load();
        if (state == null) {
            return;
        }
        try {
            MemorySegment nativeWindow = nativeWindow();
            WindowNative.setPosition(nativeWindow, state.x(), state.y());
            WindowNative.setSize(nativeWindow, state.width(), state.height());
            if (state.maximized()) {
                WindowNative.maximize(nativeWindow);
            }
        } catch (Throwable t) {
            System.err.println("[sugr] failed to restore window state:");
            t.printStackTrace();
        }
    }

    /** Persists the window's current bounds + maximized flag, if persistence is enabled. */
    private void persistWindowState() {
        if (windowStatePersistor == null) {
            return;
        }
        try {
            MemorySegment nativeWindow = nativeWindow();
            int[] pos = WindowNative.getPosition(nativeWindow);
            int[] size = WindowNative.getSize(nativeWindow);
            boolean maximized = WindowNative.isMaximized(nativeWindow);
            windowStatePersistor.save(pos[0], pos[1], size[0], size[1], maximized);
        } catch (Throwable t) {
            System.err.println("[sugr] failed to persist window state:");
            t.printStackTrace();
        }
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

        // webview_create() already shows the OS window - at whatever default size webview.dll
        // gives it - before we get a chance to call applyChrome() below. Hiding it immediately
        // and revealing it again only once applyChrome() has committed our actual size means
        // the window's first-ever visible frame is already correctly sized, instead of
        // flashing at the wrong size and then visibly snapping to the configured one.
        MemorySegment nativeWindow = (MemorySegment) webviewGetWindow.invoke(handle);
        WindowNative.hide(nativeWindow);

        webviewSetTitle.invoke(handle, arena.allocateFrom(title));
        applyChrome();
        WindowNative.show(nativeWindow);
        webviewInit.invoke(handle, arena.allocateFrom(EVENTS_BOOTSTRAP_JS));
        if (splashScreen) {
            webviewInit.invoke(handle, arena.allocateFrom(buildSplashScript()));
        }

        MemorySegment invokeStub = webview.upcall(MethodHandles.lookup(), this, "onInvoke",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        webviewBind.invoke(handle, arena.allocateFrom("invoke"), invokeStub, MemorySegment.NULL);

        MemorySegment emitStub = webview.upcall(MethodHandles.lookup(), this, "onEmitFromJs",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        webviewBind.invoke(handle, arena.allocateFrom("emit"), emitStub, MemorySegment.NULL);

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

        restoreSavedWindowState();

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
     * opened via {@link Application#openWindow} - {@link #open}'s own hide-until-{@link
     * #applyChrome}-runs dance now gets the main window's size right before it's ever shown,
     * so this nudge would only add a redundant, visible resize blip there (confirmed: turning
     * it on for the main window as a first attempt at that same size-flash bug reintroduced
     * a visible resize after open, right when the ready ping's nudge fired).
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
        persistWindowState();
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
        private boolean splashScreen = false;
        private String splashHtml;
        private Frontend frontend = Frontend.embedded("/frontend");
        private final Bridge bridge = new Bridge();
        private final EventBus eventBus = new EventBus();
        private Consumer<Window> onReady;
        private Predicate<Window> onCloseRequested;
        private Consumer<Window> onClosed;
        private Consumer<Window> onFocus;
        private Consumer<Window> onBlur;
        private ResizeListener onResize;
        private boolean restoreWindowState = false;
        private String appName;

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

        /**
         * Shows a splash overlay (default spinner + app title, on a white background) as
         * soon as the window's content starts loading, hiding it automatically once the
         * page finishes loading. Use {@link #splashScreen(String)} to supply your own HTML
         * instead of the default spinner.
         */
        public Builder splashScreen() {
            this.splashScreen = true;
            this.splashHtml = null;
            return this;
        }

        /** Like {@link #splashScreen()}, but {@code html} replaces the default spinner content. */
        public Builder splashScreen(String html) {
            this.splashScreen = true;
            this.splashHtml = html;
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

        /**
         * Enables restoring this window's position/size (and maximized state) from the
         * last session on the next launch. The state is saved automatically when the
         * window closes and restored when it opens. Requires {@link #appName} to be set
         * so the state file has a stable home ({@link AppPaths}).
         */
        public Builder restoreWindowState(boolean restore) {
            this.restoreWindowState = restore;
            return this;
        }

        /**
         * Sets the stable app identifier used to scope persisted state (e.g.
         * {@code "sugr.examples.sqlclient"}). Only needed when
         * {@link #restoreWindowState} is enabled - see {@link AppPaths#dataDir}.
         */
        public Builder appName(String appName) {
            this.appName = appName;
            return this;
        }
    }
}
