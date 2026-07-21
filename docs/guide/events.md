# Events

`@Bind`/`bind()` are request-response: JS calls, Java replies once. Events
are a separate, fire-and-forget channel for the other shape of communication
- Java pushing updates, or JS notifying Java without expecting data back.

## Java → JS

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

`Application.emit()` is safe to call from **any thread**, including a
background thread - it always hops onto the UI thread via `webview_dispatch`
before touching the webview, unlike replying to a bind/invoke call (see the
`@Bind` CompletableFuture caveat).

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
