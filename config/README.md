# App configs

One JSON file per app in `config/apps/<name>.json`. Your own configs are git-ignored (only `example.json` is tracked).
Editors get autocomplete from `config/schema.json` (`"$schema": "../schema.json"`; regenerate with `npm run schema`).

## Fields

| Field | Default | Meaning |
|---|---|---|
| `name` | file name | Key, `[a-z0-9-]`, must equal the file name. |
| `appName` | required | Launcher label / window title. |
| `appId` | required | Android applicationId / Electron appId (valid Java package, e.g. `com.me.movy`). The TV build appends `.tv`. |
| `url` | required unless `sources` | Start URL of a single-site app (legacy; normalized to one source). `http://` enables cleartext traffic. |
| `sources[]` | from `url` | `{ id, name, url, allowedHosts?, accent?, enabled? }` in display order. Only `name` is ever shown in the UI. `allowedHosts` defaults to the URL host + subdomains; the app allows the union of all enabled sources. |
| `recommendedSource` | first enabled | Source id preselected in the chooser (gets the "Recommended" pill). |
| `tagline` / `credit` | `""` | Splash texts ("Ad-free movie streaming" / "by Ayush Kumar"). |
| `island.visibility` | `home` | `home` or `always` (the Island drawer, W5b). |
| `remote` | none | `{ provider: "firebase-remote-config", projectId, parameter, googleServices, template? }`. Only when present does the build copy `google-services.json`, apply the google-services plugin and add `firebase-config` (never Analytics). See `docs/REMOTE-CONFIG.md`. |
| `version` / `versionCode` | `1.0.0` / `1` | Increase `versionCode` for every release you install over an old one. |
| `allowedHosts` | URL host + `*.host` | Hosts that may load in-app as top-level navigation (server redirects from the start URL, e.g. to a mirror domain, are also followed for the session). `*.x.com` matches `x.com` and all subdomains. |
| `authHosts` | `[]` | Extra sign-in hosts, added to the built-in list (accounts.google.com, appleid.apple.com, facebook.com/login, discord.com/oauth2, challenges.cloudflare.com). A user tap may open them as a top-level page; they are never opened by scripts or redirects. |
| `externalLinks` | `block` | Top-level links to non-allowed hosts: `block` (silently ignored, the page stays) or `in-app`. `open-external` is deprecated: it still validates (with a build warning) but behaves as `block`. The app never launches a browser or another app; `intent:`, `market:`, `tel:`, `mailto:` and other custom schemes are always dropped. |
| `userAgent` | `null` | `null` = platform UA without WebView markers (`; wv`, `Version/x.y`). |
| `backgroundColor` | `#000000` | Window/status-bar/splash color; also picks light or dark status-bar icons. |
| `orientation` | `any` | `any`, `portrait`, `landscape` (TV treats `any` as landscape). |
| `adblock.enabled` | `true` | Master switch. |
| `adblock.lists` | `"default"` | `"default"` (EasyList, EasyPrivacy, uBO filters/badware/privacy/unbreak/quick-fixes, Peter Lowe) or an array of list URLs. |
| `adblock.extraLists` | `[]` | Extra list URLs appended to `lists`. |
| `adblock.customRules` | `[]` | Inline ABP/uBO rules, e.g. `"||ads.example.com^"`. |
| `adblock.cosmetic` | `true` | Element hiding + scriptlets. |
| `adblock.blockPopups` | `true` | Popups never open a window. A user-tap popup to an allowed host loads in the main view; everything else is dropped. Iframes and subresources are not limited by `allowedHosts`, only by the ad filters. |
| `targets.<phone\|tv\|desktop>` | `{}` | Per-target overrides of any field above (except `name`); `adblock` is merged key by key. |

## Adding an app

1. `copy config/apps/example.json config/apps/myapp.json`, change `name`, `appName`, `appId`, `url`, `allowedHosts`.
2. Optional assets: `config/assets/myapp/icon.png` (1024x1024) and `config/assets/myapp/tv-banner.png` (320x180). Without them a letter icon / name banner is generated.
3. `npm run build -- --app myapp --target phone` (or `tv`, `desktop`, `all`).

## Build numbers

`versionCode` is the build number: auto-incremented on every `npm run build` per app (stored in the git-ignored
`config/apps/.build-numbers.json`; the config's `versionCode` is the minimum). `--build-number N` overrides. Shown as `v1.0.0 (build N)`.
Start URLs are written to the APK obfuscated (XOR + base64 with a per-build random key), never as plain text.

## Signing

Release APKs are signed with `config/signing.json` if present:
`{ "storeFile": "path/to/my.jks", "storePassword": "...", "keyAlias": "...", "keyPassword": "..." }` (path relative to `config/`; git-ignored).
Otherwise a stable per-app key is generated once at `%USERPROFILE%\.movy\keystore\<app>.jks` (passwords in `<app>.properties` beside it).
**Back that folder up**: updates must use the same key, and a lost key means uninstalling the app, which erases its logins.
An old `.cache/keystore/movy-debug.jks` is copied there for apps that were already built with it. `install:android` always uses `adb install -r` and never uninstalls.
