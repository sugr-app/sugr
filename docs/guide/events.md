# Events

`@Bind`/`bind()` are request-response: JS calls, Java replies once. Events
are a separate, fire-and-forget channel for the other shape of communication
- Java pushing updates, or JS notifying Java without expecting data back.

## Java → JS

### `@Emits` (recommended)

Hand-written `emit("some-event", ...)` calls only match their `events.on('some-event', ...)`
counterpart by convention - nothing catches a typo or a payload-type mismatch
between the two sides. `@Emits` fixes that the same way `@Bind` does for the
other direction: declare the event as a method on an interface, and the
`processor` module generates a real emitter class plus a typed TS stub at
compile time.

```java
interface AppEvents {
    @Emits
    void queryExecuted(String summary);
}
```

Compiling generates `AppEventsEmitter.java` (in the same package) and
`AppEvents.generated.ts`. Wire it up wherever you'd otherwise call
`window.emit(...)` by hand:

```java
new AppEventsEmitter(app.mainWindow()).queryExecuted(rowCount + " row(s)");
```

```ts
import { AppEvents } from './generated/AppEvents.generated'

useEffect(() => AppEvents.onQueryExecuted((summary) => console.log(summary)), [])
```

`on<Name>()` returns an unsubscribe function directly, so it drops straight
into a `useEffect` cleanup. The JS-visible event name defaults to the Java
method name - pass a value to `@Emits` to override it, same as `@Bind`.
`@Emits` methods take at most one parameter (the payload; bundle multiple
values into a record) and must return `void`. Same [supported types](/guide/bind#supported-types)
as `@Bind`. As with `@Bind`'s generated `.ts`, copy it into your frontend's
`src/generated/` after building - re-copy whenever an `@Emits` method's
signature changes.

### Hand-written emits

Reach for `Window.emit()`/`Application.emit()` directly only when `@Emits`'s
"declare it as a method at compile time" shape doesn't fit - e.g. an event
name that isn't known until runtime.

```java
AtomicReference<Application> appRef = new AtomicReference<>();

Application.builder()
    .bind("query", params -> {
        // ... run the query ...
        appRef.get().emit("query-executed", Json.quote(rowCount + " row(s)"));
        return result;
    })
    .onReady(appRef::set)   // the Application instance only exists once the builder finishes
    .run();
```

```ts
import { events } from '@sugr/runtime'

events.on<string>('query-executed', (payload) => console.log(payload))
```

`Application.emit()`/`Window.emit()` (what `@Emits`-generated code calls
under the hood) are safe to call from **any thread**, including a background
thread - they always hop onto the UI thread via `webview_dispatch` before
touching the webview, same as replying to a bind/invoke call (see
[How it works](/how-it-works#thread-model)).

## JS → Java

```ts
events.emit('ping', { from: 'react', at: Date.now() })
```

```java
.on("ping", payload -> {
    // payload is the raw JSON text of whatever was passed to events.emit()
    appRef.get().emit("pong", Json.quote("got: " + payload));
})
```

## Why a separate channel from bind/invoke?

Events don't have a result the caller is waiting on - `events.emit()` on the
JS side doesn't return a Promise you'd await, and there's no equivalent of
`webview_return` involved. Modeling it separately (rather than overloading
`invoke()`) keeps the request-response and fire-and-forget cases from
tangling with each other's error handling and typing.
