# Environments

Sugr apps support three named environments - **`dev`**, **`staging`**, and
**`prod`** - each with its own config file on the frontend and the backend.
No app code needs to hand-roll this: `sugr dev`/`sugr debug`/`sugr build`
accept a `--env`/`-e` flag, and the framework wires the right files up for
you.

| Command       | Default env |
|---------------|-------------|
| `sugr dev`    | `dev`       |
| `sugr debug`  | `dev`       |
| `sugr build`  | `prod`      |

```sh
sugr dev --env staging
sugr build --env staging
```

`sugr package` doesn't touch environment selection itself - it just wraps
whatever `sugr build` already produced via native-image.

## Backend: `application.properties`

Plain Java `.properties` files under `lib/src/main/resources/`:

- `application.properties` - shared defaults for every environment
- `application-dev.properties`, `application-staging.properties`,
  `application-prod.properties` - per-environment overrides, layered on top

Read them at startup via `com.sugr.core.AppConfig`:

```java
AppConfig config = AppConfig.load("SUGR_ENV", "prod");
String title = config.getOrDefault("app.windowTitle", "sugr app");
```

`AppConfig.load(envVarName, defaultEnv)` reads the environment name from the
given environment variable (falling back to `defaultEnv` if unset/blank),
then loads `application.properties` overlaid with
`application-<env>.properties` - either file may be absent. `AppConfig.auto()`
is a shorthand for `load("SUGR_ENV", "dev")`.

Branch on the resolved environment with `isDevelopment()`/`isStaging()`/`isProduction()`
instead of comparing `env()` to a string yourself:

```java
if (config.isDevelopment()) {
    // e.g. verbose logging, a local dev database, etc.
}
```

`sugr dev`/`sugr debug` set `SUGR_ENV` on the app process automatically
(same convention as `SUGR_DEV_URL` - see [How it works](/how-it-works)).
Generated `Main.java` files call `AppConfig.load("SUGR_ENV", "prod")`
explicitly (not the `dev`-defaulting `AppConfig.auto()`), so a
packaged/shipped binary defaults sanely to `prod` if `SUGR_ENV` is ever unset
in the field.

::: warning No secrets in these files
All three `application-*.properties` files are embedded together into every
build/binary - the running app just picks the right one via `SUGR_ENV`.
That means all three ship in plaintext regardless of the target environment.
Don't put real secrets in them; use proper secret management for anything
sensitive.
:::

## Frontend: `.env` files

Standard Vite convention, but with **custom mode names** (`dev`/`staging`/`prod`,
not Vite's built-in `development`/`production`) since `sugr dev`/`sugr build`
always pass an explicit `--mode <env>`:

- `.env` - shared defaults
- `.env.dev`, `.env.staging`, `.env.prod` - per-environment overrides

Only vars prefixed `VITE_` are exposed to frontend code, via
`import.meta.env`:

```ts
// src/env.ts
export const env = {
  mode: import.meta.env.MODE, // "dev" | "staging" | "prod"
  apiUrl: import.meta.env.VITE_API_URL,
};

export const isDevelopment = () => env.mode === 'dev';
export const isStaging = () => env.mode === 'staging';
export const isProduction = () => env.mode === 'prod';
```

::: tip Deviates from vanilla Vite docs
Most Vite examples online use `.env.development`/`.env.production` because
that's what `vite`/`vite build` use *by default* with no `--mode` flag. Sugr
always passes `--mode dev`/`--mode staging`/`--mode prod` explicitly, so
those default names are never consulted - use `.env.dev`/`.env.staging`/`.env.prod`
instead.
:::
