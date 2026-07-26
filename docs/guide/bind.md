# Binding Java to JS

There are two ways to expose a Java method to the frontend. Both go through
the same wire protocol (a single `invoke(method, params)` call, see
[How it works](/how-it-works)) and compose freely in the same app.

## `@Bind` (recommended)

Annotate a method, and the `processor` module generates a dispatcher (no
reflection - a direct method call) plus a typed TypeScript stub, at compile
time:

```java
public class Backend {
    @Bind
    public String hello(String name) {
        return "Hello, " + name + "!";
    }

    @Bind
    public CompletableFuture<List<Order>> ordersFor(String userId) {
        return orderService.fetchAsync(userId);
    }
}
```

The JS-visible method name defaults to the Java method name - pass a value
to `@Bind` to expose it under a different name instead (e.g. to avoid a
clash, or to keep the Java name more Java-idiomatic than the JS one):

```java
@Bind("pickFile")
public String pickDatabaseFile() {
    ...
}
```

```ts
await Backend.pickFile()   // not pickDatabaseFile
```

Compiling generates `BackendBridge.java` (in the same package) and
`Backend.generated.ts`. Wire it up:

```java
Backend backend = new Backend();
BackendBridge generatedBridge = new BackendBridge(backend);

Application.Builder builder = Application.builder()
    .title("My App")
    .frontend(Frontend.embedded("/frontend"));
generatedBridge.bindTo(builder);
builder.run();
```

```ts
import { Backend } from './generated/Backend.generated'

const greeting = await Backend.hello('world')      // fully typed
const orders = await Backend.ordersFor(userId)     // CompletableFuture<T> -> Promise<T>
```

Copy the generated `.ts` file from
`lib/build/generated/sources/annotationProcessor/java/main/...` into your
frontend's `src/generated/` after building - `sugr build`/`sugr dev` don't
automate that copy yet, so re-copy it whenever a `@Bind` method's signature
changes.

### Supported types

| Java | TS |
|---|---|
| `String` | `string` |
| `int` / `long` / `double` | `number` |
| `boolean` | `boolean` |
| a `record` (nested records too) | a generated `interface` |
| `List<T>` | `T[]` |
| `Map<String, T>` | `Record<string, T>` |
| `CompletableFuture<T>` as a return type | marks the binding async - `Promise<T>` |

Completing the future from a background thread works reliably - `core`'s
`Application` copies the webview request id to a Java `String` synchronously,
inside the invoke callback, before any async work happens (the native id
pointer is only valid for that callback's duration - see `Application.reply()`'s
javadoc).

## Hand-written binds

`@Bind` is an annotation on a method you own, resolved at *compile time* -
that's the shape it can't escape. Reach for `Application.Builder.bind()`/
`bindAsync()` directly only when that shape doesn't fit:

- **The method name isn't known until runtime** - e.g. a set of bindings
  driven by config or a plugin list, where `@Bind` (a fixed, compile-time
  annotation) can't apply.
- **You don't own the source** - exposing a third-party/library method you
  can't add `@Bind` to.

Side effects alongside the response (emitting an event, logging, etc.) are
**not** a reason to reach for this - an `@Bind` method is a normal Java
method body, free to do anything a hand-written handler can, including
calling `app.emit()` itself if it holds a reference to the running
`Application` (e.g. stashed from `onReady()`).

```java
.bind("runPlugin", params -> {
    String pluginName = Json.parseStringArray(params).get(0);
    Plugin plugin = pluginRegistry.get(pluginName);   // not known at compile time
    return plugin.run();
})
```

The handler receives the raw JSON params array as a string, and must return
a raw JSON result string - `bridge.Json` is the parse/encode helper (no
Jackson databind, per design).
