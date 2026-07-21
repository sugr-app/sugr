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

## v0.1 scope: one window

Right now `Application` manages exactly one window. Multi-window, system
tray, and native menus are planned for later (v0.3-v0.4) - not in scope yet.

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
