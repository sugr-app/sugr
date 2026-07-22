# Window & frontend

Everything starts with `Application.builder()`:

```java
Application.Builder builder = Application.builder()
    .title("My App")
    .size(900, 640)
    .frontend(Frontend.embedded("/frontend"));   // or Frontend.devServer("http://localhost:5173")

builder.run();  // blocks until the window closes
```

`builder.run()` creates the native webview and blocks the calling thread on
the event loop - call it from your app's main thread, and don't call it from
anywhere else. (See [How it works](/how-it-works) for why.)

## Multi-window (v0.3)

`Application.builder()...run()` creates and blocks on the app's first ("main")
window. For more windows, call `Application.openWindow` - from `onReady`, or
from any bind handler (both already run on the right thread):

```java
AtomicReference<Application> appRef = new AtomicReference<>();

Application.builder()
    .title("My App")
    .onReady(appRef::set)
    .bind("openSettings", params -> {
        appRef.get().openWindow(new Window.Builder()
            .title("Settings")
            .size(500, 400)
            .frontend(Frontend.embedded("/frontend")), // each window can point anywhere
            window -> System.out.println("settings window ready"));
        return "null";
    })
    .run();
```

`Window.Builder` mirrors `Application.Builder` (title/size/frontend/bind/on/...)
plus window chrome and lifecycle hooks - see below. Closing a secondary window
doesn't close the app; only closing the main window does (the app's event loop
only runs for the main window - all windows share the same underlying native
library, arena, and UI thread, but only the main window drives the loop).

`openWindow` takes an `onOpened` callback rather than returning the `Window`
directly, because creation is deferred to a later, non-reentrant tick of the
shared event loop - see the note below on why.

### Window chrome

```java
new Window.Builder()
    .resizable(false)
    .minSize(400, 300)
    .maxSize(1200, 900)
    .alwaysOnTop(true)
    .icon("assets/icon.ico")
```

`alwaysOnTop`/`icon` are Windows-only for now (no-ops elsewhere) - see
[How it works](/how-it-works) for why sugr's per-OS native features get
implemented incrementally rather than all at once.

### Window events

```java
new Window.Builder()
    .onCloseRequested(window -> Dialogs.confirm("Quit?", "Close this window?"))
    .onClosed(window -> System.out.println("closed"))
    .onFocus(window -> System.out.println("focused"))
    .onBlur(window -> System.out.println("blurred"))
    .onResize((window, w, h) -> System.out.println("resized to " + w + "x" + h))
```

`onCloseRequested` returning `false` vetoes the close - the window stays open
(useful for "are you sure?" prompts via [`Dialogs`](/guide/native)). Windows
only for now; elsewhere these callbacks never fire and closing can't be
vetoed.

::: warning Known limitation: secondary windows may briefly flicker on open
A window opened via `openWindow` can render with WebView2's control briefly
undersized before snapping to the right size, because WebView2's rendering is
asynchronous and sugr has no way to know exactly when it's actually painted -
only when its layout has been confirmed via JS (see `Window`'s source for
`markWebviewReady`). The main window never has this problem. Functionally
harmless - the window ends up the right size either way - but not
pixel-perfect yet.
:::

### Why `openWindow` is deferred

Both `webview_create()` and `webview_destroy()` pump their own nested Win32
message loop internally (waiting on WebView2's async environment/controller
creation, and its own cleanup respectively). Calling either one reentrantly -
from a call stack already nested inside another window's message dispatch,
which any bind handler always is - corrupted state badly enough to crash or
hang the whole process. `openWindow`'s creation (and a closed secondary
window's cleanup) run on a later tick of the shared message loop instead,
never nested inside another window's dispatch. See `WindowNative`'s source
for the full mechanism.

## Native menu bar & system tray (v0.4)

```java
Menu appMenu = new Menu()
    .submenu("File", new Menu()
        .item("Quit", () -> System.exit(0)))
    .submenu("Help", new Menu()
        .item("About", () -> Dialogs.showMessage("About", "My App")));

Application.builder()
    .title("My App")
    .menu(appMenu)
    .onReady(app -> {
        Menu trayMenu = new Menu()
            .item("Show main window", () -> app.mainWindow().emit("tray-show-clicked", "null"))
            .separator()
            .item("Quit", () -> System.exit(0));
        Tray tray = Tray.create("assets/icon.ico", "My App", trayMenu);
        if (tray != null) {
            Runtime.getRuntime().addShutdownHook(new Thread(tray::close));
        }
    })
    .run();
```

`Menu` builds a tree of `item`/`submenu`/`separator` entries, shared by both
a window's menu bar (`Window.Builder.menu()` / `Application.Builder.menu()`)
and `Tray.create()`'s right-click context menu.

::: warning Windows only, and no nested submenus in the tray menu
Both are no-ops (`Tray.create` returns `null`) elsewhere for now. The tray's
context menu only supports flat items and separators - a `Menu.Submenu`
passed to `Tray.create` is skipped with a warning; the window menu bar
supports full nesting.

The menu bar is bound directly via `user32.dll` (`CreateMenu`/`AppendMenu`/
`SetMenu`, the same FFM pattern as everything else in `WindowNative`).
`Tray` instead hosts a small persistent PowerShell + `NotifyIcon` process -
its native struct (`NOTIFYICONDATAW`) was judged too failure-prone to
hand-lay-out via FFM without extensive live byte-offset testing, the same
call made for `Dialogs`' `OPENFILENAMEW` (see [Native APIs](/guide/native)).

`Tray.close()` kills that hosting process outright rather than asking it to
dispose the icon first - Explorer usually clears the resulting "ghost" icon
on its next redraw/hover. Harmless, just not instant.
:::

## Frontend sources

`Frontend` is a sealed type with two variants:

```java
Frontend.devServer("http://localhost:5173")
```
Points the webview straight at a running Vite dev server. You get real HMR:
edit a `.tsx` file, see it update without a reload. This is what `sugr dev`
uses automatically (it sets the `SUGR_DEV_URL` environment variable, and a
generated app's `Main.java` reads it - see the template's `Main.java`).

```java
Frontend.embedded("/frontend")
```
Serves pre-built frontend assets from the classpath (`src/main/resources/frontend`)
over a small localhost HTTP server the `core` module starts and manages for
you. This is the production path - `sugr build` runs `pnpm build` and copies
`dist/` into that resources folder before building the Java side.

::: tip Why a localhost server instead of a custom scheme?
It's the pragmatic v0.1 choice - a custom `sugr://` scheme handler with a
tighter CSP is planned for a later milestone (v0.5). A loopback-only HTTP
server is a reasonable interim tradeoff.
:::

## Picking a source based on environment

The common pattern (see `examples/sql-client/lib/.../Main.java`):

```java
.frontend(System.getenv("SUGR_DEV_URL") != null
    ? Frontend.devServer(System.getenv("SUGR_DEV_URL"))
    : Frontend.embedded("/frontend"))
```

`sugr dev` sets `SUGR_DEV_URL`; a plain `gradle run` (or the packaged binary)
won't have it set, so it falls back to the embedded, production path.
