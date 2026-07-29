# Global shortcuts

`core.GlobalShortcut` registers a system-wide keyboard shortcut that fires a
callback even when the app's window isn't focused - useful for things like a
quick-capture hotkey on a background/tray-resident app. Like
[`core.Dialogs`/`core.Clipboard`/`core.Notification`](/guide/native), it's a
plain Java class - not wired into `Application.Builder` or exposed to JS
automatically. You call it from your own code, and bridge to JS yourself
(e.g. via `@Emits`) if you want the frontend to react to it:

```java
import com.sugr.core.GlobalShortcut;

AutoCloseable handle = GlobalShortcut.register("Ctrl+Shift+K", () -> {
    Notification.show("sugr", "Quick capture triggered");
});

// later, to unregister just this one shortcut:
handle.close();
```

## API

```java
GlobalShortcut.register(String accelerator, Runnable action)  // -> an AutoCloseable handle, or null on non-Windows
GlobalShortcut.closeAll()                                       // -> kills the shared background listener entirely
```

`accelerator` is modifiers joined by `+`, ending in a single key - e.g.
`"Ctrl+Shift+K"`, `"Alt+F4"`, `"Ctrl+Space"`. Supported modifiers: `Ctrl`/
`Control`, `Alt`, `Shift`, `Win`/`Meta`/`Cmd` (case-insensitive). The key can
be a single letter/digit, `F1`-`F24`, or one of a small set of named keys:
`Space`, `Enter`/`Return`, `Escape`/`Esc`, `Tab`, `Backspace`, `Delete`, and
the arrow keys (`Left`/`Up`/`Right`/`Down`).

Closing the handle returned by `register` unregisters just that one
shortcut - it does **not** shut down the shared background listener, since
other shortcuts may still be registered against it. Call `closeAll()`
(e.g. from a shutdown hook) to tear the whole thing down explicitly.

## How it's implemented

Windows only for now - `register` returns `null` on macOS/Linux. Real
system-wide global hotkeys need accessibility permissions (macOS) or
X11-specific APIs (Linux) that don't fit this project's shell-out pattern,
so rather than ship unverified code for them, they're left as a `null`
return until there's a verified approach.

On Windows, the first call to `register` lazily spawns one persistent hidden
PowerShell process shared by every subsequent registration in the JVM - the
same "long-lived hidden process" shape [`core.Tray`](/guide/window) uses for
its `NotifyIcon`, generalized to support registrations added and removed at
any time rather than built once upfront:

- The process hosts a hidden `System.Windows.Forms.Form` subclass (via
  `Add-Type`) whose `WndProc` catches `WM_HOTKEY` and prints
  `"HOTKEY:<id>"` to stdout - a virtual thread on the Java side reads those
  lines and dispatches to the matching `Runnable`.
- Each `register`/handle-`close()` call writes a `"REGISTER:<id>:<mods>:<vk>"`
  / `"UNREGISTER:<id>"` line to the process's stdin; a background thread
  inside the script reads those commands and marshals the actual
  `RegisterHotKey`/`UnregisterHotKey` Win32 calls onto the form's own thread
  (required since `RegisterHotKey` must be called from the thread that owns
  the window handle).

::: warning Verified on Windows only
The Windows implementation has been exercised end to end in a real running
app. macOS and Linux have no implementation yet - `register` simply returns
`null` there.
:::
