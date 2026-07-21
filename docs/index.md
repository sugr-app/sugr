---
layout: home

hero:
  name: sugr
  text: Desktop apps with Java and web tech
  tagline: Java backend + React (or any web) frontend, rendered in the OS-native webview, shipped as a single ~20-30MB binary via GraalVM native-image.
  actions:
    - theme: brand
      text: Quickstart
      link: /guide/quickstart
    - theme: alt
      text: How it works
      link: /how-it-works

features:
  - title: No embedded browser
    details: Uses the OS's built-in webview (WebView2 on Windows, WKWebView on macOS, WebKitGTK on Linux) - no bundled Chromium.
  - title: Typed bridge, no reflection
    details: Annotate a method with @Bind, get a generated dispatcher and a typed TS stub - no hand-written JSON, no runtime reflection (native-image friendly by construction).
  - title: Small, real binaries
    details: GraalVM native-image compiles the whole app to a single native executable - no JVM required at runtime.
---
