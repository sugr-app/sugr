# Building & packaging

## Development

```sh
sugr dev
```

Runs the Vite dev server and the Java app together, restarting the Java
side automatically when a `.java` file under `lib/src` changes (the
frontend gets Vite's own HMR). Auto-detects the right Gradle task and root
by walking up from the current directory to the nearest `settings.gradle.kts`
- pass `--task`/`--gradle-dir` explicitly if it guesses wrong (e.g. a
non-standard `frontend/`+`lib/` layout).

See [Debugging](/guide/debug) for `sugr debug` - attaching a real IDE
debugger to the backend and/or frontend.

## Production build (JAR, no native-image yet)

```sh
sugr build
```

1. `pnpm build` in `frontend/`
2. Copies `frontend/dist/` into `lib/src/main/resources/frontend`
3. Runs the Gradle build

The result is a runnable JAR (via `gradle run`, or the `application` plugin's
distribution) that serves the embedded frontend - still needs a JVM
installed on the machine that runs it.

## Native binary + installer

```sh
sugr package
```

Builds a native-image binary (via Gradle's `nativeCompile` task - every app
generated from the `react-ts` template has the
[GraalVM native-image Gradle plugin](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html)
wired up already) and wraps it directly into an OS installer - no JVM
involved at all in the final artifact. On Windows this is genuinely one
command with nothing to set up first: `sugr package` locates your Visual
Studio install and sources its `vcvars64.bat` itself (native-image's C
compiler step needs `cl.exe`/`link.exe` on PATH, which a plain terminal
doesn't have - this used to mean manually opening a "Developer Command
Prompt for VS" before building), then wraps the resulting `.exe` with
[NSIS](https://nsis.sourceforge.io/) into a proper installer (Start Menu +
desktop shortcuts, uninstaller, and a check that silently installs the
WebView2 Runtime if the target machine doesn't have it yet). macOS (`.dmg`
via `hdiutil`) and Linux (`.deb` via `dpkg-deb`) follow the same shape in the
code but haven't been exercised on those OSes yet.

```
sugr package [--name <name>] [--app-version <version>] [--icon <path>]
             [--lib-dir lib] [--dest dist]
```

`--name`/`--app-version`/`--icon` default to a `sugr.config.json` in your
app's root directory (alongside `frontend/` and `lib/`), if present:

```json
{
  "name": "my-app",
  "appVersion": "1.0.0",
  "icon": "assets/icon.ico"
}
```

CLI flags always override the file; both are optional (falls back to
`sugr-app`/`0.1.0`/no icon). The `react-ts` template ships one with
`"name"` pre-filled from `sugr init`'s app name.

If your app calls into a native library of its own beyond `core`'s webview
binding (like `examples/sql-client` does with `libsqlite3`), you'll need to
(re-)generate FFM reachability metadata once: run the app on a plain JVM with
`-agentlib:native-image-agent=config-output-dir=...` attached, exercise every
code path that makes a native call, close the app normally (not force-killed
- the agent only writes its config on a graceful JVM shutdown), then copy the
resulting `reachability-metadata.json` into your module's
`src/main/resources/META-INF/native-image/<any>/<any>/`. The `react-ts`
template already ships the metadata for `core`'s own webview bindings, so a
fresh `sugr init` app can run `sugr package` immediately - this step is only
needed once you add your own native library.

## What's not automated yet

- Publishing `core`/`bridge`/`processor` to Maven Central or `@sugr/runtime` to npm
