# Auto-updates

`sugr` can ship a signed, in-app update flow with no code at all: a dev
generates a key, publishes a manifest next to their release, and users of the
app get offered - and can accept - new versions from inside the running app.
No dev-side server beyond static file hosting, no third-party service.

This is built-in the way Squirrel is to Electron or the updater plugin is to
Tauri, adapted to how `sugr` packages apps: since `sugr package` already
produces one self-contained native-image executable per platform (no JVM, no
jars, no asar), applying an update is just replacing that one file.

## Quickstart

```bash
cd my-app
sugr keygen
```

This prints a public key and the block to paste into `sugr.config.json`:

```json
{
  "name": "my-app",
  "appVersion": "1.0.0",
  "updater": {
    "endpoints": ["https://your-host/latest.json"],
    "pubkey": "MCowBQYDK2VwAyEA..."
  }
}
```

Then, for every release:

```bash
SUGR_SIGNING_KEY=~/.sugr/my-app.key \
  sugr package --base-url https://your-host
```

Upload everything `sugr package` writes to `dist/` - the installer, the
update artifact, its `.sig`, and `latest.json` - to wherever `--base-url`
points (a GitHub release, S3, your own static host). That's the whole
publishing story.

With this in place, a running `my-app` checks `latest.json` a few seconds
after startup, and - if a newer version is there - shows a native "Update
available" dialog, downloads it, verifies it, and restarts into the new
version. None of that is code you write; it's what `"updater"` in
`sugr.config.json` turns on.

## Why a signing key is required

The public key is baked into every build; the private key signs every
release and never leaves your machine or CI secrets. A downloaded update is
refused unless its Ed25519 signature verifies against that embedded public
key - **not just SHA-256, and not just HTTPS**. HTTPS stops a
man-in-the-middle from swapping the file in transit; it does nothing about a
compromised or misconfigured host serving the wrong bits. The signature is
what makes it safe for the framework to replace its own executable
automatically.

There is deliberately no key-recovery flow. Losing the key means you can
never publish another update your existing users' apps will accept - back it
up like you would a TLS certificate's private key or an App Store signing
certificate.

## Configuration reference

All of this lives under `"updater"` in `sugr.config.json`. Everything is
optional except `endpoints` and `pubkey`, which together turn the feature on.

```json
{
  "updater": {
    "endpoints": ["https://your-host/latest.json"],
    "pubkey": "MCowBQYDK2VwAyEA...",
    "channel": "stable",
    "autoCheck": true,
    "prompt": true,
    "allowDowngrade": false,
    "allowInsecure": false,
    "windowsInstallMode": "passive"
  }
}
```

| Key | Default | |
|---|---|---|
| `endpoints` | - | One or more manifest URLs, tried in order until one answers. Supports `{{target}}`, `{{arch}}`, `{{currentVersion}}` placeholders. |
| `pubkey` | - | Base64 Ed25519 public key from `sugr keygen`. Required for the updater to do anything. |
| `channel` | `"stable"` | Selects which manifest `sugr package` writes/reads: `latest.json` for `stable`, `latest-<channel>.json` otherwise. |
| `autoCheck` | `true` | Check automatically ~5s after the window is ready. Set `false` to only check when your own code calls `updater.check()`. |
| `prompt` | `true` | Show the framework's own native "update available" dialog and drive the whole flow. Set `false` to build your own UI against the events/API below. |
| `allowDowngrade` | `false` | Accept a manifest whose version is *older* than the running one (useful for a channel rollback). |
| `allowInsecure` | `false` | Allow `http://` endpoints - testing only, never for production. |
| `windowsInstallMode` | `"passive"` | `passive` (progress UI, no prompts), `quiet` (silent), or `basicUi` - only used for the rare installer-fallback path, see below. |

## How an update is applied

`sugr package` now installs Windows apps per-user, into
`%LOCALAPPDATA%\Programs\<name>` rather than `Program Files` - the same
choice VS Code's "User Installer" and Squirrel-based Electron apps make. That
directory is writable by the user who installed it, which is what lets an
update apply with **no UAC prompt and no installer window**: the new
executable is written alongside the old one and two renames swap them, then
the app restarts itself.

Three strategies exist, and the app picks automatically:

| Strategy | When | UX |
|---|---|---|
| **Binary swap** | The install directory is writable (the default per-user install) | Silent, no elevation, a few seconds |
| **Run installer** | Windows, but the install directory isn't writable (a machine-wide install) | Re-downloads and silently re-runs the `Setup.exe`, one UAC prompt |
| **Manual** | Nothing else applies - a `.deb`/`.rpm` owned by a package manager, macOS with no write access | The user is pointed at a download URL instead |

