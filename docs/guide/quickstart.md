# Quickstart

::: warning Early days
sugr isn't published anywhere yet (Maven Central, npm, ...) - that's planned
for a later milestone. Today, `sugr init` scaffolds a new app *inside your
sugr checkout*, so it can depend on `core`/`bridge` as sibling Gradle
projects. This quickstart assumes you already have the sugr monorepo cloned.
:::

## Prerequisites

Run `sugr doctor` first - it checks everything below and tells you what's
missing:

- A GraalVM JDK (25+) - for `native-image` builds later. Any JDK 25 works for
  day-to-day development.
- [Gradle](https://gradle.org/) (or just use the repo's `./gradlew`)
- [Node.js](https://nodejs.org/) 20+ and [pnpm](https://pnpm.io/)

```sh
./gradlew :cli:installDist
# add cli/build/install/sugr/bin to your PATH, then:
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
