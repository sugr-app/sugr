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
