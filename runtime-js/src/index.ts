/**
 * @sugr/runtime - the JS half of the sugr bridge.
 *
 * `window.invoke`, `window.emit`, and `window.__sugrEvents__` are injected
 * by the Java side (core module, Application.java) via webview_bind /
 * webview_init - they only exist once the app is actually running inside a
 * sugr window, not in a regular browser tab.
 */

declare global {
  interface Window {
    invoke(method: string, paramsJson: string): Promise<unknown>
    emit(event: string, payloadJson: string): Promise<unknown>
    __sugrEvents__: {
      on(name: string, cb: (payload: unknown) => void): void
      off(name: string, cb: (payload: unknown) => void): void
    }
  }
}

/**
 * Shape of a rejected `invoke()`/`@Bind` call - matches `com.sugr.bridge.BridgeException.toJson()`.
 * `code` is a short, stable string (e.g. `"METHOD_NOT_FOUND"`, or whatever a
 * handler's own `BridgeException(code, message)` used) - check it instead of
 * parsing `message` text to distinguish error kinds, e.g. a cancelled native
 * dialog vs. an actual failure.
 */
export interface BridgeError {
  code: string
  message: string
  data?: unknown
}

/**
 * Calls a Java-side method registered via `Application.builder().bind(method, ...)`
 * or generated from `@Bind` (see `com.sugr.bridge.Json` in the `bridge` module -
 * args are JSON-encoded positionally, so strings/numbers/booleans/objects/arrays
 * all work). Prefer the typed stub generated per class (e.g. `SqlBridge.connect(...)`)
 * over calling this directly. Rejects with a {@link BridgeError}.
 */
export async function invoke<T = unknown>(method: string, args: unknown[] = []): Promise<T> {
  return window.invoke(method, JSON.stringify(args)) as Promise<T>
}

/**
 * Two-way event bus. `on()` receives events pushed from Java via
 * `Application.emit()`. `emit()` sends an event to Java-side listeners
 * registered via `Application.builder().on(event, ...)`. Always pair `on()`
 * with `off()` in a `useEffect` cleanup (or equivalent) - without it, a
 * component that mounts more than once (e.g. React StrictMode's intentional
 * double-invoke in development) registers the same listener repeatedly, and
 * every event fires it once per registration.
 */
export const events = {
  on<T = unknown>(name: string, listener: (payload: T) => void): void {
    window.__sugrEvents__.on(name, listener as (payload: unknown) => void)
  },

  off<T = unknown>(name: string, listener: (payload: T) => void): void {
    window.__sugrEvents__.off(name, listener as (payload: unknown) => void)
  },

  emit(name: string, payload?: unknown): void {
    void window.emit(name, JSON.stringify(payload ?? null))
  },
}