macOS and Linux follow the same shape (`.app` bundle swap on macOS, binary
swap for a portable Linux install) but - like the rest of `sugr`'s non-Windows
packaging - are unverified so far; see `PackageCommand`'s javadoc for the
same caveat applied to installers.

## Building your own UI

Set `"prompt": false` and drive the flow from the frontend with
`@sugr/runtime`:

```ts
import { updater } from '@sugr/runtime'

const info = await updater.check() // UpdateInfo | null
if (info) {
  await updater.downloadAndInstall(progress => {
    setPercent(progress.percent)
  })
  // downloadAndInstall's install step restarts the app - this line doesn't run.
}
```

Or listen to events instead of awaiting, which also works with the built-in
prompt left on (nothing stops both from reacting to the same checks):

```ts
updater.onUpdateAvailable(info => { /* ... */ })
updater.onProgress(p => { /* p.downloaded, p.total, p.percent */ })
updater.onDownloaded(info => { /* ready - call updater.install() when you want */ })
updater.onError(err => { /* err.code, err.message - the app keeps running */ })
updater.onManualRequired(info => { /* info.url - send the user there */ })
```

`updater.isSupported()` resolves `false` under `sugr dev`, in an unpackaged
build, or when no `updater` block is configured - check it before showing any
update UI at all. Every other method rejects with `code: "UPDATER_DISABLED"`
in those cases instead of silently doing nothing.

See [`docs/reference/api.md`](/reference/api) for the full method/event list.

## Manifest format

`sugr package` generates this; you only need the shape if you're building
your own release pipeline instead of using `sugr package` directly (a
monorepo with a different CI setup, say):

```json
{
  "version": "1.2.0",
  "pubDate": "2026-09-09T10:00:00Z",
  "notes": "Fixes the thing",
  "minVersion": "1.0.0",
  "rolloutPercentage": 100,
  "platforms": {
    "windows-x86_64": {
      "format": "binary",
      "url": "https://.../my-app-1.2.0-windows-x86_64.zip",
      "sha256": "...",
      "size": 26214400,
      "signature": "<base64 Ed25519 over the artifact's bytes>",
      "installerUrl": "https://.../my-app-1.2.0-Setup.exe",
      "installerSha256": "...",
      "installerSignature": "..."
    }
  }
}
```

- The signature covers the **artifact's bytes**, not the manifest - a
  tampered manifest pointing at a different URL still needs a matching
  signature for whatever that URL actually serves.
- `minVersion` tells an app older than it that it can't update automatically
  and should reinstall instead (emits `sugr://update-manual-required`).
- `rolloutPercentage` (0-100) staggers a release across users, bucketed by a
  random id persisted per-install - the same user is always in or out, so a
  10% rollout doesn't flicker an update in and out of availability between
  checks.
- A dynamic endpoint (server-side logic instead of a static file) can answer
  `204 No Content` for "you're current" instead of a body - the same
  convention Tauri's updater uses.

## Options on `sugr package`

```
sugr package [--base-url <url>] [--channel <name>] [--notes <text>]
             [--min-version <version>] [--rollout <0-100>]
             [--no-update-artifacts]
```

| Flag | |
|---|---|
| `--base-url` | Where the files will be served from, to build absolute URLs in the manifest. Omit it during local testing and fill URLs in by hand. |
| `--channel` | Overrides `sugr.config.json`'s `updater.channel` for this release. |
| `--notes` | Release notes, shown in the built-in update dialog and available to a custom UI. |
| `--min-version` | Sets `minVersion` in the manifest. |
| `--rollout` | Sets `rolloutPercentage` in the manifest. |
| `--no-update-artifacts` | Build the installer only - skip the signed update artifact and manifest. |

`sugr package` reads the signing key from `SUGR_SIGNING_KEY` (a path or the
key file's contents - CI secrets usually hold the latter) and, if the key was
encrypted with a passphrase, `SUGR_SIGNING_KEY_PASSWORD`. It refuses to
finish - rather than silently produce an unsigned release - if `pubkey` is
configured but a matching key isn't available.

## What's not built yet

- **Delta/patch updates.** Every update downloads the full binary. Given
  native-image output is tens of megabytes, not hundreds, this is an
  acceptable v1 trade-off rather than an oversight - Electron's blockmap
  diffing and Wails' patch format both need machinery (bsdiff-style binary
  diffing) this doesn't have yet.
- **Code signing / notarization.** Nothing here touches Authenticode,
  `codesign`, or `notarytool` - that's a separate concern from update
  *delivery*, and `sugr package` doesn't sign installers at all yet (see its
  class javadoc).
- **`.AppImage` targets.** Linux packaging currently only produces a `.deb`,
  which the updater treats as unattended-update-proof by design (it belongs
  to the package manager).
