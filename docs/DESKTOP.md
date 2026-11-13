# YouTube Freemium — Desktop (Electron) Edition Architecture & Guide

*Crafted by Ayush Kumar*  
**Application ID:** `com.ayushkumar.youtubefreemium`  
**Package:** `@ytfreemium/desktop`  
**Target:** Windows 11 (x64) NSIS Installer & Portable Executable (cross-target macOS DMG & Linux AppImage ready)

---

## 1. Architecture Overview

The desktop edition delivers feature-for-feature parity with the Android mobile edition. It is designed around zero DOM tampering of streaming URLs, high performance adblocking, strict sandbox isolation, and seamless media controls.

```
+---------------------------------------------------------------------------------------+
|                               Electron Main Shell Process                              |
|                                                                                       |
|  +------------------+    +-------------------+    +----------------+    +-----------+ |
|  |  Config Loader   |    | Navigation Policy |    | Cache Policy   |    | Rust      | |
|  |  (De-obfuscator) |    | (In-app routing)  |    | (100MB LRU /s/) |    | Adblock   | |
|  +------------------+    +-------------------+    +----------------+    +-----------+ |
|                                                                                       |
|  +------------------+    +-------------------+    +----------------+    +-----------+ |
|  | Media & SMTC     |    | Windows PiP       |    | System Tray    |    | Overlays  | |
|  | (powerSaveBlock) |    | Manager           |    | & Shortcuts    |    | (Island/  | |
|  |                  |    |                   |    |                |    |  Pill/Bar)| |
|  +------------------+    +-------------------+    +----------------+    +-----------+ |
+---------------------------------------------------------------------------------------+
        |                                                              |
        | Views (WebContentsView)                                      | Overlays & UI
        v                                                              v
+-----------------------------+ +-----------------------------+ +-----------------------+
| YouTube WebContentsView     | | YouTube Music WebContentsView| | Sandboxed UI Views   |
| (Session: persist:ytf)      | | (Session: persist:ytf)      | | - Island Source Switch|
| - Bridge Preload            | | - Bridge Preload            | | - Ad Skip Pill        |
| - Fast Ad Skipper           | | - Fast Ad Skipper           | | - Progress Bar        |
| - Speed Pill Injection      | | - Media Session Bridge      | | - Settings Panel      |
| - Video PiP & Mini-Player   | |                             | | - Splash Window       |
+-----------------------------+ +-----------------------------+ +-----------------------+
```

### Core Components

0. **Shell chrome (`src/ui/chrome.*`, `src/core/layout.js`):** a 36 px strip (back / forward / reload, drag area) is the window's own page; the native window buttons are drawn inside it and the site views sit below, so nothing of the page is covered. Back/forward state is per view (`src/core/nav-history.js`); load failures go through `src/main/load-guard.js` to a branded error screen.

1. **Dual WebContentsView Shell (`src/main/views.js`):**
   - Two persistent web contents views share the same session partition (`persist:ytfreemium`).
   - Switching between **YouTube** and **YouTube Music** is instantaneous without full-page reloads.
   - When "Allow Both" playback toggle is disabled in Settings, audio arbitration automatically pauses one source when the other starts playback.

2. **Native Adblock Engine (`src/main/adblock.js`):**
   - Powered by prebuilt Rust NAPI bindings (`@ytfreemium/adblock-node` / `movy_adblock_node.node`).
   - High-throughput network request interceptor inspecting subresource requests via Electron `webRequest.onBeforeRequest`.
   - Injects cosmetic filter stylesheets into document frames to hide promo elements without reflow stutter.

3. **In-Page JavaScript Bridge (`src/preload/site.js` & `apps/desktop/gen/inject/`):**
   - `youtube.js`: Prunes `playerResponse` ad objects (`adPlacements`, `adSlots`, `playerAds`), fast-forwards and skips residual in-stream video ads, restores volume and playback rate.
   - Visibility & Dimensions Spoofing: Overrides `document.visibilityState` to `'visible'` and `window.outerWidth`/`outerHeight` to prevent YouTube's background throttling and miniature PiP pause loops.
   - Speed pill button and in-player PiP toggle button injection.
   - `scamguard.js`: Detects and neutralizes fake play-store malware and phishing overlays.

4. **PiP & Background Media Playback (`src/main/pip.js`, `src/main/media.js`):**
   - Picture-in-Picture uses Chromium's own floating always-on-top window (`video.requestPictureInPicture`, started with a user gesture from the main process). The page is never restyled or re-parented; `__ytf_setPip(on, true)` only sets the page's PiP flag (hides the in-page PiP button, resume guards). Rules live in `src/core/pip-rules.js`.
   - Native Windows System Media Transport Controls (SMTC) integration via Electron's `powerSaveBlocker('prevent-app-suspension')` and MediaSession protocol. Background audio continues playing when screen is locked or window is minimized.

