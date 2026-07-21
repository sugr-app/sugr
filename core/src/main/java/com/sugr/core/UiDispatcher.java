package com.sugr.core;

import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Runs a task on the webview's UI thread, whichever thread calls
 * {@link #runOnUiThread}. If already on the UI thread, runs inline; otherwise
 * queues the task and hops over via {@code webview_dispatch}. Used by
 * {@link Application#emit} (always cross-thread-safe) and {@link Application#reply}
 * (usually same-thread, since most handlers resolve inline).
 */
final class UiDispatcher {

    private final Thread uiThread = Thread.currentThread();
    private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
    private final MethodHandle webviewDispatch;
    private final MemorySegment webviewHandle;
    private final MemorySegment trampoline;

    UiDispatcher(NativeLibrary lib, MemorySegment webviewHandle) throws Throwable {
        this.webviewHandle = webviewHandle;
        this.webviewDispatch = lib.downcall("webview_dispatch",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));
        this.trampoline = lib.upcall(MethodHandles.lookup(), this, "onDispatch",
                MethodType.methodType(void.class, MemorySegment.class, MemorySegment.class),
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    }

    void runOnUiThread(Runnable task) throws Throwable {
        if (Thread.currentThread() == uiThread) {
            task.run();
            return;
        }
        queue.add(task);
        webviewDispatch.invoke(webviewHandle, trampoline, MemorySegment.NULL);
    }

    /** Upcall target for webview_dispatch; runs on the UI thread, drains queued work. */
    private void onDispatch(MemorySegment handleArg, MemorySegment argArg) {
        Runnable task;
        while ((task = queue.poll()) != null) {
            task.run();
        }
    }
}
