package com.sugr.core;

import com.sugr.bridge.Bridge;
import com.sugr.bridge.EventBus;
import com.sugr.bridge.Json;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/**
 * Public entry point. Wraps window creation, the native webview event loop,
 * and the JSON-RPC bridge behind a small builder so callers never touch FFM
 * directly. Thread model: {@link Builder#run()} creates the webview and
 * blocks on the event loop on the calling thread - call it from your main
 * thread (see tuan-3-webview-flow.md in giai-doan-0 for why). {@link #emit}
 * is safe to call from any thread - it always hops onto the UI thread via
 * webview_dispatch before touching the webview.
 */
public final class Application {

    private static final int WV_HINT_NONE = 0;

    /** Bootstrap injected via webview_init, before any page (including the first) loads. */
    private static final String EVENTS_BOOTSTRAP_JS = """
            window.__sugrEvents__ = (function () {
                const listeners = {};
                return {
                    on(name, cb) { (listeners[name] ||= []).push(cb); },
                    dispatch(name, payload) { (listeners[name] || []).forEach((cb) => cb(payload)); }
                };
            })();
            """;

    private final String title;
    private final int width;
    private final int height;
    private final Frontend frontend;
    private final Bridge bridge;
    private final EventBus eventBus;
    private final Consumer<Application> onReady;

    private final Arena arena = Arena.ofShared();
    private MemorySegment handle = MemorySegment.NULL;
    private MethodHandle webviewReturn;
    private MethodHandle webviewEval;
    private UiDispatcher uiDispatcher;
    private AssetServer assetServer;

    private Application(Builder builder) {
        this.title = builder.title;
        this.width = builder.width;
        this.height = builder.height;
        this.frontend = builder.frontend;
        this.bridge = builder.bridge;
        this.eventBus = builder.eventBus;
        this.onReady = builder.onReady;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Sends an event to JS listeners registered via {@code events.on()} (@sugr/runtime).
     * Safe to call from any thread, including background threads unrelated to the UI loop.
     */
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

    private void run() throws Throwable {
        Path dllPath = NativeLibrary.extractToTempFile(Application.class, "/native/webview.dll", "webview", ".dll");
        NativeLibrary webview = new NativeLibrary(dllPath, arena);

        MethodHandle webviewCreate = webview.downcall("webview_create",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        MethodHandle webviewSetTitle = webview.downcall("webview_set_title",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle webviewSetSize = webview.downcall("webview_set_size",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        MethodHandle webviewNavigate = webview.downcall("webview_navigate",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle webviewInit = webview.downcall("webview_init",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle webviewBind = webview.downcall("webview_bind",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        MethodHandle webviewRun = webview.downcall("webview_run",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        MethodHandle webviewDestroy = webview.downcall("webview_destroy",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        webviewReturn = webview.downcall("webview_return",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                        ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
        webviewEval = webview.downcall("webview_eval",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

        handle = (MemorySegment) webviewCreate.invoke(1, MemorySegment.NULL);
        if (handle.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("webview_create returned NULL");
        }
        uiDispatcher = new UiDispatcher(webview, handle);

        webviewSetTitle.invoke(handle, arena.allocateFrom(title));
        webviewSetSize.invoke(handle, width, height, WV_HINT_NONE);
        webviewInit.invoke(handle, arena.allocateFrom(EVENTS_BOOTSTRAP_JS));

        MemorySegment invokeStub = webview.upcall(MethodHandles.lookup(), this, "onInvoke",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        webviewBind.invoke(handle, arena.allocateFrom("invoke"), invokeStub, MemorySegment.NULL);

        MemorySegment emitStub = webview.upcall(MethodHandles.lookup(), this, "onEmitFromJs",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        webviewBind.invoke(handle, arena.allocateFrom("emit"), emitStub, MemorySegment.NULL);

        String targetUrl = switch (frontend) {
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

        webviewRun.invoke(handle);

        webviewDestroy.invoke(handle);
        if (assetServer != null) {
            assetServer.stop();
        }
    }

    /**
     * Upcall target for the single "invoke" binding; routes to {@link Bridge#dispatch}.
     * The handler may resolve asynchronously (CompletableFuture-backed, e.g. generated
     * by the {@code processor} module) - whichever thread completes it, the reply hops
     * back onto the UI thread via webview_dispatch before calling webview_return.
     */
    private void onInvoke(MemorySegment seq, MemorySegment req, MemorySegment unusedArg) {
        String reqJson = req.reinterpret(1 << 20).getString(0);
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
                    finalResult = Json.quote(cause.getMessage() == null ? cause.toString() : cause.getMessage());
                    isError = true;
                }
                reply(seq, finalResult, isError);
            } catch (Throwable t) {
                // whenComplete() swallows exceptions thrown here - log so async failures are visible.
                System.err.println("[sugr] onInvoke whenComplete failed:");
                t.printStackTrace();
            }
        });
    }

    /**
     * Calls webview_return via {@link UiDispatcher}. If already on the UI thread
     * (the common case - sync handlers complete their future inline, inside the
     * onInvoke upcall), that runs immediately - this is the only path confirmed
     * to work reliably (see SqlBridge.slowGreet's javadoc in examples/sql-client
     * for why: replying later than the original bind callback's synchronous call
     * stack, even from the UI thread, does not reliably resolve the JS Promise
     * with the bundled webview.h build). The cross-thread dispatch path still
     * hops threads correctly, it's specifically webview_return afterwards that
     * the native side doesn't seem to honor.
     */
    private void reply(MemorySegment seq, String resultJson, boolean isError) {
        try {
            uiDispatcher.runOnUiThread(() -> {
                try {
                    MemorySegment resultNative = arena.allocateFrom(resultJson);
                    webviewReturn.invoke(handle, seq, isError ? 1 : 0, resultNative);
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
        private Frontend frontend = Frontend.embedded("/frontend");
        private final Bridge bridge = new Bridge();
        private final EventBus eventBus = new EventBus();
        private Consumer<Application> onReady;

        private Builder() {
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
         * starts blocking. Use it to stash the {@link Application} handle so background
         * code can call {@link Application#emit} later.
         */
        public Builder onReady(Consumer<Application> callback) {
            this.onReady = callback;
            return this;
        }

        public void run() throws Throwable {
            new Application(this).run();
        }
    }
}
