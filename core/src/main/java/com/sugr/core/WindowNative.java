package com.sugr.core;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Windows-specific window chrome (icon, always-on-top) and window-event
 * hooking (close/focus/resize) via direct FFM bindings to {@code user32.dll} -
 * {@code webview.h} doesn't expose any of this itself. Every method here is a
 * no-op on macOS/Linux for now; only Windows has been implemented and tested
 * (see docs/guide/window.md) - same "verified on Windows only" pattern as
 * {@code Dialogs}/{@code Clipboard} and {@code PackageCommand}'s per-OS code.
 *
 * <p>Window-event hooking works by subclassing the native window: swapping
 * its {@code GWLP_WNDPROC} for a Java upcall that intercepts
 * {@code WM_CLOSE}/{@code WM_DESTROY}/{@code WM_SIZE}/{@code WM_SETFOCUS}/{@code WM_KILLFOCUS}
 * and forwards everything else (and these too, once handled) to the original
 * proc via {@code CallWindowProcW} - the standard Win32 subclassing idiom.
 * webview's own {@code win32_edge_engine} WndProc (see webview/webview's
 * {@code win32_edge.hh}) stores its {@code win32_edge_engine*} in
 * {@code GWLP_USERDATA}, which this class never touches - the Java-side
 * {@code Window} for a given HWND is tracked in {@link #HWND_TO_WINDOW}
 * instead, keyed by the HWND's raw address.
 *
 * <p>Both {@code webview_create()} and {@code webview_destroy()} internally
 * pump a nested Win32 message loop of their own (see webview/webview's
 * {@code engine_base.hh}: {@code deplete_run_loop_event_queue}, and
 * {@code webview_create}'s wait for WebView2's async environment/controller
 * creation callback) - calling either one reentrantly, from a call stack
 * that's already nested inside another window's message dispatch (e.g. a
 * bind handler, itself invoked from an upcall from webview's own dispatch of
 * the invoking window), corrupts state badly enough to crash or hang the
 * whole process (both reproduced: a null-pointer access violation inside
 * webview.dll creating a second window from within a bind handler, and a
 * full hang destroying one the same way). {@link #runLater} defers a task
 * onto a later, non-reentrant tick of the shared message loop (posted to
 * {@link #driverHwnd}, the first window ever subclassed, which outlives
 * every other window) - {@link Application#openWindow} and
 * {@code Window.fireClosed}'s cleanup both go through it instead of calling
 * webview_create()/webview_destroy() directly from wherever they were asked to.
 */
final class WindowNative {

    private static final int GWLP_WNDPROC = -4;
    private static final int WM_CLOSE = 0x0010;
    private static final int WM_DESTROY = 0x0002;
    private static final int WM_SIZE = 0x0005;
    private static final int WM_SETFOCUS = 0x0007;
    private static final int WM_KILLFOCUS = 0x0008;
    private static final int WM_APP = 0x8000;
    private static final int WM_SUGR_DEFERRED = WM_APP + 1;
    private static final int WM_SETICON = 0x0080;
    private static final int ICON_SMALL = 0;
    private static final int ICON_BIG = 1;
    private static final int IMAGE_ICON = 1;
    private static final int LR_LOADFROMFILE = 0x0010;
    private static final long HWND_TOPMOST = -1L;
    private static final long HWND_NOTOPMOST = -2L;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOSIZE = 0x0004;

    private static final Arena ARENA = Arena.ofShared();
    private static final Map<Long, Window> HWND_TO_WINDOW = new ConcurrentHashMap<>();
    private static final Map<Long, Long> ORIGINAL_WNDPROC = new ConcurrentHashMap<>();
    private static final Queue<Runnable> PENDING_WORK = new ConcurrentLinkedQueue<>();

    /** The first window ever subclassed - stays alive for the app's whole lifetime, used as a deferred-work target. */
    private static volatile MemorySegment driverHwnd;

    private static final Linker LINKER;
    private static final SymbolLookup USER32;
    private static final MethodHandle GET_WINDOW_LONG_PTR;
    private static final MethodHandle SET_WINDOW_LONG_PTR;
    private static final MethodHandle CALL_WINDOW_PROC;
    private static final MethodHandle SET_WINDOW_POS;
    private static final MethodHandle LOAD_IMAGE;
    private static final MethodHandle SEND_MESSAGE;
    private static final MethodHandle POST_MESSAGE;
    private static final MethodHandle SHOW_WINDOW;
    private static MemorySegment subclassTrampoline;

    static {
        if (Os.isWindows()) {
            LINKER = Linker.nativeLinker();
            USER32 = SymbolLookup.libraryLookup("user32.dll", ARENA);
            GET_WINDOW_LONG_PTR = downcall("GetWindowLongPtrW",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            SET_WINDOW_LONG_PTR = downcall("SetWindowLongPtrW",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
            CALL_WINDOW_PROC = downcall("CallWindowProcW",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
            SET_WINDOW_POS = downcall("SetWindowPos",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            LOAD_IMAGE = downcall("LoadImageW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            SEND_MESSAGE = downcall("SendMessageW",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
            POST_MESSAGE = downcall("PostMessageW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
            SHOW_WINDOW = downcall("ShowWindow",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
        } else {
            LINKER = null;
            USER32 = null;
            GET_WINDOW_LONG_PTR = null;
            SET_WINDOW_LONG_PTR = null;
            CALL_WINDOW_PROC = null;
            SET_WINDOW_POS = null;
            LOAD_IMAGE = null;
            SEND_MESSAGE = null;
            POST_MESSAGE = null;
            SHOW_WINDOW = null;
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(USER32.find(name).orElseThrow(), descriptor);
    }

    private WindowNative() {
    }

    static void setIcon(MemorySegment hwnd, String iconPath) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        MemorySegment pathNative = ARENA.allocateFrom(iconPath, StandardCharsets.UTF_16LE);
        MemorySegment hicon = (MemorySegment) LOAD_IMAGE.invoke(MemorySegment.NULL, pathNative, IMAGE_ICON, 0, 0, LR_LOADFROMFILE);
        if (hicon.equals(MemorySegment.NULL)) {
            return; // couldn't load the icon file - not fatal, window just keeps the default
        }
        SEND_MESSAGE.invoke(hwnd, WM_SETICON, (long) ICON_SMALL, hicon.address());
        SEND_MESSAGE.invoke(hwnd, WM_SETICON, (long) ICON_BIG, hicon.address());
    }

    static void setAlwaysOnTop(MemorySegment hwnd, boolean alwaysOnTop) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        long insertAfter = alwaysOnTop ? HWND_TOPMOST : HWND_NOTOPMOST;
        SET_WINDOW_POS.invoke(hwnd, insertAfter, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE);
    }

    static void requestClose(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        POST_MESSAGE.invoke(hwnd, WM_CLOSE, 0L, 0L);
    }

    private static final int SW_HIDE = 0;
    private static final int SW_SHOW = 5;

    static void hide(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        SHOW_WINDOW.invoke(hwnd, SW_HIDE);
    }

    static void show(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        SHOW_WINDOW.invoke(hwnd, SW_SHOW);
    }

    static void installSubclass(Window window, MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        ensureTrampoline();
        if (driverHwnd == null) {
            driverHwnd = hwnd; // first window installed - stays alive for the app's whole lifetime
        }
        long hwndAddr = hwnd.address();
        HWND_TO_WINDOW.put(hwndAddr, window);
        long originalProc = (long) GET_WINDOW_LONG_PTR.invoke(hwnd, GWLP_WNDPROC);
        ORIGINAL_WNDPROC.put(hwndAddr, originalProc);
        SET_WINDOW_LONG_PTR.invoke(hwnd, GWLP_WNDPROC, subclassTrampoline.address());
    }

    /**
     * Runs {@code task} on a later, non-reentrant tick of the shared message loop -
     * see class javadoc for why this matters for webview_create()/webview_destroy().
     * Runs inline if there's no driver window yet (nothing to be reentrant into) or
     * on non-Windows platforms (unverified there either way - see docs/guide/window.md).
     */
    static void runLater(Runnable task) throws Throwable {
        if (!Os.isWindows() || driverHwnd == null) {
            task.run();
            return;
        }
        PENDING_WORK.add(task);
        POST_MESSAGE.invoke(driverHwnd, WM_SUGR_DEFERRED, 0L, 0L);
    }

    private static synchronized void ensureTrampoline() throws Throwable {
        if (subclassTrampoline != null) {
            return;
        }
        MethodHandle target = MethodHandles.lookup().findStatic(WindowNative.class, "onWndProc",
                MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class));
        subclassTrampoline = LINKER.upcallStub(target,
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                ARENA);
    }

    /** Shared WndProc for every subclassed window - looks up which {@link Window} owns {@code hwnd}. */
    private static long onWndProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        long hwndAddr = hwnd.address();
        Window window = HWND_TO_WINDOW.get(hwndAddr);
        Long originalProc = ORIGINAL_WNDPROC.get(hwndAddr);
        if (window == null || originalProc == null) {
            return 0;
        }
        try {
            switch (msg) {
                case WM_CLOSE -> {
                    if (!window.fireCloseRequested()) {
                        return 0; // vetoed - don't forward, so the default handler's DestroyWindow never runs
                    }
                    return callOriginal(originalProc, hwnd, msg, wParam, lParam);
                }
                case WM_DESTROY -> {
                    long result = callOriginal(originalProc, hwnd, msg, wParam, lParam);
                    window.fireClosed();
                    HWND_TO_WINDOW.remove(hwndAddr);
                    ORIGINAL_WNDPROC.remove(hwndAddr);
                    return result;
                }
                case WM_SIZE -> {
                    int newWidth = (int) (lParam & 0xFFFF);
                    int newHeight = (int) ((lParam >> 16) & 0xFFFF);
                    window.fireResized(newWidth, newHeight);
                    return callOriginal(originalProc, hwnd, msg, wParam, lParam);
                }
                case WM_SETFOCUS -> {
                    window.fireFocusChanged(true);
                    return callOriginal(originalProc, hwnd, msg, wParam, lParam);
                }
                case WM_KILLFOCUS -> {
                    window.fireFocusChanged(false);
                    return callOriginal(originalProc, hwnd, msg, wParam, lParam);
                }
                case WM_SUGR_DEFERRED -> {
                    // Reached only via runLater's PostMessage - by construction we're now
                    // outside any other window's message dispatch, so running these is safe.
                    Runnable pending;
                    while ((pending = PENDING_WORK.poll()) != null) {
                        try {
                            pending.run();
                        } catch (Throwable t) {
                            System.err.println("[sugr] deferred window task failed:");
                            t.printStackTrace();
                        }
                    }
                    return 0;
                }
                default -> {
                    return callOriginal(originalProc, hwnd, msg, wParam, lParam);
                }
            }
        } catch (Throwable t) {
            System.err.println("[sugr] window subclass WndProc failed:");
            t.printStackTrace();
            try {
                return callOriginal(originalProc, hwnd, msg, wParam, lParam);
            } catch (Throwable fallbackFailure) {
                return 0;
            }
        }
    }

    private static long callOriginal(long originalProc, MemorySegment hwnd, int msg, long wParam, long lParam) throws Throwable {
        return (long) CALL_WINDOW_PROC.invoke(MemorySegment.ofAddress(originalProc), hwnd, msg, wParam, lParam);
    }
}
