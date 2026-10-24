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

/** Identity of the running app, from `sugr.config.json` and baked in at package time. */
export interface AppInfo {
  name: string
  /** e.g. `"1.0.3"`. A build that skipped the CLI reports `"0.0.0-dev"`. */
  version: string
  /** Bundle identifier, e.g. `"com.sugr.sql-client"`. */
  identifier: string
}

/** A release newer than the one running. */
export interface UpdateInfo {
  version: string
  currentVersion: string
  notes: string | null
  pubDate: string | null
  /** Download size in bytes; 0 when the manifest didn't say. */
  size: number
}

/** Download progress. `total` is -1 and `percent` 0 when the size isn't known up front. */
export interface UpdateProgress {
  downloaded: number
  total: number
  percent: number
}

/** Payload of {@link updater.onError}. `code` is one of a small stable set - see docs/guide/updates.md. */
export interface UpdateError {
  code: string
  message: string
}

/** Payload of {@link updater.onManualRequired} - an update exists but can't be applied in place. */
export interface UpdateManualRequired {
  version: string
  reason: string
  /** Where the user can download the release themselves; `null` for a managed install. */
  url: string | null
  /**
   * `true` for an administrator-deployed (per-machine) install: updates come
   * from IT, so show `reason` and don't send the user anywhere.
   */
  managed: boolean
}

/** Name/version/identifier of the running app. */
export const app = {
  info(): Promise<AppInfo> {
    return invoke<AppInfo>('sugr.app.info')
  },
}

const UPDATE_EVENTS = {
  available: 'sugr://update-available',
  progress: 'sugr://update-progress',
  downloaded: 'sugr://update-downloaded',
  error: 'sugr://update-error',
  manualRequired: 'sugr://update-manual-required',
} as const

function subscribe<T>(name: string, listener: (payload: T) => void): () => void {
  events.on<T>(name, listener)
  return () => events.off<T>(name, listener)
}

/**
 * Built-in app updates.
 *
 * With `"updater": { "endpoints": [...], "pubkey": "..." }` in `sugr.config.json`
 * the framework already checks on startup and offers a native prompt, so you
 * need none of this. Use it to build your own UI instead - set
 * `"prompt": false` to turn the built-in dialog off first.
 *
 * ```ts
 * const update = await updater.check()
 * if (update) {
 *   await updater.downloadAndInstall(p => setPercent(p.percent))
 * }
 * ```
 *
 * Every method rejects with `code: "UPDATER_DISABLED"` where updates aren't
 * available (under `sugr dev`, in an unpackaged build, or with no updater
 * configured) - check {@link isSupported} first if you want to hide the UI
 * entirely rather than handle the rejection.
 */
export const updater = {
  /** Whether this build can update itself at all. */
  isSupported(): Promise<boolean> {
    return invoke<boolean>('sugr.updater.isSupported')
  },

  /** The running version - the same value as `app.info().version`, without the round trip through the object. */
  currentVersion(): Promise<string> {
    return invoke<string>('sugr.updater.currentVersion')
  },

  /** Asks the configured endpoints for a newer release. Resolves `null` when already current. */
  check(): Promise<UpdateInfo | null> {
    return invoke<UpdateInfo | null>('sugr.updater.check')
  },

  /**
   * Downloads and verifies the release found by the last {@link check}.
   * Resolves once it's staged and ready for {@link install}.
   */
  download(onProgress?: (progress: UpdateProgress) => void): Promise<void> {
    return withProgress(onProgress, () => invoke<void>('sugr.updater.download'))
  },

  /**
   * Applies a downloaded update and restarts the app. Resolves once the update
   * is in place, moments before the process restarts; rejects when nothing has
   * been downloaded yet (see {@link isReadyToInstall}).
   */
  install(): Promise<void> {
    return invoke<void>('sugr.updater.install')
  },

  /** Whether an update has already been downloaded and is waiting to be installed. */
  isReadyToInstall(): Promise<boolean> {
    return invoke<boolean>('sugr.updater.isReadyToInstall')
  },

  /** {@link check}, {@link download} and {@link install} in one call. Resolves just before the restart, or right away when already current. */
  downloadAndInstall(onProgress?: (progress: UpdateProgress) => void): Promise<void> {
    return withProgress(onProgress, () => invoke<void>('sugr.updater.downloadAndInstall'))
  },

  /** Restarts the app, resolving just before it exits. Useful on its own for changes that only take effect at startup. */
  relaunch(): Promise<void> {
    return invoke<void>('sugr.updater.relaunch')
  },

  /** Fires when a check finds a newer release - including the automatic startup check. */
  onUpdateAvailable(listener: (info: UpdateInfo) => void): () => void {
    return subscribe(UPDATE_EVENTS.available, listener)
  },

  /** Fires repeatedly while an update downloads. */
  onProgress(listener: (progress: UpdateProgress) => void): () => void {
    return subscribe(UPDATE_EVENTS.progress, listener)
  },

  /** Fires once an update is verified and staged. */
  onDownloaded(listener: (info: UpdateInfo) => void): () => void {
    return subscribe(UPDATE_EVENTS.downloaded, listener)
  },

  /** Fires on any update failure. The app carries on running regardless. */
  onError(listener: (error: UpdateError) => void): () => void {
    return subscribe(UPDATE_EVENTS.error, listener)
  },

  /**
   * Fires when an update exists but this installation can't apply it - a
   * machine-wide install (`managed: true`, updated by IT), a `.deb` owned by
   * the package manager, or a version too old for the manifest's
   * `minVersion`. When `url` is set, send the user there.
   */
  onManualRequired(listener: (info: UpdateManualRequired) => void): () => void {
    return subscribe(UPDATE_EVENTS.manualRequired, listener)
  },
}

/**
 * Runs `action` with a temporary progress subscription. Subscribing only for
 * the duration of the call means a caller who passes a callback can't leak a
 * listener, and one who doesn't never pays for the events at all.
 */
async function withProgress(
  onProgress: ((progress: UpdateProgress) => void) | undefined,
  action: () => Promise<void>,
): Promise<void> {
  if (!onProgress) {
    return action()
  }
  const unsubscribe = subscribe<UpdateProgress>(UPDATE_EVENTS.progress, onProgress)
  try {
    return await action()
  } finally {
    unsubscribe()
  }
}
