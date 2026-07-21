# Roadmap: Building "sugr"

> A Java desktop app framework: Java backend + React frontend rendered in the OS's native webview, packaged as a single ~20–30MB binary via GraalVM native-image.

**Estimated total time:** 8–12 months (side project, ~10h/week)
**Guiding principles throughout:** App first, lib second · Ruthless scope control · Every milestone must produce something visible

---

## Phase 0 — Knowledge foundations (2–3 weeks)

Two technical bottlenecks that must be genuinely learned, not vibe-coded. Learn through small exercises, not just reading.

### To do

| Week | Content | Verification exercise |
|---|---|---|
| 1 | FFM API (Project Panama, JDK 22+): Arena, MemorySegment, Linker, upcall stubs | Bind the OS's `libsqlite3`: open a db, run a query, print results |
| 2 | GraalVM native-image: closed-world analysis, reflection config, reachability metadata | Build week 1's exercise into a native binary < 10MB, deliberately trigger a reflection error to understand how it fails |
| 3 (buffer) | Read the webview_java (JNA) and webview_go source to understand the call sequence for libwebview's C API | Write notes on the flow: create → bind → navigate → run loop → destroy |

### Tooling setup

- GraalVM JDK 24+ (Oracle GraalVM edition, not Community, for `-Os`)
- Maven or Gradle (Gradle recommended — easier for the CLI plugin later)
- Visual Studio Build Tools (if developing on Windows) / Xcode CLT (macOS) / gcc + webkit2gtk-dev (Linux)
- Node.js + pnpm for the frontend

### Exit criteria
✅ A native binary built from FFM code runs on your machine, and you can explain why it works.

---

## Phase 1 — Proof of concept: App first, lib second (1–2 months)

Build a real app (suggestion: a mini SQL client for SQLite) to prove out the entire technical path. Messy code is fine. It only needs to run on your machine.

### Milestone 1.1 — First window (weeks 1–2)
- Minimal FFM binding for libwebview: `webview_create`, `webview_set_title`, `webview_set_size`, `webview_navigate`, `webview_run`, `webview_destroy`
- Open a window that displays `https://example.com`
- Handle the thread model correctly: macOS requires running on the main thread (`-XstartOnFirstThread` when launching the JVM)
- 🎉 **Celebration moment #1: the window opens**

### Milestone 1.2 — React rendering (weeks 3–4)
- Scaffold Vite + React + TS in a `frontend/` directory
- Dev mode: webview navigates to `http://localhost:5173` → HMR comes for free
- Production mode: embed `dist/` into resources, serve via a custom scheme or a mini localhost HTTP server (acceptable for now, optimize later)
- 🎉 **Moment #2: the React app runs inside the native window**

### Milestone 1.3 — Manual bridge (weeks 5–6)
- `webview_bind` to expose Java functions to JS: JS calls `window.invoke("method", args)` → Java handles it → returns JSON
- Hand-write 3–4 methods for the SQL client app: `connect`, `query`, `listTables`
- Events from Java back to JS via `webview_eval` (dispatched on the UI thread)
- Serialization: hand-written records + serializer, or dsl-json (avoid Jackson databind)

### Milestone 1.4 — First native binary (weeks 7–8)
- Build native-image with flags: `-Os --gc=serial --no-fallback -march=compatibility`
- Write reachability metadata for the FFM parts (downcalls/upcalls need declarations)
- Measure size, use `-H:BuildReport` to see which classes take up space
- 🎉 **Moment #3: binary < 30MB, double-click and it just runs, no JVM required**

### Exit criteria
✅ The SQL client app runs from a native binary on your machine. You've hit and cleared both bottlenecks.

---

## Phase 2 — Extracting the library (1–2 months)

Split the reusable parts out of the app. The SQL client app becomes `examples/sql-client` — the first sample app.

### Proposed repo structure (monorepo)

```
sugr/
├── core/              # FFM binding + Window/Webview API
├── bridge/            # IPC, serialization, event bus
├── processor/         # annotation processor (phase 3)
├── cli/               # CLI tool (phase 3)
├── runtime-js/        # npm package: frontend-side JS runtime
├── templates/         # project templates (phase 3)
├── examples/
│   └── sql-client/
├── docs/
└── .github/workflows/
```

### To do

**`core` module:**
- Public API: `Application`, `Window` (builder pattern: title, size, resizable, min/max size)
- Lifecycle + thread model management hidden from the user (auto-dispatch to the UI thread)
- Native lib loading: bundle libwebview for all 3 OSes in resources, extract to a temp dir at runtime (or statically link for native-image)

**`bridge` module:**
- Lightweight JSON-RPC protocol: request id, method, params, result/error
- JS Promise ↔ Java CompletableFuture
- Bidirectional event bus: `events.emit()` / `events.on()` on both sides

**`runtime-js` module:**
- npm package `@sugr/runtime`: `invoke()` function, `events`, basic typing
- This is what the React template will import

**API design — write the README before the code:**
- Write a README with "how people will use this" sample code FIRST, code follows
- Target experience: users write < 15 lines of Java to open their first app

### Exit criteria
✅ `examples/sql-client` runs entirely on the library's public API, no touching internals.

---

## Phase 3 — Developer Experience (2–3 months)

The part that decides whether the library lives or dies: turning "the code works" into "a stranger can use it in 5 minutes".

