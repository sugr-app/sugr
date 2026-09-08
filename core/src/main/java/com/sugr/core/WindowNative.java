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
import java.util.Set;
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
    private static final int WM_COMMAND = 0x0111;
    private static final int WM_APP = 0x8000;
    private static final int WM_SUGR_DEFERRED = WM_APP + 1;
    private static final int WM_SETICON = 0x0080;
    private static final int WM_NCCALCSIZE = 0x0083;
    private static final int WM_NCHITTEST = 0x0084;
    private static final int WM_NCLBUTTONDOWN = 0x00A1;
    private static final int WM_NCLBUTTONUP = 0x00A2;
    private static final int HTCAPTION = 2;
    private static final int HTMAXBUTTON = 9;
    private static final int SM_CXSIZEFRAME = 32;
    private static final int SM_CYSIZEFRAME = 33;
    private static final int SM_CXPADDEDBORDER = 92;
    private static final int WS_CHILD = 0x40000000;
    private static final int WS_OVERLAPPEDWINDOW = 0x00CF0000;
    private static final int WS_EX_APPWINDOW = 0x00040000;
    private static final int CW_USEDEFAULT = 0x80000000;
    private static final int WS_EX_LAYERED = 0x00080000;
    private static final String HOST_CLASS_NAME = "SugrHostWindow";
    /** The child window class libwebview creates inside the host to hold the WebView2 controller. */
    private static final String WEBVIEW_WIDGET_CLASS = "webview_widget";
    /** DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2, passed to SetProcessDpiAwarenessContext as a HANDLE. */
    private static final long DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4L;
    private static final int LWA_ALPHA = 0x2;
    private static final int SWP_HIDEWINDOW = 0x0080;
    private static final String SNAP_OVERLAY_CLASS_NAME = "SugrSnapOverlay";
    private static final int ICON_SMALL = 0;
    private static final int ICON_BIG = 1;
    private static final int IMAGE_ICON = 1;
    private static final int LR_LOADFROMFILE = 0x0010;
    private static final long HWND_TOPMOST = -1L;
    private static final long HWND_NOTOPMOST = -2L;
    private static final int SWP_NOSIZE = 0x0001;
    private static final int SWP_NOMOVE = 0x0002;
    private static final int SWP_NOZORDER = 0x0004;
    private static final int SWP_NOACTIVATE = 0x0010;
    private static final int SWP_FRAMECHANGED = 0x0020;
    private static final int SWP_SHOWWINDOW = 0x0040;
    private static final int MF_STRING = 0x00000000;
    private static final int MF_SEPARATOR = 0x00000800;
    private static final int MF_POPUP = 0x00000010;

    private static final Arena ARENA = Arena.ofShared();
    private static final Map<Long, Window> HWND_TO_WINDOW = new ConcurrentHashMap<>();
    private static final Map<Long, Long> ORIGINAL_WNDPROC = new ConcurrentHashMap<>();
    private static final Set<Long> DARK_TITLE_BAR_WINDOWS = ConcurrentHashMap.newKeySet();
    private static final Set<Long> CUSTOM_TITLE_BAR_WINDOWS = ConcurrentHashMap.newKeySet();
    private static final Map<Long, MemorySegment> WINDOW_MENUS = new ConcurrentHashMap<>();
    /** Owning (top-level) window HWND address -> its snap overlay HWND address. */
    private static final Map<Long, Long> SNAP_OVERLAYS = new ConcurrentHashMap<>();
    /** Snap overlay HWND address -> the {@link Window} it toggles maximize/restore on. */
    private static final Map<Long, Window> OVERLAY_OWNERS = new ConcurrentHashMap<>();
    private static final Queue<Runnable> PENDING_WORK = new ConcurrentLinkedQueue<>();
    private static final Map<Integer, Runnable> MENU_ITEM_ACTIONS = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger NEXT_MENU_ITEM_ID =
            new java.util.concurrent.atomic.AtomicInteger(1000);

    /** The first window ever subclassed - stays alive for the app's whole lifetime, used as a deferred-work target. */
    private static volatile MemorySegment driverHwnd;

    private static final Linker LINKER;
    private static final SymbolLookup USER32;
    private static final SymbolLookup DWMAPI;
    private static final SymbolLookup KERNEL32;
    private static final SymbolLookup OLE32;
    private static final MethodHandle CO_INITIALIZE_EX;
    private static final MethodHandle GET_WINDOW_LONG_PTR;
    private static final MethodHandle SET_WINDOW_LONG_PTR;
    private static final MethodHandle CALL_WINDOW_PROC;
    private static final MethodHandle SET_WINDOW_POS;
    private static final MethodHandle LOAD_IMAGE;
    private static final MethodHandle SEND_MESSAGE;
    private static final MethodHandle POST_MESSAGE;
    private static final MethodHandle SHOW_WINDOW;
    private static final MethodHandle SET_FOREGROUND_WINDOW;
    private static final MethodHandle GET_FOREGROUND_WINDOW;
    private static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID;
    private static final MethodHandle GET_CURRENT_THREAD_ID;
    private static final MethodHandle ATTACH_THREAD_INPUT;
    private static final MethodHandle BRING_WINDOW_TO_TOP;
    private static final MethodHandle CREATE_MENU;
    private static final MethodHandle CREATE_POPUP_MENU;
    private static final MethodHandle APPEND_MENU;
    private static final MethodHandle SET_MENU;
    private static final MethodHandle IS_ZOOMED;
    private static final MethodHandle IS_ICONIC;
    private static final MethodHandle FIND_WINDOW_EX;
    private static final MethodHandle GET_CLIENT_RECT;
    private static final MethodHandle SET_PROCESS_DPI_AWARENESS_CONTEXT;
    private static final MemorySegment DEF_WINDOW_PROC_ADDR;
    private static final MethodHandle GET_WINDOW_RECT;
    private static final MethodHandle DWM_SET_WINDOW_ATTRIBUTE;
    private static final MethodHandle GET_SYSTEM_METRICS;
    private static final MethodHandle RELEASE_CAPTURE;
    private static final MethodHandle REGISTER_CLASS_EX;
    private static final MethodHandle CREATE_WINDOW_EX;
    private static final MethodHandle DESTROY_WINDOW;
    private static final MethodHandle GET_MODULE_HANDLE;
    private static final MethodHandle SET_LAYERED_WINDOW_ATTRIBUTES;
    private static final MethodHandle DEF_WINDOW_PROC;
    private static final MethodHandle DWM_DEF_WINDOW_PROC;
    private static MemorySegment subclassTrampoline;
    private static MemorySegment overlayTrampoline;
    private static MemorySegment overlayModuleHandle;
    private static boolean overlayClassRegistered;
    private static boolean hostClassRegistered;
    private static boolean dpiAwarenessSet;

    static {
        if (Os.isWindows()) {
            LINKER = Linker.nativeLinker();
            USER32 = SymbolLookup.libraryLookup("user32.dll", ARENA);
            DWMAPI = SymbolLookup.libraryLookup("dwmapi.dll", ARENA);
            KERNEL32 = SymbolLookup.libraryLookup("kernel32.dll", ARENA);
            OLE32 = SymbolLookup.libraryLookup("ole32.dll", ARENA);
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
            SET_FOREGROUND_WINDOW = downcall("SetForegroundWindow",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            GET_FOREGROUND_WINDOW = downcall("GetForegroundWindow",
                    FunctionDescriptor.of(ValueLayout.ADDRESS));
            GET_WINDOW_THREAD_PROCESS_ID = downcall("GetWindowThreadProcessId",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            GET_CURRENT_THREAD_ID = downcall(KERNEL32, "GetCurrentThreadId",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            ATTACH_THREAD_INPUT = downcall("AttachThreadInput",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            BRING_WINDOW_TO_TOP = downcall("BringWindowToTop",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            CREATE_MENU = downcall("CreateMenu", FunctionDescriptor.of(ValueLayout.ADDRESS));
            CREATE_POPUP_MENU = downcall("CreatePopupMenu", FunctionDescriptor.of(ValueLayout.ADDRESS));
            APPEND_MENU = downcall("AppendMenuW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
            SET_MENU = downcall("SetMenu",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            IS_ZOOMED = downcall("IsZoomed",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            IS_ICONIC = downcall("IsIconic",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            FIND_WINDOW_EX = downcall("FindWindowExW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            GET_CLIENT_RECT = downcall("GetClientRect",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            SET_PROCESS_DPI_AWARENESS_CONTEXT = USER32.find("SetProcessDpiAwarenessContext")
                    .map(sym -> LINKER.downcallHandle(sym,
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS)))
                    .orElse(null);
            DEF_WINDOW_PROC_ADDR = USER32.find("DefWindowProcW").orElseThrow();
            CO_INITIALIZE_EX = LINKER.downcallHandle(OLE32.find("CoInitializeEx").orElseThrow(),
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            GET_WINDOW_RECT = downcall("GetWindowRect",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            DWM_SET_WINDOW_ATTRIBUTE = downcall(DWMAPI, "DwmSetWindowAttribute",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT));
            GET_SYSTEM_METRICS = downcall("GetSystemMetrics",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
            RELEASE_CAPTURE = downcall("ReleaseCapture",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT));
            REGISTER_CLASS_EX = downcall("RegisterClassExW",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            CREATE_WINDOW_EX = downcall("CreateWindowExW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
                            ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            DESTROY_WINDOW = downcall("DestroyWindow",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS));
            GET_MODULE_HANDLE = downcall(KERNEL32, "GetModuleHandleW",
                    FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
            SET_LAYERED_WINDOW_ATTRIBUTES = downcall("SetLayeredWindowAttributes",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_BYTE, ValueLayout.JAVA_INT));
            DEF_WINDOW_PROC = downcall("DefWindowProcW",
                    FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
            DWM_DEF_WINDOW_PROC = downcall(DWMAPI, "DwmDefWindowProc",
                    FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                            ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));
        } else {
            LINKER = null;
            USER32 = null;
            DWMAPI = null;
            KERNEL32 = null;
            OLE32 = null;
            CO_INITIALIZE_EX = null;
            GET_WINDOW_LONG_PTR = null;
            SET_WINDOW_LONG_PTR = null;
            CALL_WINDOW_PROC = null;
            SET_WINDOW_POS = null;
            LOAD_IMAGE = null;
            SEND_MESSAGE = null;
            POST_MESSAGE = null;
            SHOW_WINDOW = null;
            SET_FOREGROUND_WINDOW = null;
            GET_FOREGROUND_WINDOW = null;
            GET_WINDOW_THREAD_PROCESS_ID = null;
            GET_CURRENT_THREAD_ID = null;
            ATTACH_THREAD_INPUT = null;
            BRING_WINDOW_TO_TOP = null;
            CREATE_MENU = null;
            CREATE_POPUP_MENU = null;
            APPEND_MENU = null;
            SET_MENU = null;
            IS_ZOOMED = null;
            IS_ICONIC = null;
            FIND_WINDOW_EX = null;
            GET_CLIENT_RECT = null;
            SET_PROCESS_DPI_AWARENESS_CONTEXT = null;
            DEF_WINDOW_PROC_ADDR = null;
            GET_WINDOW_RECT = null;
            DWM_SET_WINDOW_ATTRIBUTE = null;
            GET_SYSTEM_METRICS = null;
            RELEASE_CAPTURE = null;
            REGISTER_CLASS_EX = null;
            CREATE_WINDOW_EX = null;
            DESTROY_WINDOW = null;
            GET_MODULE_HANDLE = null;
            SET_LAYERED_WINDOW_ATTRIBUTES = null;
            DEF_WINDOW_PROC = null;
            DWM_DEF_WINDOW_PROC = null;
        }
    }

    private static MethodHandle downcall(String name, FunctionDescriptor descriptor) {
        return downcall(USER32, name, descriptor);
    }

    private static MethodHandle downcall(SymbolLookup lookup, String name, FunctionDescriptor descriptor) {
        return LINKER.downcallHandle(lookup.find(name).orElseThrow(), descriptor);
    }

    private WindowNative() {
    }

    /**
     * Touching this class runs its static initializer - loading user32/dwmapi/kernel32 and
     * linking ~40 downcall handles, a couple hundred ms on a cold JVM. {@link Window#open}
     * calls it right before {@code webview_create} so the {@code hide()} straight after that
     * returns runs instantly, instead of leaving libwebview's freshly-created window on
     * screen while the bindings link.
     */
    static void ensureLoaded() {
    }

    /**
     * Creates the app's top-level window ourselves - hidden, no {@code WS_VISIBLE} - and
     * returns its {@code HWND}. libwebview is then handed this handle (webview_create's
     * {@code window} param) so it embeds WebView2 into it as a non-owned window and never
     * calls {@code ShowWindow} on it; {@link Window#open} shows it exactly once at the end.
     * This mirrors how Electron/Tauri/Wails create their window and reveal it after the
     * content is ready. Since the window is non-owned, libwebview also won't resize its
     * WebView2 child on {@code WM_SIZE} or end the run loop on {@code WM_DESTROY} - the
     * subclass installed by {@link #installSubclass} does both (see
     * {@link #resizeWebviewWidget} and {@code Window}'s WM_DESTROY handling).
     */
    static MemorySegment createHostWindow(String title, int width, int height) throws Throwable {
        // libwebview only CoInitializeEx()s for windows it owns. We own ours, and WebView2's
        // environment creation needs an apartment-threaded COM apartment on this thread, so
        // do it here. S_FALSE (already initialized STA) is fine; only a prior MTA init would
        // fail, which the main/UI thread never does.
        CO_INITIALIZE_EX.invoke(MemorySegment.NULL, 0x2 /* COINIT_APARTMENTTHREADED */);
        setDpiAwareness();
        ensureHostClass();
        MemorySegment hwnd = (MemorySegment) CREATE_WINDOW_EX.invoke(
                WS_EX_APPWINDOW,
                ARENA.allocateFrom(HOST_CLASS_NAME, StandardCharsets.UTF_16LE),
                ARENA.allocateFrom(title, StandardCharsets.UTF_16LE),
                WS_OVERLAPPEDWINDOW,
                CW_USEDEFAULT, CW_USEDEFAULT, width, height,
                MemorySegment.NULL, MemorySegment.NULL, overlayModuleHandle, MemorySegment.NULL);
        if (hwnd.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("CreateWindowExW failed for the host window");
        }
        return hwnd;
    }

    /** The host window's client-area size as {@code {width, height}}. */
    static int[] clientSize(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return new int[] {0, 0};
        }
        MemorySegment rc = ARENA.allocate(16);
        if ((int) GET_CLIENT_RECT.invoke(hwnd, rc) == 0) {
            return new int[] {0, 0};
        }
        return new int[] {
                rc.get(ValueLayout.JAVA_INT, 8) - rc.get(ValueLayout.JAVA_INT, 0),
                rc.get(ValueLayout.JAVA_INT, 12) - rc.get(ValueLayout.JAVA_INT, 4)
        };
    }

    /**
     * Resizes every direct child of the host to fill its client area. This libwebview build
     * embeds the WebView2 host window ({@code Chrome_WidgetWin_0}) straight as a child of the
     * window it's given and only re-fits it from the WndProc of a window it owns - ours is
     * non-owned, so the subclass does it here on every {@code WM_SIZE} (the WebView2
     * controller's own bounds are driven separately in {@link Window#onNativeResize}).
     */
    static void resizeWebviewWidget(MemorySegment hostHwnd) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        int[] size = clientSize(hostHwnd);
        // Chrome_WidgetWin_1 (WebView2's actual viewport) doesn't reflow just because its
        // parent Chrome_WidgetWin_0 was resized - Chromium sizes it from the controller's
        // bounds, which this libwebview build gives us no C API to reach. Stretching those
        // two is enough; the render-widget/D3D HWNDs below them follow Chromium's own layout.
        resizeChildTree(hostHwnd, size[0], size[1], 2);
    }

    private static void resizeChildTree(MemorySegment parent, int w, int h, int depth) throws Throwable {
        if (depth <= 0) {
            return;
        }
        MemorySegment child = MemorySegment.NULL;
        while (true) {
            child = (MemorySegment) FIND_WINDOW_EX.invoke(parent, child, MemorySegment.NULL, MemorySegment.NULL);
            if (child.equals(MemorySegment.NULL)) {
                break;
            }
            SET_WINDOW_POS.invoke(child, 0L, 0, 0, w, h, SWP_NOZORDER | SWP_NOACTIVATE);
            resizeChildTree(child, w, h, depth - 1);
        }
    }

    private static synchronized void setDpiAwareness() throws Throwable {
        if (dpiAwarenessSet || !Os.isWindows()) {
            return;
        }
        dpiAwarenessSet = true;
        // libwebview does this for windows it owns; we own ours, so match its per-monitor-v2
        // awareness. Best-effort: absent on Windows < 1703, and a no-op if a manifest already set it.
        if (SET_PROCESS_DPI_AWARENESS_CONTEXT != null) {
            try {
                SET_PROCESS_DPI_AWARENESS_CONTEXT.invoke(
                        MemorySegment.ofAddress(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2));
            } catch (Throwable ignored) {
                // already set / unsupported - not fatal
            }
        }
    }

    private static synchronized void ensureHostClass() throws Throwable {
        if (hostClassRegistered) {
            return;
        }
        overlayModuleHandle = (MemorySegment) GET_MODULE_HANDLE.invoke(MemorySegment.NULL);
        // WNDCLASSEXW (x64, 80 bytes) - see ensureOverlayClass for the field layout.
        MemorySegment wc = ARENA.allocate(80);
        wc.set(ValueLayout.JAVA_INT, 0, 80);
        wc.set(ValueLayout.JAVA_INT, 4, 0);
        wc.set(ValueLayout.ADDRESS, 8, DEF_WINDOW_PROC_ADDR); // libwebview + our subclass do the real work
        wc.set(ValueLayout.JAVA_INT, 16, 0);
        wc.set(ValueLayout.JAVA_INT, 20, 0);
        wc.set(ValueLayout.ADDRESS, 24, overlayModuleHandle);
        wc.set(ValueLayout.ADDRESS, 32, MemorySegment.NULL);
        wc.set(ValueLayout.ADDRESS, 40, MemorySegment.NULL);
        wc.set(ValueLayout.ADDRESS, 48, MemorySegment.NULL); // no background brush - WebView2 covers it, avoids a flash
        wc.set(ValueLayout.ADDRESS, 56, MemorySegment.NULL);
        wc.set(ValueLayout.ADDRESS, 64, ARENA.allocateFrom(HOST_CLASS_NAME, StandardCharsets.UTF_16LE));
        wc.set(ValueLayout.ADDRESS, 72, MemorySegment.NULL);
        if ((int) REGISTER_CLASS_EX.invoke(wc) == 0) {
            throw new IllegalStateException("RegisterClassExW failed for the host window class");
        }
        hostClassRegistered = true;
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

    /** Returns whether this window's caption is currently tinted dark via {@link #setDarkTitleBar}. */
    static boolean isDarkTitleBar(MemorySegment hwnd) {
        return DARK_TITLE_BAR_WINDOWS.contains(hwnd.address());
    }

    /**
     * Tints the window's real native caption (title bar, icon, min/max/close) dark via
     * {@code DwmSetWindowAttribute}'s {@code DWMWA_CAPTION_COLOR}/{@code DWMWA_TEXT_COLOR}
     * (Windows 11 build 22000+ only - a no-op HRESULT failure on anything older, ignored).
     *
     * <p>Nothing about the frame itself changes: the caption, icon, title text, menu bar,
     * min/max/close, drag, resize, and the Windows 11 Snap Layouts flyout are all still
     * 100% native and DWM-owned - only their color is. This is deliberately <em>not</em> a
     * custom-drawn or frame-removed title bar. An earlier version of this method instead cut
     * the button cluster out of WebView2's own region (via {@code SetWindowRgn}) to hand-paint
     * icons with GDI and hand-translate clicks to {@code WM_SYSCOMMAND}, so a custom HTML title
     * bar could still put its own menu/drag region in the same row as real, Snap-Layout-capable
     * buttons. That approach worked, but every native affordance in that row - the hover/press
     * highlight, the Snap Layouts hover chevron - is owned by DWM and can't be suppressed
     * without also losing it (a well-known limitation: even VS Code's own custom title bar
     * can't get Snap Layouts - see microsoft/vscode#127449/#130495), so the highlight always
     * shows through in its own colors regardless of what's hand-painted underneath. VS Code's
     * <em>native</em> title bar mode gets a clean, on-theme look with none of that trade-off by
     * doing exactly what this method does: leaving the caption itself alone and only recoloring
     * it - so that's the approach here too.
     */
    static void setDarkTitleBar(MemorySegment hwnd, boolean dark) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        long hwndAddr = hwnd.address();
        if (dark) {
            DARK_TITLE_BAR_WINDOWS.add(hwndAddr);
        } else {
            DARK_TITLE_BAR_WINDOWS.remove(hwndAddr);
        }
        MemorySegment captionColor = ARENA.allocate(4);
        MemorySegment textColor = ARENA.allocate(4);
        captionColor.set(ValueLayout.JAVA_INT, 0, dark ? DARK_TITLE_BAR_CAPTION_COLOR : DWMWA_COLOR_DEFAULT);
        textColor.set(ValueLayout.JAVA_INT, 0, dark ? DARK_TITLE_BAR_TEXT_COLOR : DWMWA_COLOR_DEFAULT);
        DWM_SET_WINDOW_ATTRIBUTE.invoke(hwnd, DWMWA_CAPTION_COLOR, captionColor, 4);
        DWM_SET_WINDOW_ATTRIBUTE.invoke(hwnd, DWMWA_TEXT_COLOR, textColor, 4);
        // Nudge DWM to repaint the non-client area now that the color changed, without actually
        // moving or resizing the window.
        SET_WINDOW_POS.invoke(hwnd, 0L, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_FRAMECHANGED | SWP_NOZORDER);
    }

    /** Returns whether this window's caption is currently extended into the client area via {@link #setCustomTitleBar}. */
    static boolean isCustomTitleBar(MemorySegment hwnd) {
        return CUSTOM_TITLE_BAR_WINDOWS.contains(hwnd.address());
    }

    /**
     * Extends the client area into the caption (so frontend content can draw its own title
     * bar row) while leaving the frame itself - {@code WS_CAPTION}/{@code WS_THICKFRAME} and
     * everything DWM does with them (shadow, rounded corners, Aero Snap edge-drag, resize
     * border on the left/right/bottom) - untouched; see the {@code WM_NCCALCSIZE} case in
     * {@link #onWndProc} for how the reclaimed area is computed. Two things a real native
     * caption gives you for free don't come along for the ride and have to be replaced by the
     * frontend:
     *
     * <ul>
     *   <li>Dragging the window - the reclaimed area is covered by WebView2's own child HWND,
     *       so {@code WM_NCHITTEST} on this window never even sees clicks there (input is
     *       routed to the topmost window under the cursor, i.e. the child). The frontend must
     *       call {@link #startDrag} itself from a {@code mousedown} handler on its title bar.
     *   <li>The Windows 11 Snap Layouts hover flyout on a custom-drawn maximize button - same
     *       root cause ({@code WM_NCHITTEST}'s {@code HTMAXBUTTON} never reaches this window
     *       either). Not a dead end though: {@link #createSnapOverlay} gets it back via a tiny
     *       invisible native window stacked over the button, the same technique Tauri's
     *       {@code tauri-plugin-frame} uses - see its javadoc. Without that overlay (e.g. an
     *       app that only calls {@code setCustomTitleBar}), clicking the button still maximizes
     *       normally - see {@code AppMenu}/{@code WindowControls} - just without the hover
     *       preview, the trade-off VS Code, Discord, and Spotify's own custom title bars make
     *       (see {@link #setDarkTitleBar}'s javadoc for the related Snap-Layouts caveat).
     * </ul>
     */
    static void setCustomTitleBar(MemorySegment hwnd, boolean enabled) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        if (enabled) {
            CUSTOM_TITLE_BAR_WINDOWS.add(hwnd.address());
        } else {
            CUSTOM_TITLE_BAR_WINDOWS.remove(hwnd.address());
        }
        // Force WM_NCCALCSIZE to run again with the new setting, without actually moving/resizing.
        SET_WINDOW_POS.invoke(hwnd, 0L, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_FRAMECHANGED | SWP_NOZORDER);
    }

    /**
     * Forwards a drag-to-move to the OS on behalf of a click the frontend's own title bar
     * received (see {@link #setCustomTitleBar}'s javadoc for why this can't happen
     * automatically). Mirrors what WebView2's own {@code app-region: drag} support does
     * internally, and what every pre-app-region Electron/CEF app did by hand: release the
     * capture WebView2 already took for the mousedown, then feed the OS a synthetic
     * "the user just pressed down on the caption" message so it drives the rest of the drag
     * (including Aero Snap edge-docking and dragging back down out of maximized) exactly as
     * it would for a real native caption.
     */
    static void startDrag(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        RELEASE_CAPTURE.invoke();
        SEND_MESSAGE.invoke(hwnd, WM_NCLBUTTONDOWN, (long) HTCAPTION, 0L);
    }

    /**
     * Creates (once per window) an invisible native child window stacked over
     * {@code parentHwnd}'s custom-drawn maximize button, so hovering/clicking it gets a
     * real Windows 11 Snap Layouts flyout - something a custom-drawn HTML button alone
     * can never get (see {@link #setCustomTitleBar}'s javadoc for why). Same technique as
     * Tauri's {@code tauri-plugin-frame}: since the overlay is a genuine top-of-z-order
     * HWND rather than pixels painted by WebView2, {@code WM_NCHITTEST} reaches <em>it</em>
     * directly instead of being swallowed by the WebView2 child underneath, so it can
     * answer {@code HTMAXBUTTON} itself. Starts at zero size/hidden - {@link
     * #setSnapOverlayBounds} positions it once the frontend reports where its button
     * actually is.
     */
    static void createSnapOverlay(MemorySegment parentHwnd, Window owner) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        long parentAddr = parentHwnd.address();
        if (SNAP_OVERLAYS.containsKey(parentAddr)) {
            return;
        }
        ensureOverlayClass();
        MemorySegment overlay = (MemorySegment) CREATE_WINDOW_EX.invoke(
                WS_EX_LAYERED, ARENA.allocateFrom(SNAP_OVERLAY_CLASS_NAME, StandardCharsets.UTF_16LE),
                MemorySegment.NULL, WS_CHILD, 0, 0, 0, 0, parentHwnd, MemorySegment.NULL,
                overlayModuleHandle, MemorySegment.NULL);
        if (overlay.equals(MemorySegment.NULL)) {
            throw new IllegalStateException("CreateWindowExW failed for the snap overlay");
        }
        // Fully transparent (alpha 0) but NOT WS_EX_TRANSPARENT - that would let hit-testing
        // (and thus the whole point of this window) pass through to the WebView2 below it.
        SET_LAYERED_WINDOW_ATTRIBUTES.invoke(overlay, 0, (byte) 0, LWA_ALPHA);
        SNAP_OVERLAYS.put(parentAddr, overlay.address());
        OVERLAY_OWNERS.put(overlay.address(), owner);
    }

    /**
     * Repositions {@code parentHwnd}'s snap overlay to match its maximize button's current
     * on-screen rect (physical pixels, relative to the parent's client area - exactly what
     * a DPI-aware {@code getBoundingClientRect()} reading times {@code devicePixelRatio}
     * gives you, since the reclaimed client area's origin is the window's own top-left).
     * Hides the overlay if {@code width}/{@code height} is zero (e.g. before the frontend's
     * first layout pass). No-ops if {@link #createSnapOverlay} hasn't been called yet.
     */
    static void setSnapOverlayBounds(MemorySegment parentHwnd, int x, int y, int width, int height) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        Long overlayAddr = SNAP_OVERLAYS.get(parentHwnd.address());
        if (overlayAddr == null) {
            return;
        }
        MemorySegment overlay = MemorySegment.ofAddress(overlayAddr);
        int visibilityFlag = width > 0 && height > 0 ? SWP_SHOWWINDOW : SWP_HIDEWINDOW;
        SET_WINDOW_POS.invoke(overlay, 0L, x, y, width, height, SWP_NOZORDER | visibilityFlag);
    }

    static void requestClose(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        POST_MESSAGE.invoke(hwnd, WM_CLOSE, 0L, 0L);
    }

    private static final int SW_HIDE = 0;
    private static final int SW_SHOW = 5;
    private static final int SW_RESTORE = 9;
    private static final int SW_MINIMIZE = 6;
    private static final int SW_MAXIMIZE = 3;

    // Window style flags and metrics
    private static final int GWL_STYLE = -16;
    private static final long WS_MAXIMIZEBOX = 0x00010000L;
    private static final long WS_THICKFRAME = 0x00040000L;

    // COLORREF format (0x00BBGGRR). There's no way to read the frontend's CSS from native code,
    // so these are just a reasonable dark-title-bar default, not synced with any app's theme.
    private static final int DARK_TITLE_BAR_TEXT_COLOR = 0x00E8E8E8;
    private static final int DARK_TITLE_BAR_CAPTION_COLOR = 0x002D2D2D;
    private static final int DWMWA_CAPTION_COLOR = 35;
    private static final int DWMWA_TEXT_COLOR = 36;
    private static final int DWMWA_COLOR_DEFAULT = -1; // 0xFFFFFFFF as a signed 32-bit int

    static void hide(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        SHOW_WINDOW.invoke(hwnd, SW_HIDE);
    }

    static void showWindow(MemorySegment hwnd, boolean visible) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        SHOW_WINDOW.invoke(hwnd, visible ? SW_SHOW : SW_HIDE);
    }

    static void minimize(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        SHOW_WINDOW.invoke(hwnd, SW_MINIMIZE);
    }

    static void maximize(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        SHOW_WINDOW.invoke(hwnd, SW_MAXIMIZE);
    }

    static void restore(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        SHOW_WINDOW.invoke(hwnd, SW_RESTORE);
    }

    /** Returns true if the window is currently maximized (Windows only). */
    static boolean isMaximized(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return false;
        }
        return (int) IS_ZOOMED.invoke(hwnd) != 0;
    }

    /** Returns true if the window is currently minimized (Windows only). */
    static boolean isMinimized(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return false;
        }
        return (int) IS_ICONIC.invoke(hwnd) != 0;
    }

    /**
     * Toggles fullscreen by flipping the window style so it has no resize/maximize
     * border, then expanding it over the whole monitor work area (fullscreen) or
     * restoring its previous size (windowed). Windows only for now.
     */
    static void setFullscreen(MemorySegment hwnd, boolean fullscreen) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        long style = (long) GET_WINDOW_LONG_PTR.invoke(hwnd, GWL_STYLE);
        long newStyle = fullscreen
                ? style & ~WS_MAXIMIZEBOX & ~WS_THICKFRAME
                : style | WS_MAXIMIZEBOX | WS_THICKFRAME;
        SET_WINDOW_LONG_PTR.invoke(hwnd, GWL_STYLE, newStyle);
        // Refresh the chrome after the style change so the removed border actually applies.
        SET_WINDOW_POS.invoke(hwnd, 0L, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_FRAMECHANGED | SWP_NOZORDER);
        if (fullscreen) {
            // Expand over the primary monitor's work area (excluding taskbar hacks aside).
            SHOW_WINDOW.invoke(hwnd, SW_MAXIMIZE);
        } else {
            SHOW_WINDOW.invoke(hwnd, SW_RESTORE);
        }
    }

    /** Returns the window's on-screen position as {x, y} (Windows only). */
    static int[] getPosition(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return new int[] {0, 0};
        }
        MemorySegment rc = ARENA.allocate(16); // RECT: left, top, right, bottom (4 ints)
        GET_WINDOW_RECT.invoke(hwnd, rc);
        int left = rc.get(ValueLayout.JAVA_INT, 0);
        int top = rc.get(ValueLayout.JAVA_INT, 4);
        return new int[] {left, top};
    }

    /** Returns the window's outer size as {width, height} (Windows only). */
    static int[] getSize(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return new int[] {0, 0};
        }
        MemorySegment rc = ARENA.allocate(16);
        GET_WINDOW_RECT.invoke(hwnd, rc);
        int left = rc.get(ValueLayout.JAVA_INT, 0);
        int top = rc.get(ValueLayout.JAVA_INT, 4);
        int right = rc.get(ValueLayout.JAVA_INT, 8);
        int bottom = rc.get(ValueLayout.JAVA_INT, 12);
        return new int[] {right - left, bottom - top};
    }

    /** Moves the window to {@code (x, y)} keeping its current size (Windows only). */
    static void setPosition(MemorySegment hwnd, int x, int y) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        int[] size = getSize(hwnd);
        SET_WINDOW_POS.invoke(hwnd, 0L, x, y, size[0], size[1], SWP_NOZORDER);
    }

    /** Resizes the window to {@code width}x{@code height} keeping its top-left corner (Windows only). */
    static void setSize(MemorySegment hwnd, int width, int height) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        SET_WINDOW_POS.invoke(hwnd, 0L, 0, 0, width, height, SWP_NOMOVE | SWP_NOZORDER);
    }

    /** Builds a native HMENU tree from {@code menu}, registering each item's action for WM_COMMAND dispatch. */
    static MemorySegment buildNativeMenu(Menu menu, boolean isRoot) throws Throwable {
        if (!Os.isWindows()) {
            return null;
        }
        MemorySegment hMenu = (MemorySegment) (isRoot ? CREATE_MENU.invoke() : CREATE_POPUP_MENU.invoke());
        for (Menu.Entry entry : menu.entries()) {
            switch (entry) {
                case Menu.Item item -> {
                    int id = NEXT_MENU_ITEM_ID.getAndIncrement();
                    MENU_ITEM_ACTIONS.put(id, item.action());
                    MemorySegment label = ARENA.allocateFrom(item.label(), StandardCharsets.UTF_16LE);
                    APPEND_MENU.invoke(hMenu, MF_STRING, (long) id, label);
                }
                case Menu.Submenu submenu -> {
                    MemorySegment hSubMenu = buildNativeMenu(submenu.menu(), false);
                    MemorySegment label = ARENA.allocateFrom(submenu.label(), StandardCharsets.UTF_16LE);
                    APPEND_MENU.invoke(hMenu, MF_POPUP, hSubMenu.address(), label);
                }
                case Menu.Separator ignored -> APPEND_MENU.invoke(hMenu, MF_SEPARATOR, 0L, MemorySegment.NULL);
            }
        }
        return hMenu;
    }

    static void setWindowMenu(MemorySegment hwnd, MemorySegment hMenu) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        WINDOW_MENUS.put(hwnd.address(), hMenu);
        SET_MENU.invoke(hwnd, hMenu);
    }

    /** Looked up by {@code onWndProc}'s WM_COMMAND case; also used by {@link Tray} for its context menu. */
    static Runnable menuItemAction(int id) {
        return MENU_ITEM_ACTIONS.get(id);
    }

    /**
     * Shows {@code hwnd} and brings it to the front, activated. {@code ShowWindow(SW_SHOW)}
     * alone just makes it visible; {@link Window#open} keeps the window hidden through all of
     * setup and only calls this once at the very end, so nothing else has done the "first
     * show" activation {@code webview_create()} would otherwise do implicitly. See
     * {@link #bringToForeground} for why plain {@code SetForegroundWindow} isn't enough when
     * the app was launched from a terminal / forked by the Gradle daemon.
     */
    static void show(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        SHOW_WINDOW.invoke(hwnd, SW_SHOW);
        bringToForeground(hwnd);
    }

    /**
     * Forces {@code hwnd} to the top of the z-order and gives it focus. A process that isn't
     * already the foreground process (the app JVM is forked by the Gradle daemon under
     * {@code sugr dev}, or just launched from a terminal) has its {@code SetForegroundWindow}
     * calls silently ignored by Windows' focus-stealing guard - the taskbar button only
     * flashes. Briefly attaching our input queue to the current foreground thread lifts that
     * restriction for the duration of the call; the topmost/no-topmost flick then pulls the
     * window above others without pinning it there. Safe to call for a user-driven re-show
     * too (tray "Show", un-minimize) - there the attach is just a no-op belt-and-braces.
     */
    static void bringToForeground(MemorySegment hwnd) throws Throwable {
        if (!Os.isWindows()) {
            return;
        }
        MemorySegment fg = (MemorySegment) GET_FOREGROUND_WINDOW.invoke();
        int myThread = (int) GET_CURRENT_THREAD_ID.invoke();
        int fgThread = fg.equals(MemorySegment.NULL)
                ? 0
                : (int) GET_WINDOW_THREAD_PROCESS_ID.invoke(fg, MemorySegment.NULL);
        boolean attached = fgThread != 0 && fgThread != myThread
                && (int) ATTACH_THREAD_INPUT.invoke(myThread, fgThread, 1) != 0;
        try {
            SET_WINDOW_POS.invoke(hwnd, HWND_TOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
            SET_WINDOW_POS.invoke(hwnd, HWND_NOTOPMOST, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_SHOWWINDOW);
            BRING_WINDOW_TO_TOP.invoke(hwnd);
            SET_FOREGROUND_WINDOW.invoke(hwnd);
        } finally {
            if (attached) {
                ATTACH_THREAD_INPUT.invoke(myThread, fgThread, 0);
            }
        }
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

    private static synchronized void ensureOverlayTrampoline() throws Throwable {
        if (overlayTrampoline != null) {
            return;
        }
        MethodHandle target = MethodHandles.lookup().findStatic(WindowNative.class, "onOverlayWndProc",
                MethodType.methodType(long.class, MemorySegment.class, int.class, long.class, long.class));
        overlayTrampoline = LINKER.upcallStub(target,
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
                        ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                ARENA);
    }

    /** Registers {@link #SNAP_OVERLAY_CLASS_NAME} once per process - see {@link #createSnapOverlay}. */
    private static synchronized void ensureOverlayClass() throws Throwable {
        if (overlayClassRegistered) {
            return;
        }
        ensureOverlayTrampoline();
        overlayModuleHandle = (MemorySegment) GET_MODULE_HANDLE.invoke(MemorySegment.NULL);
        // WNDCLASSEXW (x64 layout, 80 bytes): cbSize:4 style:4 lpfnWndProc:8 cbClsExtra:4
        // cbWndExtra:4 hInstance:8 hIcon:8 hCursor:8 hbrBackground:8 lpszMenuName:8
        // lpszClassName:8 hIconSm:8 - fields after the two ints at offset 16/20 realign to
        // 8 naturally since 24 is already a multiple of 8, no manual padding needed.
        MemorySegment wndClass = ARENA.allocate(80);
        wndClass.set(ValueLayout.JAVA_INT, 0, 80);
        wndClass.set(ValueLayout.JAVA_INT, 4, 0);
        wndClass.set(ValueLayout.ADDRESS, 8, overlayTrampoline);
        wndClass.set(ValueLayout.JAVA_INT, 16, 0);
        wndClass.set(ValueLayout.JAVA_INT, 20, 0);
        wndClass.set(ValueLayout.ADDRESS, 24, overlayModuleHandle);
        wndClass.set(ValueLayout.ADDRESS, 32, MemorySegment.NULL);
        wndClass.set(ValueLayout.ADDRESS, 40, MemorySegment.NULL);
        wndClass.set(ValueLayout.ADDRESS, 48, MemorySegment.NULL);
        wndClass.set(ValueLayout.ADDRESS, 56, MemorySegment.NULL);
        wndClass.set(ValueLayout.ADDRESS, 64, ARENA.allocateFrom(SNAP_OVERLAY_CLASS_NAME, StandardCharsets.UTF_16LE));
        wndClass.set(ValueLayout.ADDRESS, 72, MemorySegment.NULL);
        if ((int) REGISTER_CLASS_EX.invoke(wndClass) == 0) {
            throw new IllegalStateException("RegisterClassExW failed for the snap overlay window class");
        }
        overlayClassRegistered = true;
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
                    DARK_TITLE_BAR_WINDOWS.remove(hwndAddr);
                    CUSTOM_TITLE_BAR_WINDOWS.remove(hwndAddr);
                    WINDOW_MENUS.remove(hwndAddr);
                    Long overlayAddr = SNAP_OVERLAYS.remove(hwndAddr);
                    if (overlayAddr != null) {
                        OVERLAY_OWNERS.remove(overlayAddr);
                        // The overlay is a child of this HWND, so DestroyWindow on the parent
                        // would already tear it down - this just avoids relying on that order.
                        try {
                            DESTROY_WINDOW.invoke(MemorySegment.ofAddress(overlayAddr));
                        } catch (Throwable ignored) {
                            // best-effort - the parent's own destruction cleans it up regardless
                        }
                    }
                    return result;
                }
                case WM_NCCALCSIZE -> {
                    if (wParam == 0 || !CUSTOM_TITLE_BAR_WINDOWS.contains(hwndAddr)) {
                        return callOriginal(originalProc, hwnd, msg, wParam, lParam);
                    }
                    // NCCALCSIZE_PARAMS.rgrc[0]: the proposed window rect on the way in, the
                    // computed client rect on the way out. RECT is {left, top, right, bottom}
                    // as 4 LONGs, so rgrc[0].top is byte offset 4.
                    MemorySegment params = MemorySegment.ofAddress(lParam).reinterpret(56);
                    int proposedTop = params.get(ValueLayout.JAVA_INT, 4);
                    // Let the default handler compute the normal client rect first (correct
                    // left/right/bottom resize-border insets, and - critically - the extra
                    // maximized-state inset that keeps a maximized window from overhanging the
                    // monitor edges) before overwriting just the top back to reclaim the caption.
                    callOriginal(originalProc, hwnd, msg, wParam, lParam);
                    if ((int) IS_ZOOMED.invoke(hwnd) != 0) {
                        int frameY = (int) GET_SYSTEM_METRICS.invoke(SM_CYSIZEFRAME)
                                + (int) GET_SYSTEM_METRICS.invoke(SM_CXPADDEDBORDER);
                        params.set(ValueLayout.JAVA_INT, 4, proposedTop + frameY);
                    } else {
                        params.set(ValueLayout.JAVA_INT, 4, proposedTop);
                    }
                    return 0;
                }
                case WM_SIZE -> {
                    int newWidth = (int) (lParam & 0xFFFF);
                    int newHeight = (int) ((lParam >> 16) & 0xFFFF);
                    // The host window is non-owned, so libwebview won't do this itself - keep
                    // the embedded WebView2 filling the client area as the window resizes.
                    window.onNativeResize(newWidth, newHeight);
                    window.fireResized(newWidth, newHeight);
                    // wParam is SIZE_MAXIMIZED (2) / SIZE_RESTORED (0).
                    long sizeType = wParam;
                    if (sizeType == 2 || sizeType == 0) {
                        window.fireMaximizedChanged(sizeType == 2);
                    }
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
                case WM_COMMAND -> {
                    int menuItemId = (int) (wParam & 0xFFFF);
                    Runnable action = menuItemAction(menuItemId);
                    if (action != null) {
                        // Off the UI thread: this runs directly inside the window's message
                        // dispatch, so running the action inline would block the whole app's
                        // message pump (freezing every window) for as long as it takes - e.g.
                        // a Dialogs.* call, which blocks until the user closes it.
                        Thread.ofVirtual().start(() -> {
                            try {
                                action.run();
                            } catch (Throwable t) {
                                System.err.println("[sugr] menu item action failed:");
                                t.printStackTrace();
                            }
                        });
                    }
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

    /**
     * WndProc for every snap overlay window created by {@link #createSnapOverlay} - a tiny,
     * fully transparent child window is the only reliable way to get {@code WM_NCHITTEST}
     * for a custom maximize button, since the WebView2 child covering the rest of the
     * client area answers it for itself first (see {@link #setCustomTitleBar}'s javadoc).
     * Follows the same "ask DWM first" order Microsoft's own custom-title-bar sample uses:
     * {@code DwmDefWindowProc} gets first look at every message (it owns the actual Snap
     * Layouts flyout - hover chevron, popup, keyboard nav); only once it declines does this
     * answer {@code WM_NCHITTEST} itself (unconditionally {@code HTMAXBUTTON} - the whole
     * overlay <em>is</em> the button, no coordinate math needed) or, for a plain click that
     * didn't go through the flyout, forward to {@link Window#toggleMaximizeFromOverlay}.
     */
    private static long onOverlayWndProc(MemorySegment hwnd, int msg, long wParam, long lParam) {
        try {
            MemorySegment dwmResult = ARENA.allocate(ValueLayout.JAVA_LONG);
            boolean dwmHandled = (int) DWM_DEF_WINDOW_PROC.invoke(hwnd, msg, wParam, lParam, dwmResult) != 0;
            if (msg == WM_NCHITTEST) {
                return dwmHandled ? dwmResult.get(ValueLayout.JAVA_LONG, 0) : HTMAXBUTTON;
            }
            if (dwmHandled) {
                return dwmResult.get(ValueLayout.JAVA_LONG, 0);
            }
            if (msg == WM_NCLBUTTONUP && wParam == HTMAXBUTTON) {
                Window owner = OVERLAY_OWNERS.get(hwnd.address());
                if (owner != null) {
                    owner.toggleMaximizeFromOverlay();
                }
                return 0;
            }
            return (long) DEF_WINDOW_PROC.invoke(hwnd, msg, wParam, lParam);
        } catch (Throwable t) {
            System.err.println("[sugr] snap overlay WndProc failed:");
            t.printStackTrace();
            return 0;
        }
    }

}
