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

## Native binary

Not automated by `sugr build` yet - build it manually, and only on Windows
today needs a **Developer Command Prompt** (native-image's C compiler step
needs `cl.exe`/`link.exe` on PATH, which a plain terminal doesn't have):

```sh
# from a Developer Command Prompt / after running vcvarsall.bat on Windows
native-image -cp "lib/build/classes/java/main;lib/build/resources/main;<core.jar>;<bridge.jar>" \
  <your.Main.class> -o my-app --no-fallback -Os --gc=serial -march=compatibility
```

Before your first native-image build, run the app once with
`-agentlib:native-image-agent=config-output-dir=...` to generate
`reachability-metadata.json` for your FFM downcalls/upcalls - `@Bind`-generated
dispatchers don't need this (no reflection by construction), but the FFM
webview/native-library bindings in `core` do. See `examples/sql-client`'s
`META-INF/native-image/` directory for a generated example.

The `cli` module *is* wired up with the official
[GraalVM native-image Gradle plugin](https://graalvm.github.io/native-build-tools/latest/gradle-plugin.html),
so building the CLI itself natively is one command (same Developer Command
Prompt requirement on Windows):

```sh
./gradlew :cli:nativeCompile
```

## What's not automated yet

- Native-image compilation inside `sugr build` (see above - manual for now)
- `sugr package` (`.msi`/`.dmg`/`.deb` installers)
- Bundling a WebView2 runtime bootstrapper for Windows 10 machines that don't have it preinstalled
