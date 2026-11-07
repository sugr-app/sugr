# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.2.0] - 2026-11-07

### Added
- **Signed in-app auto-updates** (#14, PR #43): `Updater` API in Java and `updater` client in `@sugr/runtime`, Ed25519 key generation via `sugr keygen`, signed update manifests in `sugr package`, Windows Registry `DisableUpdates` policy support, per-machine (`Program Files`) detection, and system proxy support (`SystemProxy`).
- **Window state controls & persistence** (#3, #6, #7, #8, #9, PR #34, #38, #40, #41): `minimize()`, `maximize()`, `restore()`, `isMaximized()`, `isMinimized()`, `setFullscreen(boolean)`, `setAlwaysOnTop(boolean)`, `minimizeToTray()`, `hide()`, `show()`, and automatic position/size/maximized persistence across restarts via `restoreWindowState(true)`.
- **Custom & dark title bars with Windows 11 Snap Layouts** (#35, PR #37): `darkTitleBar()`, `customTitleBar()`, `startDrag()`, and a native transparent `HTMAXBUTTON` overlay (`reportMaxButtonBounds`) so custom HTML title bars retain the real Windows 11 Snap Layouts flyout.
- **System theme detection & live sync** (#5): `SystemTheme.current()` (`LIGHT` / `DARK`), `followSystemTheme()` on `Window.Builder` / `Application.Builder`, `onThemeChanged(...)` callback driven by `WM_SETTINGCHANGE`, and `app.theme()` / `app.onThemeChanged()` in `@sugr/runtime`.
- **Standard OS app directories** (#20): Public `AppPaths.dataDir()`, `AppPaths.cacheDir()`, and `AppPaths.logDir()` APIs in `core`, plus `app.paths()` in `@sugr/runtime`.
- **OS shell operations** (#24): `Shell.openUrl(String)` (strictly allowlisted to `http`, `https`, `mailto`) and `Shell.openPath(Path)` to open folders/files in the system file manager.
- **File picker dialog enhancements** (#16): Multi-select file dialog (`Dialogs.openFiles`), folder picker (`Dialogs.openFolder`), and named file-type filters (`Dialogs.FileFilter`).
- **WebView DevTools control** (#12, PR #46): `devTools(boolean)` on `Window.Builder` / `Application.Builder` and `SUGR_DEVTOOLS=1/0` environment override (enabled by default in `dev`, disabled in packaged builds).
- **Bridge & asset server hardening** (#15 partial, PR #46, #47): `OriginPolicy` (`allowOrigin(...)`) rejecting bridge calls from foreign origins, strict depth-limited `Json` parser, crash-safe FFM upcalls with per-call confined arenas, and hardened `AssetServer`.

### Fixed
- `sugr dev` exits cleanly when the app window closes or on `Ctrl+C`, and logs build/startup timings (PR #39, #45).
- Windows system tray icon is cleaned up on exit so no ghost icon lingers in the notification area (PR #40).
- Window creation is hidden until the first frontend layout paint completes, eliminating the white startup flash (PR #39).

## [0.1.0] - 2026-07-29

### Added
- Initial release of `sugr`: Java FFM bindings for `libwebview` (`core`), JSON-RPC bridge & two-way event bus (`bridge`), compile-time `@Bind` / `@Emits` annotation processor (`processor`), `@sugr/runtime` TypeScript package, `sugr` CLI (`init`, `dev`, `debug`, `build`, `package`, `doctor`), React+TS template, and `examples/sql-client`.
