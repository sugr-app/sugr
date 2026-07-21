# Native dialogs & clipboard

`core.Dialogs` and `core.Clipboard` are plain Java utility classes - not
wired into `Application.Builder` or exposed to JS automatically. You call
them from your own `@Bind`/hand-written methods, the same as any other Java
code:

```java
import com.sugr.bridge.BridgeException;
import com.sugr.core.Clipboard;
import com.sugr.core.Dialogs;

@Bind
String pickFile() {
    String path = Dialogs.openFile("Choose a file", "png", "jpg");
    if (path == null) {
        throw new BridgeException("DIALOG_CANCELLED", "No file was selected");
    }
    return path;
}

@Bind
void copyResult(String text) {
    Clipboard.write(text);
}
```

```ts
import type { BridgeError } from '@sugr/runtime'

try {
  const path = await Backend.pickFile()
} catch (e) {
  const err = e as BridgeError
  if (err.code === 'DIALOG_CANCELLED') {
    // user just closed the dialog - not a real error
  } else {
    console.error(err.message)
  }
}
```

## API

```java
Dialogs.openFile(String title, String... extensions)   // -> picked path, or null if cancelled
Dialogs.saveFile(String title, String... extensions)    // -> picked path, or null if cancelled
Dialogs.showMessage(String title, String message)        // -> void

Clipboard.read()          // -> current clipboard text, or null
Clipboard.write(String text)
```

## How it's implemented

Rather than binding each OS's native dialog/clipboard API directly via FFM,
these shell out to a small script per OS:

| | Dialogs | Clipboard |
|---|---|---|
| Windows | PowerShell + `System.Windows.Forms` | PowerShell's `Get-Clipboard`/`Set-Clipboard` |
| macOS | `osascript` (AppleScript) | `pbcopy`/`pbpaste` |
| Linux | `zenity` | `xclip` (falls back to `xsel`) |

This is a deliberate trade-off, not a placeholder: Windows' native file
dialog API (`comdlg32`'s `GetOpenFileNameW`/`GetSaveFileNameW`) takes a
pointer to a ~150-byte `OPENFILENAMEW` struct, and getting every field's
offset and alignment exactly right isn't something that can be verified
without a way to test each byte - unlike `core`'s other FFM bindings
(webview, sqlite3), which were checked against a running app end to end. A
wrong struct layout corrupts memory rather than failing loudly, so a slower
process-spawn per call is the safer trade for something a user clicks
occasionally, not a hot path.

::: warning Verified on Windows only
The Windows path (dialogs and clipboard) has been exercised end to end in a
real running app. macOS (`osascript`/`pbcopy`/`pbpaste`) and Linux
(`zenity`/`xclip`/`xsel`) follow the same shape using each platform's
standard, well-documented tools, but haven't been run on those OSes yet.
:::

## Structured bridge errors

`BridgeException` carries a `code` (short, stable string) alongside the
message, and an optional JSON `data` payload:

```java
throw new BridgeException("NOT_FOUND", "No such record", Json.of(recordId));
```

It crosses the wire as `{"code": ..., "message": ..., "data": ...}` (`data`
omitted when null) - JS gets this shape on `.catch()`, typed as
`BridgeError` from `@sugr/runtime`. `Bridge`'s own built-in failures use
`"BAD_REQUEST"` (malformed `invoke()` call) and `"METHOD_NOT_FOUND"` (no
handler for that method); anything else that throws inside a handler and
isn't already a `BridgeException` gets wrapped with code `"INTERNAL_ERROR"`.
