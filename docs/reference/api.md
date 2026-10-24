# API reference

Hand-written for now (no javadoc/typedoc extraction pipeline yet) - kept in
sync manually with `core`, `bridge`, and `runtime-js`.

## Java: `com.sugr.core.Application`

```java
static Application.Builder builder()
```

### `Application.Builder`

| Method | |
|---|---|
| `title(String)` | Window title |
| `size(int width, int height)` | Window size |
| `frontend(Frontend)` | Where the web content comes from - see `Frontend` below |
| `bind(String method, Bridge.Handler handler)` | Registers a synchronous hand-written bind |
| `bindAsync(String method, Bridge.AsyncHandler handler)` | Like `bind`, for a `CompletableFuture<String>`-returning handler |
| `on(String event, Consumer<String> listener)` | Registers a Java-side listener for `events.emit()` from JS |
| `onReady(Consumer<Application> callback)` | Called once the window/bridge are wired, just before the event loop blocks - stash the `Application` handle here so you can call `emit()` later |
| `run()` | Creates the window and blocks on the event loop. Call from your main thread. |

### `Application` (instance)

| Method | |
|---|---|
| `emit(String event, String payloadJson)` | Sends an event to JS. Safe from any thread. |
| `version()` | This app's version, embedded at package time (`"0.0.0-dev"` for a build that skipped the CLI). |
| `name()` | This app's name from `sugr.config.json`. |
| `identifier()` | This app's bundle identifier, e.g. `"com.sugr.sql-client"`. |
| `updater()` | The `Updater` for this app, or `null` when updates aren't configured/available - see [Auto-updates](/guide/updates). |
| `relaunch()` | Spawns a fresh copy of the app and exits this process. |

### `com.sugr.core.Updater`

Returned by `Application.updater()`; also driven automatically by the
framework when `sugr.config.json`'s `updater.prompt` is `true` (the default).
See [Auto-updates](/guide/updates) for the full guide.

```java
CompletableFuture<UpdateInfo> check()       // null when already current
CompletableFuture<Void> download()          // downloads + verifies what check() found
void install()                              // applies the update and restarts - does not return
CompletableFuture<Void> downloadAndInstall()
boolean isReadyToInstall()
String currentVersion()
String channel()
```

`UpdateInfo` and `Progress` are records: `UpdateInfo(version, currentVersion,
notes, pubDate, size)`, `Progress(downloaded, total, percent)`.

## Java: `com.sugr.core.Frontend`

```java
static Frontend devServer(String url)
static Frontend embedded(String resourceRoot)
```

## Java: `com.sugr.bridge.Bridge`

Used internally by `Application` - most apps only touch it indirectly via
`Application.Builder.bind()`/`bindAsync()`.

```java
void register(String method, Bridge.Handler handler)
void registerAsync(String method, Bridge.AsyncHandler handler)
CompletableFuture<String> dispatch(String requestJson)
```

```java
interface Bridge.Handler { String handle(String paramsJson) throws Throwable; }
interface Bridge.AsyncHandler { CompletableFuture<String> handle(String paramsJson); }
```

## Java: `com.sugr.bridge.Json`

A minimal, hand-rolled JSON value model (sealed interface: `Str`, `Num`,
`Bool`, `Null`, `Arr`, `Obj`) - no Jackson databind.

```java
static Json of(String)     // and of(double), of(long), of(boolean)
static Json array(List<Json>)
static Json object(Map<String, Json>)
static Json parse(String text)

String encode()
String asString() / double asNumber() / boolean asBoolean() / boolean isNull()
List<Json> asArray()
Map<String, Json> asObject()

// convenience for hand-written binds:
static String quote(String s)                     // Json.of(s).encode()
static List<String> parseStringArray(String text)  // e.g. '["a","b"]' -> ["a","b"]
```

## Java: `com.sugr.bridge.Bind`

```java
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.SOURCE)
public @interface Bind {
    String value() default "";   // JS-visible method name; defaults to the Java method name
}
```

See [Binding Java to JS](/guide/bind) for supported parameter/return types
and what gets generated.

## TypeScript: `@sugr/runtime`

```ts
function invoke<T = unknown>(method: string, args?: unknown[]): Promise<T>

const events: {
  on<T = unknown>(name: string, listener: (payload: T) => void): void
  emit(name: string, payload?: unknown): void
}
```

Prefer the typed stub generated per class (e.g. `Backend.hello(...)`) over
calling `invoke()` directly - see [Binding Java to JS](/guide/bind).

### `app`

```ts
app.info(): Promise<{ name: string; version: string; identifier: string }>
```

### `updater`

Full guide: [Auto-updates](/guide/updates).

```ts
updater.isSupported(): Promise<boolean>
updater.currentVersion(): Promise<string>
updater.check(): Promise<UpdateInfo | null>
updater.download(onProgress?: (p: UpdateProgress) => void): Promise<void>
updater.install(): Promise<void>                 // does not resolve - restarts the app
updater.downloadAndInstall(onProgress?: (p: UpdateProgress) => void): Promise<void>
updater.isReadyToInstall(): Promise<boolean>
updater.relaunch(): Promise<void>

updater.onUpdateAvailable(listener: (info: UpdateInfo) => void): () => void
updater.onProgress(listener: (p: UpdateProgress) => void): () => void
updater.onDownloaded(listener: (info: UpdateInfo) => void): () => void
updater.onError(listener: (e: { code: string; message: string }) => void): () => void
updater.onManualRequired(listener: (i: { version: string; reason: string; url: string | null; managed: boolean }) => void): () => void
```

Every `on*` method returns an unsubscribe function. Rejects with `code:
"UPDATER_DISABLED"` when no `updater` block is configured, under `sugr dev`,
or in an unpackaged build.
