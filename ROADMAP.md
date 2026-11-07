# Roadmap: sugr → v1.0 (enterprise Swing replacement)

> Supersedes the post-launch part of [plan.md](plan.md) (Phase 5). plan.md stays as the
> history of how v0.1 was built.

## Positioning

**sugr is the modern-UI base for Java desktop apps that would otherwise be written in Swing.**
An enterprise keeps its Java backend, libraries, and team, swaps the UI layer for
React/any web stack, and extends sugr itself where it needs to.

What follows from that:

| Matters a lot | Matters less (for now) |
|---|---|
| Consumable from Maven Central / npm / an internal Nexus mirror | Smallest possible binary |
| Windows first (where enterprise desktops are) | macOS/Linux parity |
| Deployable by IT: MSI, code signing, offline WebView2 | App-store style distribution |
| Stable, versioned, documented API | Many frontend templates |
| Security story an auditor can sign off on | Consumer-only native APIs (Dock badge, sounds) |
| Reusing existing Java code (Spring, JDBC, services) | |
| Extension points (plugins) instead of forking core | |
| Swing → sugr migration path | |

Non-goals until v1.0: mobile, cross-compiling, replacing JavaFX for 3D/canvas-heavy apps.

Capacity assumption (same as plan.md): ~10h/week, so one "week" below means about 10 hours of work.

---

## Where things stand (2026-11-07)

