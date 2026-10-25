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
release and never leaves your machine or CI secrets. Two Ed25519
signatures are checked against that embedded public key - **not just
SHA-256, and not just HTTPS**:

- the **manifest signature**, before the app believes anything the manifest
  says. It covers the version, the download URLs and checksum, the release
  notes and your app's `identifier`, so a compromised or hostile endpoint
  can't relabel an old signed build as "new" to roll users back to a
  vulnerable release, put its own text in the update dialog, or point the
  "download it yourself" link somewhere else;
- the **artifact signature**, over the downloaded file's bytes, before it
  replaces anything.

HTTPS stops a man-in-the-middle from swapping files in transit; it does
nothing about a compromised or misconfigured host serving the wrong bits.
The signatures are what make it safe for the framework to replace its own
executable automatically.

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
    "allowInsecure": false
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
| **Managed** | Windows, but the install directory isn't writable (a machine-wide `Program Files` deployment) | No self-update. The app reports "Managed install - updates are delivered by your administrator" (`onManualRequired` with `managed: true`, no URL) and shows no prompt |
| **Manual** | Nothing else applies - a `.deb`/`.rpm` owned by a package manager, macOS with no write access | The user is pointed at a download URL instead |

Per-machine installs are treated as IT-managed on purpose: whoever put the
app in `Program Files` (Intune, SCCM, a GPO, an admin running the installer)
owns its updates. Re-running the per-user installer from there would only
create a second copy under `%LOCALAPPDATA%` and leave the managed one stale.

### Turning updates off by policy

An administrator can switch the updater off entirely - no checks, no
network traffic, `updater.isSupported()` resolves `false` - in either of
these ways:

- the environment variable `SUGR_DISABLE_UPDATER=1`;
- on Windows, a `REG_DWORD` value `DisableUpdates` set to `1` under
  `HKLM\Software\Policies\<identifier>` (machine policy) or the same path
  under `HKCU` (user policy), where `<identifier>` is the app's
  `identifier` from `sugr.config.json`. Either one is enough:

```bat
reg add "HKLM\Software\Policies\com.example.app" /v DisableUpdates /t REG_DWORD /d 1 /f
```

The registry is read once at startup, only for a packaged app that has an
`updater` block.

### Proxies

Update checks and downloads go through the machine's proxy, resolved in
this order:

1. JVM system properties `https.proxyHost` / `http.proxyHost`, if set;
2. the `HTTPS_PROXY` / `HTTP_PROXY` / `NO_PROXY` environment variables
   (either case; `NO_PROXY` matches a domain and its subdomains, `*` matches
   everything);
3. on Windows, the user's Internet Settings - `ProxyEnable`, `ProxyServer`
   (`host:port` or `http=h:p;https=h2:p2`) and `ProxyOverride` (globs plus
   `<local>`) under
   `HKCU\Software\Microsoft\Windows\CurrentVersion\Internet Settings`;
4. otherwise a direct connection.

PAC/WPAD auto-config scripts and proxies that require authentication aren't
supported yet. Loopback hosts always bypass the proxy.

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
  // Resolves just before the app restarts into the new version.
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
      "manifestSignature": "<base64 Ed25519 over the canonical payload below>"
    }
  }
}
```

- `signature` covers the **artifact's bytes**; `manifestSignature` covers
  the entry's metadata (see below). Both are required - an entry without
  `manifestSignature` is rejected with `MANIFEST_SIGNATURE_INVALID`.
- `installerUrl`/`installerSha256` are only ever shown to the user as a
  manual download link (a `minVersion` gate, a read-only install); the
  updater never runs an installer itself.
- `minVersion` tells an app older than it that it can't update automatically
  and should reinstall instead (emits `sugr://update-manual-required`).
- `rolloutPercentage` (0-100) staggers a release across users, bucketed by a
  random id persisted per-install - the same user is always in or out, so a
  10% rollout doesn't flicker an update in and out of availability between
  checks.
- A dynamic endpoint (server-side logic instead of a static file) can answer
  `204 No Content` for "you're current" instead of a body - the same
  convention Tauri's updater uses.

### The signed manifest payload

`manifestSignature` is an Ed25519 signature, with the same key, over these
UTF-8 bytes - a header line, then one `key=<byte length>:<value>` line per
field, in this exact order, each ending in `
`, with an absent field
written as length `0`:

```
sugr-update-manifest-v1
identifier=15:com.example.app
version=5:1.2.0
target=14:windows-x86_64
format=6:binary
url=43:https://.../my-app-1.2.0-windows-x86_64.zip
sha256=64:...
size=8:26214400
minVersion=5:1.0.0
notes=15:Fixes the thing
installerUrl=0:
installerSha256=0:
```

- `identifier` is the app's `identifier` from `sugr.config.json`, so one
  app's release can't be replayed to another app that shares the key.
- `target` is the platform key (lowercase); `version`, `minVersion`,
  `notes` (top-level) and the entry fields are the strings exactly as they
  appear in the JSON, `size` in decimal.
- The length prefix keeps it unambiguous with newlines in `notes` - no
  canonical-JSON rules to reproduce.
- `pubDate` and `rolloutPercentage` are **not** signed: per-OS builds stamp
  different dates, and widening a rollout after publishing is the point of
  having one. Everything else in the entry is - editing `notes`, `version`
  or a URL in a published manifest by hand makes the app refuse it, so
  re-run `sugr package` instead.

After the signature passes, a `version` that isn't newer than the running
app is ignored (unless `allowDowngrade` is set), so replaying a genuine
*older* manifest doesn't roll anyone back either.

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
