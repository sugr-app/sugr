# Why sugr?

sugr is the modern-UI base for Java desktop apps that would otherwise be built
with Swing. You keep your Java backend, your libraries and your team. The UI is
built with React (or any web stack) and rendered in the OS-native webview, and
the result ships as a native binary and installer.

## Swing vs. JavaFX vs. sugr

| | Swing | JavaFX | sugr |
|---|---|---|---|
| UI technology | Swing components (Java) | JavaFX scene graph (Java, FXML/CSS) | React or any web framework, in the OS webview |
| Look & feel / theming | Look & feel classes; custom theming is laborious | CSS, but a separate styling model from the web | Ordinary CSS and component libraries |
| Hiring pool for UI devs | Small and shrinking | Small | Large (web developers) |
| Reuse of existing Java code | Yes | Yes | Yes - the backend stays plain Java; UI talks to it through `@Bind` |
| Packaging / distribution | JVM app (jpackage or a bundled runtime) | JVM app (jpackage or a bundled runtime) | GraalVM native-image binary plus an installer; no JVM on user machines |
| Platform support today | Windows, macOS, Linux | Windows, macOS, Linux | Windows (macOS and Linux planned) |

Swing and JavaFX are mature and fully supported, and both are good choices
when their trade-offs fit your app. sugr is for teams that want a modern UI
without giving up the Java they already have.

## When not to use sugr

- **You need macOS or Linux today.** Only Windows is supported right now.
  macOS and Linux are planned, but not yet available.
- **Heavy 3D or canvas-driven apps.** The UI runs in a webview, so
  performance-critical rendering (games, CAD, large real-time visualizations)
  is better served by a native graphics stack.
- **You need to embed Swing components inside sugr.** sugr does not host
  Swing components in-process. If your UI is mostly Swing components you need
  to keep, stay with Swing or migrate those screens first.

## Next steps

- [Quickstart](/guide/quickstart) - set up a sugr app on Windows
- [Binding Java to JS](/guide/bind) - how the typed bridge works
- [How it works](/how-it-works) - the architecture behind the bridge and the webview
