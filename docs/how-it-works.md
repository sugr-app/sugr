# How it works

sugr has two technical problems to solve, and everything else builds on top:
**calling native C functions from Java without JNI**, and **compiling the
whole thing to a small standalone binary**.

## FFM instead of JNI

sugr's `core` module binds directly to the OS's native webview library
(`libwebview` - itself a thin wrapper over WebView2/WKWebView/WebKitGTK)
using the [Foreign Function & Memory API](https://openjdk.org/jeps/454)
(`java.lang.foreign`), not JNI. No C glue code to compile per platform -
`Linker.downcallHandle()` binds directly to exported C functions
(`webview_create`, `webview_navigate`, `webview_bind`, ...), and
`Linker.upcallStub()` lets native code call back into Java (used for the
`invoke`/`emit` bindings, and for `sqlite3_exec`'s row callback in the
`examples/sql-client` app).

## The bridge protocol

JS never calls arbitrary native functions - there's exactly one entry point,
`window.invoke(method, paramsJson)`, injected by a single `webview_bind` call
in `Application.run()`. Every `@Bind`-generated or hand-written binding goes
through it:

```
JS: Backend.hello("world")
 -> invoke("hello", ["world"])                          (@sugr/runtime)
 -> window.invoke("hello", '["world"]')                 (raw call)
 -> native req: ["hello", "[\"world\"]"]                (webview's own JS glue wraps all args)
 -> Bridge.dispatch(reqJson)                             (bridge module - Java)
   -> looks up the "hello" handler, calls it, gets a JSON result string
 -> webview_return(handle, seq, isError, resultJson)
 -> JS Promise resolves/rejects
```

`Bridge` doesn't know about webview at all - `Application` (in `core`) owns
the FFM calls and the JSON-RPC-shaped envelope; `Bridge` just routes a method
name to a handler and returns JSON text. This is why hand-written `bind()`
calls and `@Bind`-generated ones can freely mix: they're both just entries in
the same `Bridge` handler map.

Events (`events.emit()`/`events.on()`) are a second, parallel channel -
`webview_bind("emit", ...)` for JS→Java, and a JS-side `__sugrEvents__`
dispatcher (injected once via `webview_init`, before any page loads) for
Java→JS, driven by `Application.emit()` calling `webview_eval`.

## Thread model

`webview_create` + `webview_run` must run on the same thread (the OS's UI
thread - on macOS specifically the *main* thread). Any native call from a
different thread has to go through `webview_dispatch`, which schedules a
callback back onto the UI thread. `Application.emit()` always does this,
which is why it's safe to call from anywhere. Bind/invoke replies take a
faster path when they resolve synchronously (the common case), and only fall
back to `webview_dispatch` for genuinely async replies (see the
[CompletableFuture caveat](/guide/bind#supported-types) for a case where that
fallback path doesn't actually work with the current bundled webview.h).

## No reflection at the bind boundary

`@Bind` methods are dispatched via a **generated** switch-shaped class - the
`processor` module reads your method's signature at compile time (via
`javax.annotation.processing`, not runtime reflection) and emits direct calls
(`target.hello(arg0)`), plus JSON encode/decode calls into `bridge.Json` for
whatever the parameter/return types need (records, `List`, `Map`,
`CompletableFuture`). Nothing about the bind path needs a
`reachability-metadata.json` entry for native-image - the FFM downcalls in
`core` still do (that's inherent to FFM, not something `@Bind` can remove),
but you generate that once with `native-image-agent` and it doesn't grow as
you add more `@Bind` methods.

## Native-image

GraalVM's `native-image` does closed-world, whole-program static analysis
and compiles straight to a native binary - no JVM at runtime, no JIT
warm-up, much lower baseline memory than running the same app on a JVM (this
is also *why* native-image builds take longer than a `javac` compile and why
`sugr dev` intentionally runs on a plain JVM rather than rebuilding a native
image on every save - see [Building & packaging](/guide/build)).
