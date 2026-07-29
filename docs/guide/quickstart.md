# Quickstart

::: warning Early days
sugr isn't published anywhere yet (Maven Central, npm, ...) - that's planned
for a later milestone. Today, `sugr init` scaffolds a new app *inside your
sugr checkout*, so it can depend on `core`/`bridge` as sibling Gradle
projects. This quickstart assumes you already have the sugr monorepo cloned.
:::

## Prerequisites

- A GraalVM JDK (25+) - for `native-image` builds later. Any JDK 25 works for
  day-to-day development.
- [Gradle](https://gradle.org/) (or just use the repo's `./gradlew`)
- [Node.js](https://nodejs.org/) 20+ and [pnpm](https://pnpm.io/)

### Get the `sugr` CLI

Download the prebuilt native binary from the latest
[GitHub Release](https://github.com/sugr-app/sugr/releases) - no JVM, Gradle,
or GraalVM install needed just to run the CLI itself:

```sh
# macOS (Apple Silicon only for now) / Linux
curl -fsSL https://raw.githubusercontent.com/sugr-app/sugr/main/install.sh | sh
```

```powershell
# Windows
irm https://raw.githubusercontent.com/sugr-app/sugr/main/install.ps1 | iex
```

This installs to `~/.sugr/bin` (`%LOCALAPPDATA%\sugr\bin` on Windows) and
adds it to your PATH - see [`install.sh`](https://github.com/sugr-app/sugr/blob/main/install.sh)
for the full list of supported platforms, and
[`.github/workflows/release.yml`](https://github.com/sugr-app/sugr/blob/main/.github/workflows/release.yml)
for how each release's binaries are built.

Or, if you're working from a checkout of this repo anyway (e.g. to build the
prerequisites for the section below), build the CLI from source instead:

```sh
./gradlew :cli:installDist
# add cli/build/install/sugr/bin to your PATH
```

Either way, run `sugr doctor` next - it checks every prerequisite above and
tells you what's missing:

```sh
sugr doctor
```

## Create an app

From anywhere inside your sugr checkout:

```sh
sugr init my-app
```

This scaffolds `examples/my-app/` (a `frontend/` Vite+React+TS project and a
`lib/` Gradle module) from `templates/react-ts` and registers it in the root
`settings.gradle.kts`.

Link the new frontend's dependencies (from the sugr root, so pnpm's
workspace can find `@sugr/runtime`):

```sh
pnpm install
```

## Run it

```sh
cd examples/my-app
sugr dev
```

This starts the Vite dev server, waits for it to be ready, then runs the
Java app pointed at it. The frontend gets Vite's HMR for free; editing a
`.java` file under `lib/src` rebuilds and restarts the app automatically.

A window should open showing the template's "Say hello" button - click it to
confirm the Java bridge is wired up (it calls `Backend.hello()` in
`lib/src/main/java/.../Backend.java`).

## Next steps

- [Window & frontend](/guide/window) - configuring the window, dev vs. embedded frontend
- [Binding Java to JS](/guide/bind) - `@Bind`, hand-written binds, and when to use which
- [Events](/guide/events) - the two-way event bus
- [Building & packaging](/guide/build) - `sugr build`, and native-image today
- [Debugging](/guide/debug) - `sugr debug`, attaching a real IDE debugger to backend/frontend
