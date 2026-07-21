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
javadoc). See `examples/sql-client`'s `SqlBridge.slowGreet` for a real
background-thread example.

## Hand-written binds

Use `Application.Builder.bind()` directly when you need something codegen
doesn't know how to express - most commonly, a side effect alongside the
response (like emitting an event):

```java
.bind("query", params -> {
    List<String> args = Json.parseStringArray(params);
    List<Row> rows = db.query(args.get(0));
    app.emit("query-executed", Json.quote(rows.size() + " row(s)"));   // side effect
    return rowsToJson(rows);
})
```

The handler receives the raw JSON params array as a string, and must return
a raw JSON result string - `bridge.Json` is the parse/encode helper (no
Jackson databind, per design). See `examples/sql-client`'s `Main.java` for a
complete example that mixes both styles.
