# Debugging

```sh
sugr debug
```

Same loop as `sugr dev` (Vite + watch/restart), with backend and frontend
debugging enabled independently - both land you on a real breakpoint in the
app's own window.

::: warning VS Code only, for now
`sugr debug` auto-generates debug configs for **VS Code** only
(`.vscode/launch.json`, see below) - that's the only IDE it wires up today.
The underlying mechanisms (JDWP on port `5005`, CDP on port `9222`) aren't
VS Code-specific, so attaching manually from another IDE or tool works too
(e.g. IntelliJ's "Remote JVM Debug" for the backend, `chrome://inspect` or
any CDP-compatible tool for the frontend) - you just have to set that up
yourself; there's no auto-generated JetBrains/Neovim config yet.
:::

- **Backend**: appends Gradle's built-in `--debug-jvm` to the app launch, so
  the JVM suspends and waits for a debugger on port `5005` every time it
  (re)starts.
- **Frontend**: sets `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS` (Windows/WebView2)
  or `WEBKIT_INSPECTOR_SERVER` (Linux/WebKitGTK, unverified like the rest of
  the Linux webview path) so the app's *own* webview exposes a Chrome
  DevTools Protocol endpoint on port `9222` - not a separate browser tab, the
  actual window the user sees, bridge calls (`invoke()`/`events`) included.
  macOS's WKWebView has no such env var; use Safari's Develop menu there for
  now.

The first time you run it, `sugr debug` writes `.vscode/launch.json` in the
current directory with two `"attach"` configs - `sugr: attach backend` (Java,
port 5005) and `sugr: attach frontend` (`msedge`, port 9222, built into VS
Code's bundled JS debugger, no extra extension needed). It only ever adds
these two entries by name, leaving the rest of an existing `launch.json`
untouched. Open the Run & Debug panel, pick one (or both, as separate debug
sessions), set a breakpoint in your Java or `.tsx` source, and it'll actually
hit - the backend one every time the JVM (re)starts (suspend=y), the frontend
one as soon as you attach.

Turn either side off independently:

```sh
sugr debug --no-backend    # frontend debugging only, backend just watches
sugr debug --no-frontend   # backend debugging only, no CDP port
```

::: warning With both sides on, attach the backend first
The webview is created from inside `main()`, and with backend debugging on,
`main()` never runs until *something* attaches on port `5005` (that's what
`suspend=y` means). So if you leave both sides enabled, attaching
`sugr: attach frontend` first fails with "could not find any debuggable
target" - the window hasn't opened yet, so there's nothing on port `9222` to
attach to. Attach `sugr: attach backend` first (even if you don't need a Java
breakpoint yet) to let it past that point, then attach the frontend. Running
with `--no-backend` sidesteps this entirely - the JVM starts immediately.
:::
