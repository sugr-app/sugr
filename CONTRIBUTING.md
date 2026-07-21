# Contributing

sugr is a very young project - this file describes the current state, not
a mature process. Expect it to change as the project grows.

## Setup

```sh
sugr doctor   # or: run the checks in cli/src/main/java/com/sugr/cli/DoctorCommand.java by hand
```

You'll need:
- A GraalVM JDK (25+) - see `docs/guide/quickstart.md` for the SDKMAN
  install path used during development
- Node.js 20+ and pnpm
- On Windows, Visual Studio Build Tools (for native-image's C compiler step)
- On Windows 10 machines without it preinstalled, the WebView2 runtime

## Build

```sh
./gradlew build          # core, bridge, processor, cli, examples/sql-client
pnpm install              # links @sugr/runtime and docs deps across the workspace
```

## Project layout

| Path | What |
|---|---|
| `core/` | `Application`/`Frontend` - window lifecycle, FFM webview bindings |
| `bridge/` | `Bridge`, `Json`, `Bind`, `EventBus` - the wire protocol, no webview knowledge |
| `processor/` | `@Bind` annotation processor - generates dispatchers + TS stubs |
| `cli/` | The `sugr` command (`doctor`/`init`/`dev`/`build`/`package`) |
| `runtime-js/` | `@sugr/runtime` - the JS half of the bridge |
| `templates/react-ts/` | What `sugr init` scaffolds |
| `examples/sql-client/` | The reference app - exercises every part of the stack |
| `docs/` | This documentation site (VitePress) |

## Making changes

- Touching `core`/`bridge`/`processor`: rebuild and re-test against
  `examples/sql-client` (`sugr dev` from `examples/sql-client`) before
  considering it done - it's the only thing exercising the full stack
  end to end right now.
- Touching `cli`: `./gradlew :cli:installDist`, then run the built script
  directly from `cli/build/install/sugr/bin/`.
- Touching `templates/react-ts`: test with `sugr init <name>`, run
  `sugr dev` against the scaffolded app, then delete it (don't commit
  scaffolded test apps under `examples/`).
- Touching `docs/`: `pnpm --filter sugr-docs dev` for a live preview,
  `pnpm --filter sugr-docs build` to check it builds cleanly.

## Versioning

sugr is pre-1.0 (currently working through `plan.md`'s v0.1 "hardening"
phase) - under SemVer's own rules for 0.x releases, that means the *entire*
public API can still change between minor versions, not just parts of it.
`@Experimental` (`com.sugr.bridge.Experimental`) exists for something more
specific: flagging API that's newer or riskier than that already-unstable
baseline and likely to be redesigned in the very next minor version (e.g. a
brand-new area added in one release that hasn't proven itself yet). Most of
today's API (`Application.Builder`, `Bridge`, `EventBus`, `@Bind`) predates
the annotation and isn't marked - that's a statement about it being no more
unstable than the 0.x baseline itself, not a promise of stability. Use
`@Experimental` on new public API going forward if you'd want a heads-up
before depending on it; remove it once something's shipped unchanged for a
minor version or two.

## What's not built yet

See `plan.md` for the full roadmap. Notably: nothing is published anywhere
(Maven Central, npm), so `sugr init` only scaffolds inside this checkout,
and `sugr package`'s macOS (`.dmg`)/Linux (`.deb`) paths follow the same
shape as the Windows one but haven't been exercised on those OSes yet.

## Reporting issues

Use the issue templates - they ask for the sugr module affected and your
`sugr doctor` output, which covers most of what's needed to reproduce
environment-specific problems (this project leans heavily on native
toolchains, so environment details matter more than usual).