### Milestone 3.1 — Annotation processor (weeks 1–3)
- `@Bind` annotation on classes/methods
- Compile-time generation of:
  - A Java dispatcher (switch on method name — no reflection, native-image friendly)
  - A `.d.ts` file + JS stub: React dev calls `Backend.getUser()` with full autocomplete and typing
  - Reachability metadata for native-image, automatically
- Supported types: primitives, String, records (nested), List/Map, CompletableFuture for async

### Milestone 3.2 — CLI (weeks 4–7)

Write the CLI in Java itself + picocli, build it as a native-image → the CLI itself becomes a demo of the library.

| Command | Function |
|---|---|
| `sugr init <name>` | Scaffold a project from a template (asks: React/Vue/Svelte/Vanilla, JS/TS) |
| `sugr dev` | Run the Vite dev server + the Java app together, watch Java files → rebuild + restart, frontend gets HMR |
| `sugr build` | npm build → embed assets → compile native-image → binary in `build/bin/` |
| `sugr build --target=jlink` | Fallback: jlink runtime instead of native-image (faster build for dev) |
| `sugr package` | Package as: `.msi`/`.exe` (Windows), `.dmg`/`.app` (macOS), `.deb`/`.AppImage` (Linux) |
| `sugr doctor` | Check the environment: GraalVM, native toolchain, Node, WebView2 runtime |
| `sugr generate` | Run codegen bindings standalone (normally runs automatically at compile time) |

### Milestone 3.3 — Templates + CI (weeks 8–10)
- Complete `react-ts` template: Vite config ready, `@sugr/runtime` pre-installed, example backend call
- GitHub Actions matrix across 3 OSes: build + test + build the native example app on Windows/macOS/Linux
- This is the first time the library gets verified cross-platform — budget extra time for CI debugging

### Milestone 3.4 — Docs (weeks 11–12)
- Docs site (Docusaurus/VitePress): 5-minute quickstart, guides (window, bind, events, build), API reference
- A "How it works" page explaining the architecture — users of a foundational library really need this trust
- CONTRIBUTING.md + issue templates

### Exit criteria
✅ A stranger (get a friend to test) goes from `sugr init` to a running app in < 10 minutes without asking you anything.

---

## Phase 4 — Hardening + v0.1 launch (1 month)

### Locked scope for v0.1
**In:** a single window · webview · `@Bind` + TS generation · events · CLI init/dev/build/package · React template · desktop on all 3 OSes
**Out (later, clearly noted in the README):** multi-window, system tray, native menus, dialogs, auto-update, mobile

### To do
- Test on real machines for all 3 OSes (borrow machines/VMs if needed), especially Windows 10 without WebView2 preinstalled → the CLI package needs a bootstrapper that installs the WebView2 runtime
- Friendly error messages for the 10 most common failures (missing GraalVM, missing toolchain, port in use, ...)
- Versioning: SemVer, mark APIs `@Experimental` when not yet stable
- License: MIT or Apache-2.0 (decided from the first commit)
- Publish: Maven Central (core/bridge/processor), npm (`@sugr/runtime`), Homebrew/Scoop for the CLI (can wait for v0.2)

### Launch
- Write an "I built sugr" post — tell the real technical story (FFM, native-image, the moments you almost gave up)
- Post to: r/java, Hacker News (Show HN), Twitter/X, Vietnamese dev communities
- Be mentally ready for a flood of issues in the first 2 weeks — that's actually a good sign

---

## Phase 5 — Post-launch → v1.0 (3–6 months, driven by user demand)

Suggested priority order (adjust based on real issues):

1. **v0.2** — Native dialogs (open/save file, message box) + clipboard: every real app needs these right away
2. **v0.3** — Multi-window + window events (close, focus, resize)
3. **v0.4** — System tray + native menus
4. **v0.5** — Full custom-scheme asset serving (drop the localhost server entirely), safe default CSP
5. **v0.6** — Auto-updater (hard, consider a third-party integration)
6. **v1.0** — When: the API has been stable for 3+ months with no breaking changes, ≥ 2 real production apps are using it, docs are complete, CI is consistently green

### Ongoing operations
- Respond to issues within 48h in the early period (doesn't need an immediate fix, just a response)
- Tag `good-first-issue` to attract contributors
- Changelog per release, short blog posts for major milestones

---

## Key risks and mitigations

| Risk | Mitigation |
|---|---|
| Stuck on an undebuggable FFM segfault | Always keep webview_go/webview_java as a reference; test each C function individually; keep the binding minimal |
| native-image builds on your machine but fails on others | 3-OS CI early (phase 3.3, don't leave it to the end); test on a clean Windows 10 |
| WebKitGTK version fragmentation on Linux | Clearly document supported distros (Ubuntu 22.04+); bundle dependencies in the AppImage |
| "Week 6 slump" — losing motivation | Small milestones with visible results; build in public to create positive pressure |
| Scope creep — wanting to add more features forever | A "Not doing this for v0.1" list pinned at the top of the README; every new idea → goes into an issue, not code |
| Nobody uses it after launch | Still a win: FFM + native-image + annotation processing + OSS ops skills are real career assets |

---

## Timeline summary

| Phase | Duration | Outcome |
|---|---|---|
| 0 — Foundations | 2–3 weeks | Understand FFM + native-image through exercises |
| 1 — PoC app | 1–2 months | SQL client native binary < 30MB |
| 2 — Extract lib | 1–2 months | Core + Bridge + public API |
| 3 — DX | 2–3 months | @Bind codegen, CLI, template, CI, docs |
| 4 — v0.1 | 1 month | Public launch |
| 5 — v1.0 | 3–6 months | Stabilize based on real demand |