- **Released:** `v0.1.0` (2026-07-29) and `v0.2.0` (2026-11-07).
- **Shipped in `v0.2.0` (M0 + pulled-forward desktop & enterprise items):**
  - Window controls, fullscreen, always-on-top, state persistence, minimize-to-tray (#3, #6, #7, #8, #9, PR #34/#38/#40/#41).
  - Custom & dark title bars with Windows 11 Snap Layouts (#35, PR #37).
  - Built-in Ed25519-signed auto-updates + `sugr keygen` + `DisableUpdates` policy + `SystemProxy` (#14, PR #43).
  - DevTools off in prod + `AssetServer` & `Bridge` / `OriginPolicy` hardening (#12, partial #15, PR #46/#47).
  - Pulled forward into `v0.2.0` and integrated into `examples/sql-client`:
    - #5 System theme detection (`SystemTheme`, `followSystemTheme`, live `WM_SETTINGCHANGE` + `app.theme()`).
    - #16 File picker dialogs: `openFiles` (multi-select), `openFolder`, and `FileFilter`.
    - #20 Public `AppPaths` (`dataDir`, `cacheDir`, `logDir`) + `app.paths()`.
    - #24 `Shell.openUrl` / `Shell.openPath`.
- **Windows only, in practice.** `core` bundles only `webview.dll`, so CI builds the native
  sql-client on Windows only, and macOS/Linux can't open a window.
- **Docs are live** at https://sugr-app.github.io/sugr/.
- **Not published:** `core`/`bridge`/`processor` aren't on Maven Central and
  `@sugr/runtime` isn't on npm, so `sugr init` only works inside this checkout.

---

## Milestone overview

| Milestone | Version | Theme | Est. |
|---|---|---|---|
| M0 | v0.2.0 | Housekeeping: release `dev`, align issues and docs with reality | 1–2 wk |
| M1 | v0.3.0 | **Adoptable**: publish artifacts, `init` outside the repo, stable-API policy | 4–6 wk |
| M2 | v0.4.0 | **Deployable on Windows by IT**: MSI, signing, WebView2, app data, logging | 5–7 wk |
| M3 | v0.5.0 | **Auditable**: CSP/sandbox, bridge security, SBOM, secure storage | 4–5 wk |
| M4 | v0.6.0 | **Extendable and migratable**: plugin API, Spring, Swing migration guide | 6–8 wk |
| M5 | v0.7.0 | **Desktop essentials** an enterprise app hits in week one | 6–8 wk |
| M6 | v0.8.0 | **Cross-platform**: macOS + Linux webview, signing/notarize | 8–12 wk |
| — | v1.0.0 | API freeze, see exit criteria | — |

Total: ~9–12 months at 10h/week. M5 items can be pulled forward one at a time when a real
user asks. M6 can slip without hurting the positioning.

GitHub setup: create one GitHub milestone per row above and assign issues to them.
Today no issue has a milestone.

---

## M0 — Housekeeping (v0.2.0) · 1–2 weeks (Completed)

Goal: `main`, the docs site, and the issue tracker describe the same product.

### Tasks
- [x] Review and merge PR #43 into `dev` (closes #14). It's large (core updater + CLI keygen +
      docs), so review it in this order: `UpdateVerifier`/`UpdateInstaller` (security- and
      crash-sensitive), then `SugrConfig`, then docs.
- [x] Merge `dev` → `main`, tag `v0.2.0`, write release notes (start `CHANGELOG.md`, Keep a
      Changelog format).
- [x] Close issues that `v0.2.0` implements (#3, #5, #6, #7, #8, #9, #12, #14, #16, #20, #24, #35).
- [x] Re-label against the new positioning (see "Issue re-prioritisation" below).
- [x] Fix the docs:
  - The Contributing link in `docs/.vitepress/config.ts` points to
    `sugr-app/sugr/blob/main`.
  - Replace the hero tagline ("~20-30MB binary via GraalVM native-image") with the new
    positioning, and add a "Why not Swing / JavaFX?" section on the home page.
  - Add "Window chrome & title bar" (from #37) and "Auto-update" (from #43) to the sidebar.
  - README: drop "no hosted docs site yet", link the site, add a "Platform support:
    Windows today, macOS/Linux planned" table.
- [x] Set the `docs.yml` deploy to trigger on tag or `main` only, so the site matches the
      released version.

### Exit criteria
`main` = latest release, every open issue is truly open, and the docs home page states the
enterprise positioning.

---

## M1 — Adoptable (v0.3.0) · 4–6 weeks

Goal: an engineer at another company can add sugr to a new or existing Gradle project from
public repositories, without cloning this repo.

### Tasks
- [ ] **Publish to Maven Central** *(new issue)*: `com.sugr:core`, `bridge`, `processor`
  - Claim the namespace (`com.sugr` needs domain proof; fall back to `io.github.sugr-app`
    if you don't own `sugr.com`). Decide this **before** publishing, because coordinates
    are forever.
  - Gradle `maven-publish` + signing plugin, with sources and javadoc jars and a POM that
    has license, SCM, and developers.
  - `release.yml`: on a `v*` tag publish libraries + CLI binary + npm from the same version.
  - Check that the `webview.dll` resource inside `core.jar` loads from a dependency jar,
    not only from this repo's build (`NativeLibrary` extract path).
- [ ] **Publish `@sugr/runtime` to npm** *(new issue)*, with the version locked to the Java
      artifacts and a runtime handshake that warns on a mismatch.
- [ ] **`sugr init` outside the checkout** *(new issue)*. The template resolves published
      versions instead of `includeBuild`. Add `--sugr-version` and an option for a custom
      Maven repo URL (Nexus/Artifactory mirror).
- [ ] **Use in an existing project** *(new issue)*. Document and test adding sugr to an
      existing multi-module Gradle build without `sugr init`. Decide whether a **Maven**
      (not Gradle) path is in scope. Many Swing shops are Maven; at minimum document a
      Maven POM that runs the processor, and treat a Maven plugin as M4.
- [ ] **API stability policy** *(new issue)*:
  - SemVer, with "0.x minor = may break" until 1.0, written down in the docs.
  - Audit the public surface of `core`/`bridge`/`runtime-js` and mark everything not
    meant to be stable `@Experimental` (it's used nowhere today). Window chrome
    (`customTitleBar`, snap overlay) and the updater are good candidates.
  - Mark internals non-public, or move them to `internal` packages. Consider JPMS
    `module-info` with exported packages only.
  - Add `japicmp` (or similar) in CI to flag accidental breaking changes.
- [ ] **`sugr doctor` for enterprise machines**: detect a proxy, check Maven Central/npm
      reachability, and report the WebView2 runtime version.

### Exit criteria
On a clean Windows VM: install the CLI, run `sugr init demo && cd demo && sugr dev`, and a
window opens in < 10 min, with nothing cloned and no `includeBuild`.

---

## M2 — Deployable on Windows by IT (v0.4.0) · 5–7 weeks

Goal: an IT department can sign, distribute (Intune/SCCM/GPO), and support a sugr app like
any other Windows desktop app.

### Tasks
- [ ] **WebView2 runtime strategy** *(new issue)*. Locked-down machines can't run the online
      Evergreen bootstrapper. Support all three, set in `sugr.config.json`:
      `evergreen-bootstrapper` (default), `evergreen-offline` (bundles the standalone
      installer), and `fixed-version` (ship a pinned runtime folder next to the app). Show a
      clear dialog, not a blank window, when the runtime is missing.
- [ ] **MSI output for `sugr package`** *(new issue)*. Use WiX v4 alongside the existing NSIS
      output. Per-machine vs per-user install, stable `UpgradeCode`, silent install
      (`msiexec /qn`), and documented properties for IT.
- [ ] **Code signing (#33)**, raised P3 → **P1**. Use `signtool` with a PFX file, the
      certificate store, or a cloud HSM (Azure Trusted Signing). Sign the exe, the
      installer, and the update artifacts. #43's Ed25519 update signature is separate from
      Authenticode; document how they relate.
- [ ] **Auto-update (#14 / PR #43)** in enterprise mode: allow disabling it by policy
      (registry or config), a self-hosted manifest URL, and a check that per-machine
      installs in `Program Files` aren't writable by the user (the updater must fail
      cleanly or hand off to an elevated helper).
- [ ] **Single instance (#10)**, raised to P0. Use a named mutex plus forwarding of argv to
      the running instance. This is also the foundation for #1 deep links and #2 file
      associations in M5.
- [x] **App data directories (#20)**, shipped in `v0.2.0`: public `AppPaths` (`dataDir`,
      `cacheDir`, `logDir`) + `app.paths()` in `@sugr/runtime`.
- [ ] **Logging (#22)**, raised P3 → **P1**. Log through SLF4J (the enterprise default)
      instead of inventing a sugr logger. Provide a file appender into the app data log
      dir and forward the frontend `console.*` to the Java log.
- [ ] **Crash reporting (#17, P1)**: uncaught JVM exceptions plus native crashes go to the
      log dir, with an opt-in "send report" hook (no built-in SaaS).
- [x] **DevTools (#12, P1)**, shipped in `v0.2.0`: on in `dev`/`debug`, off in `package`
      unless `SUGR_DEVTOOLS=1` or `Builder.devTools(true)`.
- [ ] **Corporate network**: the HTTP client (#11) and the updater respect the system
      proxy and the Windows certificate store (corporate TLS interception).

### Exit criteria
A signed MSI installs silently on a clean Windows 10 VM with no internet access
(fixed/offline WebView2). The app writes logs to the app data dir, refuses a second
instance, and updates (or is policy-blocked from updating) correctly.

---

## M3 — Auditable (v0.5.0) · 4–5 weeks

Goal: a security reviewer at a bank or insurer can read one page and approve sugr.

### Tasks
- [ ] **Replace the localhost asset server** (plan.md v0.5, part of #15). `AssetServer`
      binds `127.0.0.1` on a random port, so any local process can read the app's assets.
      Move to WebView2 `SetVirtualHostNameToFolderMapping` or `WebResourceRequested` with a
      custom scheme. If the server stays as a fallback, require a per-launch token.
- [ ] **CSP + sandbox (#15)**: a strict default CSP injected by sugr, configurable in
      `sugr.config.json`. Block navigation to remote origins by default, with an allowlist.
      Open external links in the system browser (shares code with #24). Deny or prompt
      WebView2 permission requests (camera, mic, geolocation).
- [ ] **Bridge security model** *(new issue, docs + code)*:
  - Only `@Bind` methods are callable, which is already an allowlist.
  - Bindings are rejected from navigated or remote origins.
  - Optional per-window binding scopes.
  - Input size limits in `bridge.Json`.
  - Write it up as `docs/security.md`.
- [ ] **Third-party provenance / SBOM** *(new issue)*. Document where `webview.dll` comes
      from (upstream version/commit, build flags, license, SHA-256). Make it reproducible
      from CI. Generate a CycloneDX SBOM for the libraries and for the packaged app.
- [ ] **Secure storage (#18)**, raised P2 → **P1**: Windows Credential Manager first,
      behind an interface that M6 implements for Keychain/libsecret.
- [ ] Add `SECURITY.md` (reporting policy, supported versions) and turn on GitHub private
      vulnerability reporting and Dependabot.

### Exit criteria
`docs/security.md` + SBOM + no open listening port in prod builds + default CSP that blocks
an injected `<script src="https://evil">` (covered by a test).

---

## M4 — Extendable and migratable (v0.6.0) · 6–8 weeks

Goal: deliver the "base the enterprise extends" promise and give Swing teams a migration
path.

### Tasks
- [ ] **Plugin API (#36, P1)**. The design is already in the issue: `Plugin#install
      (Window.Builder)` plus `use()` on both builders, and first the prerequisite
      lifecycle fix noted in #36. Then rewrite one or two built-ins as plugins to prove the
      API: window-state persistence and the tray minimize behaviour from #38. Plugins can
      ship their own `@Bind` classes + generated TS. Document how to publish a plugin as a
      Maven + npm pair.
- [ ] **Spring Boot integration** *(new issue)*:
  - A `sugr-spring` module or a documented recipe: the Spring context starts before the
    window, `@Bind` beans come from the context, and shutdown is ordered (window closes,
    then the context closes).
  - Check native-image compatibility with Spring AOT, and state clearly if JVM mode is
    the supported path for Spring.
- [ ] **JVM distribution as a first-class target** *(new issue)*. Many enterprises will run on
      their managed JDK instead of native-image. `sugr package --target=jlink` (a jlink
      runtime + launcher + MSI) gives a no-GraalVM path, which plan.md 3.2 listed as a
      fallback. Document the trade-offs table: native-image vs jlink vs plain JAR.
- [ ] **"Migrating from Swing" guide** *(new issue)*:
  - Concept map: `JFrame` → `Window`, `SwingWorker` → `CompletableFuture` + `@Bind`,
    `PropertyChangeListener` → `@Emits`, `JTable` → a React table (TanStack Table),
    `JFileChooser` → `Dialogs`, `JOptionPane` → `Dialogs.confirm`, `Action`/`KeyStroke` →
    `Menu` + `GlobalShortcut`.
  - Show how to keep the service/DAO layer untouched and replace only the view layer.
  - Strategy for incremental migration (screen by screen) vs big-bang, and the limits
    (no in-process Swing + webview mixing).
- [ ] **Second example app: `examples/swing-migration`** *(new issue)*: a small Swing CRUD app
      (customers/orders on H2 or SQLite) and the same app on sugr, sharing one
      `service` module. Doubles as the migration guide's source code.
- [ ] **Maven plugin** *(new issue, optional, depends on M1 findings)*: `sugr:dev`,
      `sugr:package` for Maven-only shops.
- [ ] **Docs comparison page**: Swing vs JavaFX vs sugr (UI tech, hiring pool, reuse of Java
      code, packaging, theming/dark mode, accessibility).

### Exit criteria
A team can (a) add an in-house plugin without forking core, (b) run sugr inside an existing
Spring Boot app, and (c) follow the guide to port the example Swing app.

---

## M5 — Desktop essentials (v0.7.0) · 6–8 weeks

Existing issues, ordered by how soon a typical internal line-of-business app needs them.
Each is a standalone PR. Pull any of them forward on user demand.

| Order | Issue | Notes |
|---|---|---|
| 1 | ~~#16 File dialogs: folder picker, multi-select, filters (P1)~~ | **Done in `v0.2.0`** (`Dialogs.openFiles`, `Dialogs.openFolder`, `Dialogs.FileFilter`) |
| 2 | #25 Print / print to PDF, raise P2 → **P1** | Reports and invoices are core to LOB apps. Use WebView2 `PrintToPdf` + `ShowPrintUI` |
| 3 | ~~#5 System theme detection (P0)~~ | **Done in `v0.2.0`** (`SystemTheme`, `followSystemTheme`, `onThemeChanged`, `app.theme()`) |
| 4 | #4 Drag & drop files (P0) | |
| 5 | ~~#24 Shell open URL/folder~~ | **Done in `v0.2.0`** (`Shell.openUrl`, `Shell.openPath`) |
| 6 | #2 File associations (P0) | Depends on #10 + MSI (M2) |
| 7 | #1 Deep links (P0) | Depends on #10. Covers SSO/OAuth redirects (Azure AD) |
| 8 | #21 Recent files (P1) | Windows jump list |
| 9 | #11 HTTP client (P1), #13 WebSocket (P1) | **Re-check the need.** The frontend can already use `fetch`/WebSocket, and Java has `HttpClient`. Narrow them to "proxy/cert-aware Java-side client exposed via bridge", or close |
| 10 | #19 File watching (P2) | `WatchService` + `@Emits` |
| 11 | #31 Context menu in webview (P3) | |
| 12 | #26 Multi-monitor (P3) | Restore-on-missing-monitor overlaps #6's persistence |
| 13 | #23 Prevent sleep, #30 Process info, #28 Sound (P3) | Low priority for LOB apps |

Also: **accessibility** *(new issue)*. Enterprises (government especially) need it. Verify
screen-reader behaviour through WebView2 UIA, and that keyboard focus moves correctly
between the native menu and the webview.

---

## M6 — Cross-platform (v0.8.0) · 8–12 weeks

- [ ] **macOS + Linux webview binaries** *(new issue, the big one)*: build `libwebview` for
      macOS (arm64/x64, WKWebView) and Linux (x64/arm64, WebKitGTK 4.1) in CI. Load them via
      `NativeLibrary` per OS, and handle the macOS main-thread requirement.
- [ ] Port the Windows-only parts: `WindowNative` (title bar and snap work are Win32-only, so
      define the cross-platform subset), `Tray`, `Notification`, `GlobalShortcut`,
      `AutoStart`, `Dialogs`.
- [ ] #27 Linux native tray + notifications, #29 macOS Dock menu & badge.
- [ ] #33 signing for macOS (codesign + notarize) and Linux packages (`.deb`/AppImage).
- [ ] CI matrix: build and smoke-test (open window, call one `@Bind`, close) on all 3 OSes.
- [ ] #32 Error handling / remote debugging: cross-platform crash and debug parity.

---

## v1.0 exit criteria

- No breaking API change for 3 months, `japicmp` clean, and every remaining
  `@Experimental` is intentional.
- At least 2 real apps in production, ideally 1 of them a migrated Swing app.
- M0–M4 done. M5 at least items 1–7. M6 is not required: v1.0 may ship as "Windows GA,
  macOS/Linux preview".
- Docs: guide, security page, migration guide, API reference generated from Javadoc.
- CI green on main for 4 consecutive weeks; release process fully automated from a tag.

---

## Issue re-prioritisation (summary)

| Issue | Current | Proposed | Why |
|---|---|---|---|
| #3 #6 #7 #8 #9 | P0 | **Close** | Done in #34/#37/#38 on `dev` |
| #35 Frameless | — | Close or P2 (narrowed) | #37's `customTitleBar()` covers most of it |
| #14 Auto-update | P1 | P1, close when PR #43 merges | |
| #10 Single instance | P1 | **P0** | Enterprise baseline; prerequisite for #1 and #2 |
| #33 App signing | P3 | **P1** | IT won't deploy unsigned binaries |
| #22 Logging | P3 | **P1** | Supportability; use SLF4J |
| #20 App data dirs | P2 | **P1** | Logs, config, crash dumps depend on it |
| #18 Secure storage | P2 | **P1** | Credentials for DB/API connections |
| #25 Print / PDF | P2 | **P1** | Reports are core to LOB apps |
| #24 Shell open | P3 | P2 | Needed by #15 |
| #36 Plugin API | — | **P1** + `platform` label | It's what "base the enterprise extends" means |
| #11 HTTP / #13 WebSocket | P1 | Re-scope or close | Overlaps `fetch` and `java.net.http` |
| #29 Dock / #28 Sound / #30 Proc info / #23 Sleep | P3 | P3 (unchanged) | |

## New issues to create (no existing issue covers these)

1. Publish `core`/`bridge`/`processor` to Maven Central (M1)
2. Publish `@sugr/runtime` to npm + version handshake (M1)
3. `sugr init` outside the checkout / custom Maven repo (M1)
4. Add sugr to an existing Gradle/Maven project (M1)
5. API stability policy: `@Experimental` audit, internal packages, japicmp (M1)
6. WebView2 runtime distribution modes: bootstrapper / offline / fixed (M2)
7. MSI packaging via WiX (M2)
8. Bridge security model + `docs/security.md` (M3)
9. `webview.dll` provenance + SBOM (M3)
10. Spring Boot integration (M4)
11. jlink / JVM distribution target (M4)
12. "Migrating from Swing" guide (M4)
13. `examples/swing-migration` (M4)
14. Maven plugin, optional (M4)
15. Accessibility audit through WebView2 UIA (M5)
16. macOS + Linux webview binaries (M6)
17. Fix docs Contributing link + positioning copy (M0, can just be a PR)
