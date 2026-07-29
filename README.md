# Sugr

> Sugar for your cup of Java — build lightweight desktop apps with Java and web technologies.

Sugr is a desktop app framework that pairs a Java backend with a React (or any web) frontend rendered in the OS-native webview, and ships as a single ~20–30MB binary via GraalVM native-image.

## Status

🚧 **v0.1 done, v0.2-v0.4 done, working past them** (see [plan.md](plan.md)
for the full phased roadmap). `core`, `bridge`, `processor`, `cli`, and
`@sugr/runtime` exist as a Gradle + pnpm monorepo.
[`examples/sql-client`](examples/sql-client) runs entirely on the public
API: most methods are exposed with a single `@Bind` annotation (no
hand-written JSON), one method stays hand-written to show it composes with
generated bindings, `@Emits` pushes Java→JS events from native menu/shortcut
actions, and the two-way event bus works end to end.
`sugr init/dev/debug/build/package/doctor` automate the day-to-day workflow
(see [CLI](#cli) below and [`docs/`](docs)) - `sugr package` builds a
native-image binary and wraps it into a real OS installer (NSIS on Windows,
tested end to end; macOS/Linux follow the same shape but are unverified so
far). Per-environment config (`dev`/`staging`/`prod`, both backend and
frontend) is wired through `--env` on `dev`/`debug`/`build`/`package`.

Native OS integration beyond the bridge itself: dialogs, clipboard,
notifications, multi-window + window events, system tray, native menus,
global keyboard shortcuts, and launch-at-login autostart - see
[Native dialogs, clipboard, notifications & autostart](docs/guide/native.md),
[Window & frontend](docs/guide/window.md), and
[Global shortcuts](docs/guide/shortcuts.md).

The `sugr` CLI itself is installable today as a prebuilt binary via
[GitHub Releases](#cli) - `core`/`bridge`/`processor` (Maven Central) and
`@sugr/runtime` (npm) are still workspace-only, so `sugr init` can only
scaffold an app *inside this checkout* for now (planned for later, see
plan.md).

📖 Full docs: run `pnpm --filter sugr-docs dev` and open the printed
localhost URL (no hosted docs site yet).

## Why

- **Small binaries.** Native-image + system webview keep the final app compact
  (the sql-client example native binary is ~18MB).
- **Familiar stack.** Java backend, React (or your framework of choice) frontend.
- **Native feel.** Uses the OS's built-in webview (WebView2 / WKWebView / WebKitGTK).

## Usage (current API)

The recommended path: annotate methods with `@Bind`, the `processor` module
generates a dispatcher (no reflection) and a typed TS stub at compile time.

```java
public class Backend {
    @Bind
    public User getUser(String id) { ... }

    @Bind
    public CompletableFuture<List<Order>> ordersFor(String userId) { ... }
}
```

```java
// Generated: BackendBridge (Java) + Backend.generated.ts
Application.Builder builder = Application.builder()
    .title("My App")
    .frontend(Frontend.embedded("/frontend"));
new BackendBridge(new Backend()).bindTo(builder);
builder.run();
```

```ts
import { Backend } from "./generated/Backend.generated"

const user = await Backend.getUser("1")          // fully typed, no invoke() call written by hand
const orders = await Backend.ordersFor(user.id)   // CompletableFuture<T> -> Promise<T>
```

Supported types (nested where it makes sense): `String`, `int`/`long`/`double`/`boolean`,
records, `List<T>`, `Map<String,T>`, and `CompletableFuture<T>` as a return type marks
the binding async. See [`processor`](processor) for how types are matched to codegen,
and [`examples/sql-client`](examples/sql-client)'s `SqlBridge.java` for a real example,
including completing a `CompletableFuture` from a background thread.

You can still bind by hand when you need a side effect codegen doesn't know about
(e.g. emitting an event alongside the response) - both styles compose freely:

```java
AtomicReference<Application> appRef = new AtomicReference<>();

Application.builder()
    .bind("getUser", params -> {
        List<String> args = MiniJson.parseStringArray(params);
        return MiniJson.quote(myService.getUser(args.get(0)));
    })
    .on("ping", payload -> appRef.get().emit("pong", MiniJson.quote("hi from Java")))
    .onReady(appRef::set)   // stash the Application handle so bind()/on() callbacks can call emit()
    .run();
```

```ts
import { invoke, events } from "@sugr/runtime"

const user = await invoke<User>("getUser", ["1"])
events.on<string>("pong", (payload) => console.log(payload))
events.emit("ping")
```

Every bound method (hand-written or generated) goes through a single
`invoke(method, params)` JS-side call, dispatched by `Bridge` on the Java
side (see [`bridge`](bridge)) - adding a new bound method never touches the
FFM/webview layer in `core`. Events are a separate, fire-and-forget channel
(`Application.emit()` / `.on()` in Java, `events.emit()` / `events.on()` in
JS) - `emit()` is safe to call from any thread, it always hops onto the UI
thread via `webview_dispatch` first. JSON is hand-rolled (`bridge.Json`) - no
Jackson databind, per plan.md.

See [`examples/sql-client`](examples/sql-client) for a full working app:
`@Bind`-generated methods, a hand-written one, both directions of the event
bus, and an async `CompletableFuture` binding completed from a background
thread.

## CLI

Install the prebuilt native binary (no JVM/Gradle/GraalVM needed - macOS is
Apple Silicon only for now, see [`install.sh`](install.sh) for the full
platform list):

```sh
# macOS / Linux
curl -fsSL https://raw.githubusercontent.com/sugr-app/sugr/main/install.sh | sh
```

```powershell
# Windows
irm https://raw.githubusercontent.com/sugr-app/sugr/main/install.ps1 | iex
```

This downloads the latest [GitHub Release](https://github.com/sugr-app/sugr/releases)
asset built by [`.github/workflows/release.yml`](.github/workflows/release.yml)
(triggered by pushing a `v*` tag) into `~/.sugr/bin` (`%LOCALAPPDATA%\sugr\bin`
on Windows) and adds it to your PATH.

Or build it yourself from a checkout (needs the tools `sugr doctor` checks
for - GraalVM, Node, pnpm):

```
./gradlew :cli:installDist
```

The resulting `cli/build/install/sugr/bin/sugr(.bat)` (or the downloaded
binary above) supports:

| Command | What it does |
|---|---|
| `sugr doctor` | Checks Java/GraalVM, native-image, Node, pnpm, Gradle, WebView2 (Windows) |
| `sugr init <name>` | Scaffolds the [`templates/react-ts`](templates/react-ts) template into `examples/<name>` of the current checkout and registers it in settings.gradle.kts |
| `sugr dev` | Runs the Vite dev server and the Java app together - Vite gives the frontend HMR, and `sugr dev` itself watches `lib/src/**/*.java` and rebuilds+restarts the app on change. Auto-detects the right Gradle task/root by walking up to the nearest settings.gradle.kts |
| `sugr debug` | Like `dev`, but with backend (JDWP) and/or frontend (CDP) debugging enabled - attach a real IDE debugger to either side |
| `sugr build` | Builds the frontend, embeds it into resources, then runs the Gradle build (JVM-based, not native-image) |
| `sugr package` | Builds a native-image binary and wraps it into an OS installer - NSIS (`.exe`) on Windows, tested end to end; `.dmg`/`.deb` on macOS/Linux follow the same shape but are unverified so far |

`dev`/`build` auto-detection covers the common case (run them from the app's
directory); pass `--task`/`--gradle-dir` explicitly if it guesses wrong.
`dev`/`debug`/`build`/`package` all accept `--env <name>` to pick which
`.env.<name>` (frontend) and `application-<name>.properties` (backend) to
build against - defaults to `dev` for `dev`/`debug`, `prod` for `build`/
`package` (see [Environments](docs/guide/environments.md)).
`sugr init` is scoped to scaffolding *inside* this checkout for now - since
nothing is published, a scaffolded app can only depend on `core`/`bridge` as
sibling Gradle projects in the same build. On Windows, `sugr package` locates
Visual Studio and sources its `vcvars64.bat` itself before invoking
native-image, so there's no manual "Developer Command Prompt" step (see
[`.github/workflows/ci.yml`](.github/workflows/ci.yml) for the CLI itself
building natively cross-platform in CI, via the Gradle
[native-image plugin](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html)).

## Roadmap

See [plan.md](plan.md) for the detailed, phased roadmap (foundations → PoC app → library extraction → developer experience → v0.1 launch → v1.0).

## License

[MIT](LICENSE)