5. **Cache Policy (`src/main/cache-policy.js`):**
   - Pure implementation of disk asset cache: caches strictly JS, CSS, and fonts under `/s/` up to 100 MB with a 14-day LRU expiration.
   - Strictly blocks caching of video, audio, and images (`.mp4`, `.m4s`, `.webm`, `.mp3`, `.jpg`, `.png`, `.webp`) and requests with authorization or range headers.

6. **Safety & Privacy:**
   - No plaintext source URLs or tokens appear in UI strings, HTML templates, or main process logs.
   - Host navigation rule enforcement prevents navigating to external malicious domains or breaking out of YouTube/Google auth.

---

## 2. Workspace & Directory Layout

```
D:\YouTube-Freemium\
├── apps\
│   └── desktop\
│       ├── gen\                   # Generated runtime assets (obfuscated config, icons, scripts)
│       ├── src\
│       │   ├── core\              # Pure core modules (nav rules, cache, config, prefs)
│       │   ├── main\              # Electron main process shell and window controllers
│       │   ├── preload\           # Preload scripts (isolated main world bridge)
│       │   └── ui\                # Sandboxed UI web views (Island, Pill, Settings, Splash)
│       ├── test\                  # Unit and soak acceptance test suites
│       └── package.json
├── native\
│   └── adblock-node\              # Rust NAPI native adblock binding crate & .node binary
├── scripts\
│   ├── desktop\
│   │   ├── native.mjs             # Node binding verifier and loader
│   │   ├── icon.mjs               # Vector icon and multi-resolution ICO generator
│   │   ├── generate.mjs           # Asset generator (obfuscated app-config.json, injects)
│   │   └── builder-config.mjs     # Electron-builder configuration
│   ├── build-desktop.mjs          # Desktop build pipeline driver
│   └── build.mjs                  # Root build dispatcher
└── dist\
    └── desktop\                   # Packaged installers and executables
```

---

## 3. Development & Build Commands

All commands can be executed from the repository root:

### Development Run
Runs asset generation and starts the Electron desktop app in development mode:
```powershell
npm run desktop:dev
```
Or with developer flags (e.g. bypassing splash for rapid iteration):
```powershell
node scripts/desktop/gen-only.mjs --app youtube-freemium
electron apps/desktop --no-splash
```

### Running Test Suites
Run the comprehensive suite of unit tests, acceptance tests (U1-U13), and soak/budget tests (S2):
```powershell
npm run desktop:test
```
Direct Node test runner invocation:
```powershell
node --test apps/desktop/test/*.test.mjs
```

### Building Release Installers
To build the production-ready NSIS installer (`YouTube-Freemium-Setup-1.0.0.exe`) and standalone portable executable (`YouTube-Freemium-1.0.0-portable.exe`):
```powershell
npm run build -- --app youtube-freemium --target desktop
```

Output artifacts are placed in `D:\YouTube-Freemium\dist\desktop\`.

---

## 4. Maintenance Guide

### Updating Ad & Element Selectors
When YouTube updates its web client DOM structure or ad player markup:
1. Locate ad container selectors and skip button query selectors in:
   - `packages/core/src/inject/youtube.js`
   - `apps/desktop/src/preload/site.js`
2. Update the target selector list (e.g. `.video-ads`, `.ytp-ad-skip-button`, `.ytp-ad-overlay-container`).
3. Regenerate desktop assets:
   ```powershell
   node scripts/desktop/gen-only.mjs --app youtube-freemium
   ```
4. Run unit tests to verify player pruning logic:
   ```powershell
   node --test apps/desktop/test/acceptance-unit.test.mjs
   ```

### Updating Adblock Filter Lists
The adblock engine compiles standard filter rule sets (EasyList, AdGuard, custom cosmetics):
1. Update source lists in `scripts/filters.mjs` or add custom cosmetic CSS rules.
2. Build the optimized serialized rule cache:
   ```powershell
   node scripts/desktop/gen-only.mjs --app youtube-freemium
   ```
3. The compiler validates syntax and writes the binary filter index into `apps/desktop/gen/adblock/`.

### Bumping Version
To release a new version (e.g., 1.0.1):
1. In `apps/desktop/package.json`, update `"version": "1.0.1"`.
2. In `configs/youtube-freemium.json`, update `"version": "1.0.1"` and increment `"versionCode"`.
3. Re-run the release build:
   ```powershell
   npm run build -- --app youtube-freemium --target desktop
   ```
4. Electron-builder will package `YouTube-Freemium-Setup-1.0.1.exe` and `YouTube-Freemium-1.0.1-portable.exe`.
