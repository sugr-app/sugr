# Native dialogs, clipboard & notifications

`core.Dialogs`, `core.Clipboard`, and `core.Notification` are plain Java
utility classes - not wired into `Application.Builder` or exposed to JS
automatically. You call them from your own `@Bind`/hand-written methods, the
same as any other Java code:

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
Dialogs.confirm(String title, String message)             // -> true if the user picked Yes

Clipboard.read()          // -> current clipboard text, or null
Clipboard.write(String text)

Notification.show(String title, String message)               // -> void, fire-and-forget
Notification.show(String title, String message, String iconPath)  // -> same, with a custom icon
Notification.registerApp(String appId, String displayName, String exePath, String iconPath)
```

`iconPath` (on `show`) is a local file path to a PNG/JPG (not an `.ico`) -
on Windows it becomes the toast's `appLogoOverride` image, and doesn't need
any AUMID/shortcut registration - it works today, in `sugr dev` or
otherwise.

Call `registerApp` once at startup, before the first `show`, to also brand
the toast's *sender name* with your own app instead of "Windows PowerShell"
(see the warning below for why that's a separate concern from the icon):

```java
Notification.registerApp("com.example.myapp", "My App",
    ProcessHandle.current().info().command().orElse(null), iconPath);
```

Every `Dialogs` method also has an overload taking a native window handle as the first
argument (e.g. `Dialogs.showMessage(long ownerHwnd, String title, String message)`) -
pass `window.nativeHandle()` (see [`Window`](/guide/window)) to make the dialog an owned
child of that window. This is what keeps the dialog grouped with the app's own taskbar
entry (and, on Windows, shown together in its Alt-Tab/taskbar hover preview) instead of
appearing as an unrelated window of its own - worth doing for any dialog triggered from a
menu item or other place that isn't already inside a request from that window's own page.

## How it's implemented

Rather than binding each OS's native dialog/clipboard/notification API
directly via FFM, these shell out to a small script per OS:

| | Dialogs | Clipboard | Notification |
|---|---|---|---|
| Windows | PowerShell + `System.Windows.Forms` | PowerShell's `Get-Clipboard`/`Set-Clipboard` | PowerShell + WinRT's `Windows.UI.Notifications.ToastNotificationManager` |
| macOS | `osascript` (AppleScript) | `pbcopy`/`pbpaste` | `osascript`'s `display notification` |
| Linux | `zenity` | `xclip` (falls back to `xsel`) | `notify-send` |

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
The Windows path (dialogs, clipboard, and notifications) has been exercised
end to end in a real running app. macOS (`osascript`/`pbcopy`/`pbpaste`) and
Linux (`zenity`/`xclip`/`xsel`/`notify-send`) follow the same shape using
each platform's standard, well-documented tools, but haven't been run on
those OSes yet.
:::

::: warning Without `registerApp`, notification toasts show "Windows PowerShell" as the sender name
Showing a toast via `ToastNotificationManager` requires an AppUserModelID
registered with the OS - normally via a Start Menu shortcut carrying a
matching `System.AppUserModel.ID` property, which only exists once an app
is installed via a real installer. `Notification.registerApp` creates that
shortcut itself (via `IShellLinkW`/`IPropertyStore` COM interop, since a
plain `New-Object -ComObject WScript.Shell` shortcut can't set that
property) the first time it's called, so it works in `sugr dev` too - not
just once `sugr package`'d. Skip calling it and `show` falls back to the
well-known, already-registered AUMID for Windows PowerShell itself, so
toasts still work everywhere, just under that name instead of the app's.
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
