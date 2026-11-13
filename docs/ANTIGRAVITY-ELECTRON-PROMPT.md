# Anti-Gravity kickoff prompt: YouTube Freemium for Desktop (Electron)

Copy everything below the line into Anti-Gravity. Open the workspace at `D:\` so both `D:\Movy` and `D:\YouTube-Freemium` are visible.

---

## 0. ROLE AND OPERATING MODE

You are the lead engineer and orchestrator. Build the **desktop (Electron) edition of YouTube Freemium**, feature-for-feature equal to the finished Android app. Work in this order: read, plan, build, test, fix, repeat. Use parallel sub-agents only for independent work packages (listed in section 9). Do not stop at "it compiles": every item in the acceptance suite (section 10) must be run and reported PASS / FAIL / NOT VERIFIED with evidence (command output, screenshot, or log line). If something cannot be verified on this machine, say exactly why. Never claim success you have not observed.

Hard rules:
1. **Do not modify `D:\Movy` or the Android project in `D:\YouTube-Freemium\apps\mobile`.** Read them as references only.
2. Put everything new in `D:\YouTube-Freemium\apps\desktop` (and shared pieces in `D:\YouTube-Freemium\packages\...` if they already exist there). Keep the repo's existing conventions: ESM, `node:test` tests, config in `config/apps/youtube-freemium.json`.
3. Immutability, small files (<400 lines typical, 800 max), explicit error handling, no hardcoded secrets, no logging of full URLs in the UI.
4. Windows 11 is the primary target. Also produce macOS and Linux build configs, but only Windows must be built and tested here.
5. Never open a browser or an external app for navigation; all browsing stays inside the app (see "Navigation rules").

## 1. WHAT THE PRODUCT IS

**YouTube Freemium** is an ad-free native wrapper around exactly two websites:

| id | name | start URL |
|---|---|---|
| `youtube` | YouTube | `https://www.youtube.com/` |
| `youtube-music` | YouTube Music | `https://music.youtube.com/` |

Credit: **Ayush Kumar**. Tagline: "Ad-free YouTube and YouTube Music". App id: `com.ayushkumar.youtubefreemium`. Version 1.0.0. Icon: `D:\YouTube-Freemium\config\assets\youtube-freemium\icon.png` (red rounded square, white play button) used for the window, taskbar, tray, installer and macOS/Linux icons (generate `.ico` with sizes 16/24/32/48/64/128/256, `.icns`, and PNGs).

Everything below describes behaviour that already exists and is verified in the Android build. Reproduce the behaviour; do not invent new product features.

## 2. READ THESE FIRST (references to copy from, do not edit)

- `D:\Movy\apps\desktop\src\main\*.js`, `preload\*.js`, `ui\*`: the existing Electron shell (window state, views, island, overlays, adblock, cosmetic IPC, context menu, downloads, permissions, remote config, health, shortcuts, menu, stats). **Start from this code**: copy the files you need into `D:\YouTube-Freemium\apps\desktop`, then adapt. Reuse its tests (`test\*.test.mjs`) as the pattern.
- `D:\Movy\native\adblock` (Brave `adblock-rust` + napi build) and `D:\Movy\scripts\desktop\*.mjs` (generate, icon, native, builder-config). Reuse the napi adblock engine if it builds on this host (Rust uses the **gnu** toolchain; MSVC is incomplete). If the native build is not possible, fall back to `@ghostery/adblocker-electron` and say so.
- `D:\YouTube-Freemium\packages\inject\youtube.js` and `youtube-selectors.json`: **the authoritative page script**. Reuse it on desktop with a small desktop adapter (see section 5). Also read `navguard.js`, `scamguard.js`, `loading.js`.
- `D:\YouTube-Freemium\apps\mobile\android\app\src\main\java\app\movy\shell\`: read `SplashView.java`, `SplashSound.java`, `AdPill.java`, `SourceLogos.java`, `IslandView.java`, `IslandPainter.java`, `MediaBridge.java`, `PlaybackService.java`, `MainActivity.java` for exact behaviour, timings and strings.
- `D:\YouTube-Freemium\config\apps\youtube-freemium.json` and `config\README.md`: config schema (sources, allowedHosts, authHosts, adblock, cache).

## 3. TECH CHOICES (decided, do not re-litigate)

- Electron (latest stable that the Movy desktop already pins), `electron-builder` for packaging. Windows: NSIS installer **and** portable exe, both signed with a locally generated self-signed cert if a real cert is absent (document it). macOS dmg and Linux AppImage configs included but not required to build.
- One `BrowserWindow` hosting **one `WebContentsView` per source** (YouTube and YouTube Music), kept alive in memory so switching is instant and each keeps its own playback and scroll state. A separate transparent overlay view hosts the Island switcher and the Skipping-ads pill. This mirrors `views.js` and `overlays.js` in the Movy desktop shell.
- Session: one persistent partition for both views (`persist:ytfreemium`) so a Google sign-in is shared and survives restarts.
- User agent: a current Chrome-on-Windows string **without** `Electron/` and without the app name. Sign-in must reach the password step (Google blocks embedded browsers by UA and by `X-Requested-With`; strip that header, keep `sec-ch-ua` consistent with the UA).
- Page scripts run via a preload (`preload/site.js`) in the main world using `webFrame.executeJavaScript` or `contextBridge`+`world: 'MAIN'` injection at document start, in **all frames** (needed for the embedded mini player).
- `webPreferences`: `backgroundThrottling: false`, `contextIsolation: true`, `sandbox: true` for site views, `autoplayPolicy: 'no-user-gesture-required'`, `spellcheck: false`.

## 4. FEATURE SPECIFICATION (every item is mandatory)

### 4.1 Window and chrome
- Frameless-looking modern window with the native title bar hidden (`titleBarStyle: 'hidden'` + `titleBarOverlay` themed `#0F0F0F`), min size 480x320, default 1280x800, remembers size, position, maximized state and display (see `window-state.js`).
- Dark theme `#0F0F0F` everywhere; no white flash on start or while switching sources.
- System tray icon with: Show/Hide, Play/Pause, Next, Previous, Switch to YouTube, Switch to YouTube Music, Quit. Closing the window minimizes to the tray **only while media is playing**; otherwise it quits. A setting "Keep playing in the tray" (default on) controls this.
- Single instance lock; a second launch focuses the first and passes a `youtube.com`, `youtu.be` or `music.youtube.com` URL if given. Register as handler for those links optionally via a setting (default off).

### 4.2 First-launch splash (once per process start, about 3.5 s)
Implement as a frameless centered splash window (or an overlay inside the main window) that is shown **only on a cold start of the app**, never when the window is restored, hidden to the tray, or a second instance is launched.
- 0 s: black screen, the icon scales in (0.86 to 1.0, 480 ms decelerate), "YouTube Freemium" (40 px, medium-bold, white) and the tagline (18 px, `#B8B8BD`) fade in; a rising 4-note chime plays (C5 E5 G5 C6, 95 ms apart, sine + 25% 2nd harmonic, exponential decay, gain about 0.3).
- 0.4 s: a red spinner (`#FF0033` arc, 36 px) appears under the tagline if the app is not yet ready.
- 2.0 s: icon, name, tagline and spinner fade out and shrink to 0.88 over 420 ms.
- 2.35 s: "Crafted by" (18 px, `#B8B8BD`, letter-spacing 0.12 em) above "Ayush Kumar" (44 px, bold, white) followed by a red heart (`#FF2D55`) grows from scale 0.5 with overshoot to 1.0 over 620 ms; a warm 3-note chord plays (G4 C5 E5, 70 ms apart, gain about 0.26).
- 3.15 s: the credit flips away (rotateY 0 to 90 deg, 280 ms, perspective 8000 px) while fading out.
- 3.5 s: the splash is gone and the app is already loaded behind it (preload both views during the splash). Total time to usable app is 3.5 s on the first start; on every later start path (tray restore, second instance) there is no splash.
- Sounds are synthesized (Web Audio in the splash page), never shipped as files, played quietly, and skipped if the OS is muted.
- Add a `--no-splash` flag and a setting "Show intro on launch" (default on) for tests.

### 4.3 Island (source switcher)
- A thin red grabber on the right edge, vertically centered. Hover or click expands a vertical pill (Island) containing two tiles with the **real marks on white rounded tiles**:
  - YouTube: red rounded rectangle (1.44 x 1.02 ratio, corner 30% of its height) with a white play triangle.
  - YouTube Music: red circle (`#FF0033`), white ring at 70% radius (stroke 11.5% of radius), white play triangle.
  - Label under each tile ("YouTube", "YouTube Music"), the active one highlighted, a small health dot (green/amber/red) per source.
- Click a tile to switch (instant, no reload; the other view keeps playing in the background). Keyboard: `Ctrl+1` YouTube, `Ctrl+2` YouTube Music, `Ctrl+Tab` toggles. Esc or click outside collapses the Island. Spring animation like `island.js`/`spring.js`.
- A settings gear on the Island opens the settings panel (4.12).
- No URLs are ever shown. Only the two names.

### 4.4 Ad blocking and skipping (YouTube and YouTube Music)
Layers, all required:
1. Network and cosmetic filtering with the Brave `adblock-rust` engine and default lists (EasyList, EasyPrivacy, uBO filters/badware/privacy/unbreak/quick-fixes, Peter Lowe). Lists are cached on disk and refreshed every 24 h; the app must work offline with the cached lists.
2. **Player-response pruning** (in the page script, already written in `youtube.js`): remove `adPlacements`, `playerAds`, `adSlots`, `adBreakHeartbeatParams` from `/youtubei/v1/player`, `/next`, `/reel/reel_item_watch` responses, via a wrapped `fetch`, a wrapped `XMLHttpRequest` (`responseText`/`response` getters) and a setter on `window.ytInitialPlayerResponse`. This stops most ads from ever loading.
3. **Fallback skipper**: when the player carries `ad-showing`/`ad-interrupting`, click `.ytp-skip-ad-button`, `.ytp-ad-skip-button-modern`; otherwise mute, set `playbackRate` 16 and seek to the end (clips shorter than 1200 s), then restore mute and speed when the ad ends. Run on `loadstart`, `loadedmetadata`, `playing`, `timeupdate` and every 100 ms while an ad is showing. If an ad is still there after 4 s, reload the page once per video (guard with `sessionStorage`).
4. Hide ad containers, the "ad blockers violate YouTube's terms" enforcement popup (remove it and resume playback), promo banners and the mobile "Open app" button. On Music, auto-confirm "Are you still listening?".
5. **Skipping-ads pill** (native overlay, not inside the page): while the page reports an ad being skipped, a black capsule (`#000` at 95%, 1 px `#FFFFFF33` border, text "Skipping ads for you" 14 px medium white, padding 10x20) drops from the top center of the window: starts scaled 0.25x0.55 and 36 px above, animates to full size in 420 ms with overshoot 1.1; stays at least 1.4 s; leaves in 260 ms. A 3.5 s watchdog hides it if the page stops sending heartbeats (page sends one per second while skipping). It never appears while the player is in Picture-in-Picture. It must disappear in every case once the video is playing normally.
6. Selectors live in one file (`youtube-selectors.json`) with a documented refresh path.

### 4.5 Playback behaviour that must be preserved (these were the hardest bugs; keep them)
Add these in the page script for the **top frame and all subframes** at document start:
- **Page Visibility spoof**: override `document.hidden`, `visibilityState`, `webkitHidden`, `webkitVisibilityState` on both the instance and `Document.prototype`; `hasFocus()` returns true; swallow `visibilitychange`, `webkitvisibilitychange`, `pagehide`, and window `blur` in the capture phase.
- **Window-size spoof**: `window.outerWidth`/`outerHeight` return `screen.width`/`screen.height`. YouTube pauses itself when it believes it is in a small Picture-in-Picture window (outer area under about 33% of the screen); this makes the player never self-pause when the window is small or in PiP.
- **Background-resume**: track `wantPlaying` from `play` events; a `pause` event is a user pause only if a touch/click/key happened in the previous 1.5 s or the native side called `__ytf_pause`; any other pause while the app is minimized/hidden or within 4 s after a PiP/minimize transition is undone (at most 2 forced resumes per 5 s so it never fights the page).
- **No "Leave this page?" prompt**: swallow `beforeunload` and define `window.onbeforeunload` as a no-op; in Electron also handle `will-prevent-unload` by calling `event.preventDefault()` so navigation always proceeds.
- **Trusted Types**: YouTube enforces them. Never use `innerHTML` in injected UI. Build DOM with `createElement`/`createElementNS`.
- Do not rely on `window.open` or external browsers.

### 4.6 Picture-in-Picture (two kinds)
**External PiP (OS-level floating window)**
- Trigger: the user minimizes or switches away while a **video** (not Music audio) is playing, or presses the PiP button/shortcut (`Ctrl+Shift+P`).
- Implementation: a small always-on-top `BrowserWindow` (frameless, resizable with 16:9 lock, default 480x270, remembers position, bottom-right of the work area first time) that shows **only the video**. Prefer the Chromium Document-PiP / `video.requestPictureInPicture()` path if it is reliable under Electron on this machine; otherwise move the active source's `WebContentsView` into the small window and isolate the video element (hide every sibling along its ancestor chain, make the chain `position:fixed; inset:0; 100vw x 100vh`, `object-fit: contain`, restore the saved inline styles on exit; the page script already implements `__ytf_setPip(true/false)`).
- Controls in the PiP window: play/pause, seek +-10 s, close, "back to app" (restores the main window to the same video position). Pause and resume from the PiP window must work repeatedly **without the video stalling after one second** (regression test: toggle 10 times).
- Closing the PiP window with the X returns focus to the main window and keeps the video playing there (unless "pause on close" is set).
- Never enter PiP when nothing is playing, when audio-only Music is playing, or when the setting "Picture-in-Picture" is off.

**Internal mini player (inside the app)**
- On the YouTube source, pressing Back (mouse back button, `Alt+Left`, `Esc` on a watch page when not fullscreen, or the in-app back arrow) **while a video is on a watch page** shrinks it into a floating mini player inside the app and keeps playing while the user browses home/search. Implementation matches `youtube.js` section 10: create a fixed container on `<html>` (survives SPA navigation) with an `<iframe src="https://www.youtube.com/embed/<id>?autoplay=1&start=<sec>&playsinline=1&enablejsapi=1&rel=0&modestbranding=1&origin=...">`, 16:9, width `min(46vw, 360px)`, bottom-right 12 px above the bottom edge, 12 px radius, shadow. Two round buttons (36 px, `rgba(0,0,0,.7)`, white SVG icons drawn with `createElementNS`): **back arrow** (top-left) and **close X** (top-right).
- The **back arrow does NOT go fullscreen**. It navigates to `/watch?v=<id>&t=<seconds>s`, i.e. the normal watch page (short video with recommendations) at the exact second the mini player reached.
- Position tracking: `postMessage({event:'listening'})` handshake, read `infoDelivery`/`onStateChange` for `currentTime`, `playerState`, `duration`, title. If the embed sits at state -1 or 5 while the user was watching, send `playVideo`.
- Full page navigations: because `history.back()` can cross documents, store `{id, t, playing, title, at}` in `sessionStorage['ytf_mini_pending']` before going back, and recreate the mini player on the next page load if the entry is younger than 15 s (add elapsed time if it was playing).
- Mini player to external PiP / minimize / screen lock: when the app is minimized, hidden, the display turns off or PiP starts, convert the mini player into the real watch page at the same position (so playback continues and the native PiP path above works). After returning from external PiP to the app, pressing Back **must create the mini player again** (this loop mini -> PiP -> app -> Back -> mini is a required test).
- Opening a new watch page closes the mini player. It is hidden in PiP and fullscreen.

### 4.7 Background and locked-screen audio
- Audio continues when the window is minimized, hidden to the tray, behind other windows, or when the OS session locks / display sleeps (`powerSaveBlocker.start('prevent-app-suspension')` while playing; release it 2 minutes after playback stops).
- Disable renderer backgrounding (`backgroundThrottling: false`, `app.commandLine.appendSwitch('disable-renderer-backgrounding')`, `disable-background-timer-throttling`, `disable-backgrounding-occluded-windows`).
- Media keys (play/pause, next, previous, stop) and Windows System Media Transport Controls (SMTC) must show title, artist/channel, artwork and working buttons. Use the page's `navigator.mediaSession` metadata (the script already intercepts `navigator.mediaSession.metadata`) and `app.setAboutPanelOptions`/`globalShortcut` as fallback.
- Per-source state is independent: Music keeps playing while the user watches a muted-by-choice YouTube tab only if the user starts both; starting playback in one source **pauses the other** (like the Android single-session behaviour), with a setting "Allow both to play" (default off).

### 4.8 Playback speed up to 4x
- A small pill button (`1x`) at the top-left of the YouTube player, cycling 1, 1.25, 1.5, 2, 3, 4. Chosen speed is remembered (`localStorage['ytf_rate']`) and re-applied on each new video (`loadedmetadata`, then again at 0.8 s and 2.5 s). It fades with the player controls: visible while paused or for 3.5 s after any pointer/touch/click, hidden otherwise (opacity transition 250 ms). Hit-testing is done at window level (capture phase) because the player overlay sits above it. Hidden during ads, PiP and on YouTube Music. Keyboard: `Shift+.` / `Shift+,` also step the rate up/down.

### 4.8b In-player PiP button
- Next to the `1x` speed pill (top-left of the player, 8 px gap, same pill style and the same fade-with-controls behaviour, also visible in fullscreen) there is a second pill labelled `PiP`. It exists only on watch pages, is hidden during ads, in PiP and on YouTube Music, and is hit-tested at window level like the speed pill.
- Tapping it: if the player is fullscreen, exit fullscreen first and wait about 650 ms (60 ms otherwise); then ask the main process (`YouTubeFreemiumBridge.enterPip()`) to open the external PiP window (4.6). This is the supported way to get PiP from fullscreen, so fullscreen itself never has to be restyled.
- Tests: E4b taps the PiP button in normal mode and in fullscreen (both must yield a PiP window showing only the video, not black); U13 checks the button's visibility rules and that it is positioned right of the speed pill whatever the pill's width.

### 4.9 Resume and state
- Every 3 s while a video plays (and on pause) save `{id, t, at}` in `localStorage['ytf_last']`; when the same video is opened again within 12 hours and the URL has no explicit `t=`, seek to the saved position once. Switching between YouTube and YouTube Music preserves each source's last page and playback state.
- Remember the last active source and open it on start.
- Returning from the mini player unmutes the video (YouTube autoplays muted after a non-gesture navigation: clear the "Tap to unmute" banner and set `muted = false` for 12 s after a restore).

### 4.10 Navigation, privacy and safety rules
- Allowed in-app hosts: `*.youtube.com`, `youtu.be`, `*.googlevideo.com`, `*.ytimg.com`, `*.ggpht.com`, `*.gstatic.com`, `*.googleusercontent.com`. Sign-in hosts (`accounts.google.com`, `accounts.youtube.com`, `myaccount.google.com`, `consent.youtube.com`, `consent.google.com`) may load as top-level pages **after a user click** and return to the app. Everything else is blocked silently (never launched in an external browser, never `intent:`-style schemes).
- Popups (`window.open`) are denied except user-initiated sign-in popups to allowed auth hosts, which open inside the same view.
- Permissions: deny all by default except `media` (audio output), `fullscreen`, `clipboard-sanitized-write`, `notifications` (for the media notification) and `pointerLock` off. Block geolocation, camera, microphone, USB, serial, HID.
- Block the scam/redirect behaviours covered by `scamguard.js` and `navguard.js`.
- Start URLs are never written to logs or shown in the UI; the packaged app stores them obfuscated (XOR + base64 with a per-build key), as the Android build does.

### 4.11 Caching and performance
- Cache only CSS, JS and fonts from `youtube.com`, `ytimg.com`, `gstatic.com` versioned paths (`/s/...`), maximum 100 MB, 14 days. **Never cache images, audio or video.** Implement with a `session.webRequest`/protocol interceptor that mirrors `CachePolicy.java` (include a unit test that images and media are never stored).
- Cold start to usable UI after the splash: no extra wait; the first source loads while the splash plays. Idle memory target under 450 MB with both sources loaded; the inactive source is frozen via `webContents.setBackgroundThrottling(true)` only when it is not playing.
- Release build is minified, ASAR-packed, with `devTools` disabled in production and available with `--devtools`.

### 4.12 Settings panel (opened from the Island gear, `Ctrl+,`)
Toggles (persisted): Picture-in-Picture (on), Background play (on), Keep playing in the tray (on), Allow both sources to play (off), Show intro on launch (on), Ad blocking (on), Cosmetic filtering (on), Start with Windows (off), Hardware acceleration (on), plus buttons: Clear cache (not logins), Clear data (logins; confirm dialog), Update lists now, About (shows "YouTube Freemium", version, "Crafted by Ayush Kumar" with the red heart, build number).

### 4.13 Keyboard shortcuts
`Space`/`K` play-pause (when the page does not already handle it), `J`/`L` seek -/+10 s, `Ctrl+1/2` source, `Ctrl+Tab` toggle source, `Ctrl+Shift+P` PiP, `F11` fullscreen, `Ctrl+R` reload active source, `Ctrl+Shift+R` hard reload, `Ctrl+,` settings, `Alt+Left` back (mini player aware), `Alt+Home` go to source home, `Ctrl+Q` quit.

### 4.14 Fullscreen
Video fullscreen uses HTML fullscreen inside the view (hides Island and title bar); Esc exits; fullscreen survives source switches; the speed pill stays inside the player in fullscreen; screen-off/lock does not pause playback in fullscreen.

## 5. DESKTOP ADAPTER FOR `youtube.js`

The Android build talks to native code through `window.YouTubeFreemiumBridge` (`reportMediaState(...)`, `adSkipping(bool)`, `log(msg)`). On desktop provide the **same object** from the preload using `ipcRenderer.sendSync`/`invoke` shims so `youtube.js` runs **unchanged** (a thin `bridge.js` file). Native to page calls that must exist as `window.__ytf_*` functions (already implemented in `youtube.js`): `__ytf_play`, `__ytf_pause`, `__ytf_seek(sec)`, `__ytf_seekTo(sec)`, `__ytf_next`, `__ytf_prev`, `__ytf_setPip(bool)`, `__ytf_setBg(bool)`, `__ytf_resumeIfWanted()`, `__ytf_miniOnBack()` (returns true if it turned the video into the mini player). Main-process responsibilities that mirror `MainActivity.java`:
- window `blur`/`minimize`/`hide` and display-off events call `__ytf_setBg(true)`; `focus`/`restore` call `__ytf_setBg(false)`; PiP enter/exit call `__ytf_setPip(true/false)`.
- "Back" gestures call `__ytf_miniOnBack()` first; if it returns false, do normal `webContents.goBack()`; if there is no history, minimize to the tray.
- `reportMediaState(isPlaying, isVideo, title, artist, artworkUrl, duration, position, w, h, left, top, right, bottom)` drives: tray menu labels, SMTC/MPRIS, the PiP eligibility, the power-save blocker and the window title (`<title> - YouTube Freemium`).

## 6. REPO LAYOUT TO PRODUCE

```
D:\YouTube-Freemium\apps\desktop\
  package.json  (name @ytfreemium/desktop, main: src/main/main.js)
  src\main\  main.js window-state.js views.js overlays.js island-ipc.js splash.js
             adblock.js cosmetic-ipc.js cache-policy.js navigation.js permissions.js
             media.js (SMTC, tray, media keys, power blocker) pip.js tray.js settings-store.js
             user-agent.js shortcuts.js menu.js log.js (no URLs)
  src\preload\ site.js (injects packages/inject/*.js, builds the bridge) ui.js
  src\ui\ island.html/.css/.js  panel.html/.css/.js  splash.html/.css/.js  pill.html/.css/.js
  test\ *.test.mjs  e2e\*.spec.mjs (Playwright for Electron)
D:\YouTube-Freemium\scripts\desktop\ generate.mjs icon.mjs builder-config.mjs sign.mjs
D:\YouTube-Freemium\docs\DESKTOP.md   (architecture, build, test, release steps)
```
`npm run desktop:dev`, `npm run desktop:test`, `npm run desktop:e2e`, `npm run build -- --app youtube-freemium --target desktop` must all work from the repo root.

## 7. BUILD AND RELEASE

- `electron-builder` outputs to `D:\YouTube-Freemium\dist\desktop\`: `YouTube-Freemium-Setup-1.0.0.exe` (NSIS, per-user install, desktop + start-menu shortcuts, uninstaller keeps user data unless asked), `YouTube-Freemium-1.0.0-portable.exe`, and for completeness `.dmg` and `.AppImage` configs.
- App metadata: productName `YouTube Freemium`, appId `com.ayushkumar.youtubefreemium`, copyright "Crafted by Ayush Kumar", icon set from section 1.
- Auto-update is **off** (no network publishing). Provide a "Check for updates" menu item that only opens the project page placeholder in the settings About view (no external browser: show the text).
- After building, print file sizes, SHA-256 of each artifact and the Authenticode signer (or "self-signed").

## 8. KNOWN PITFALLS (learned the hard way; do not repeat)

1. YouTube pauses itself ~250 ms after every `play()` when it thinks the window is a tiny PiP. Fix = `outerWidth/outerHeight` spoof (4.5). If you see "pause, resume, pause, resume" loops, this is why. Do not "fix" it with more forced resumes.
2. Forced resumes must be rate-limited (2 per 5 s) or they fight the page.
3. `touchend`/`pointerup` `preventDefault()` cancels the synthetic `click`; handle `click`, `pointerup` and `touchend` all with a 350-500 ms debounce.
4. `innerHTML` throws under Trusted Types on YouTube (the mini-player buttons and ad overlay silently failed because of it). Use DOM APIs.
5. The player's own overlay sits above injected buttons: do hit-testing in a window-level capture listener when needed.
6. Full-document navigations destroy in-page UI. Persist the mini-player intent in `sessionStorage` and rebuild it.
7. Hidden `<video>` elements remain in the SPA DOM after leaving a watch page. "Is a video playing" must be decided with `miniFront()` (mini active and not on a watch page) before looking at `document.querySelector('video')`.
8. Embeds in a cross-origin iframe get frozen when the app is backgrounded: relay the background flag with `postMessage({ytf:'bg', v})` and resume with `playVideo`; for real background audio and PiP, fall back to the full watch page.
9. Google sign-in fails if the UA contains `Electron` or the window is a bare webview; keep UA, client hints and `X-Requested-With` clean.
10. Do not put start URLs, tokens or full URLs in logs or UI.

11. **PiP isolation must not use inline styles.** Restoring saved inline `style` strings on exit overwrites what YouTube recomputed while the window was small and leaves the page half black and shifted. Mark the video's ancestor chain with a data attribute (`data-ytf-pip`) and its siblings with `data-ytf-hide`, drive both from ONE injected stylesheet with `!important` rules, and on exit just remove the attributes and the stylesheet (then dispatch `resize`).
12. **Fullscreen + PiP:** if `document.fullscreenElement` exists when PiP starts, change nothing (no restyling, no hiding, no exiting fullscreen). Touching the fullscreen surface makes the PiP window black. Only the non-fullscreen watch page gets the attribute isolation.
13. **Tell the phone/desktop host apart from a "fullscreen that never really happened":** a tap on the fullscreen button can set `fullscreenElement` without the host showing a fullscreen view; the rule in 12 still holds, PiP then shows the page and the user can exit.

## 9. WORK PACKAGES (parallelize where independent)

| WP | Scope | Depends on |
|---|---|---|
| WP1 | Scaffold `apps/desktop`, copy and adapt the Movy desktop shell, config loader for `youtube-freemium.json`, single instance, window state, tray skeleton | none |
| WP2 | Two persistent `WebContentsView`s, session partition, UA/client hints, navigation rules, permissions, Google sign-in path | WP1 |
| WP3 | Adblock engine (napi adblock-rust or Ghostery fallback), cosmetic IPC, cache policy, filter-list updater | WP1 |
| WP4 | Page-script bridge (`bridge.js`), injection in all frames, pruning, skipper, visibility/outerWidth spoofs, resume logic, speed pill, progress save/restore | WP2 |
| WP5 | Island overlay with real logos, Skipping-ads pill overlay, settings panel, About | WP2 |
| WP6 | Splash (animation + synthesized sound), first-run-only logic | WP1 |
| WP7 | External PiP window, internal mini player integration, Back-key logic, mini -> PiP -> app -> Back loop | WP4 |
| WP8 | Media: SMTC/MPRIS, media keys, tray controls, power-save blocker, background and display-off audio | WP4 |
| WP9 | Packaging, icons, signing, docs (`docs/DESKTOP.md`) | WP1-8 |
| WP10 | Test suites (unit, e2e, soak) and the final acceptance report | all |

## 10. ACCEPTANCE SUITE (all must be executed; report PASS/FAIL/NOT VERIFIED with evidence)

Unit tests (`node --test`):
- U1 config loader accepts `youtube-freemium.json`, rejects extra sources, obfuscates URLs in the packaged config and de-obfuscates at runtime.
- U2 navigation rules: allowed hosts load; `evil.example` blocked; `intent://`, `market:`, `mailto:` dropped; auth hosts allowed only after a user gesture.
- U3 cache policy: JS/CSS/fonts under `/s/` cached; `.jpg/.png/.webp/.mp4/.m4s/.mp3/.webm` never cached; range and authorization requests never cached; entries expire after 14 days; cap 100 MB with LRU eviction.
- U4 `pruneAds` removes all four ad keys from nested `playerResponse`, leaves other keys intact, returns false when nothing changed.
- U5 ad-skip decision: speed restored and mute restored after an ad; clips over 1200 s are not seeked; reload-once guard works.
- U6 pill state machine: show, heartbeat, watchdog hide at 3.5 s, min visible 1.4 s, never shown in PiP.
- U7 speed cycle order 1 -> 1.25 -> 1.5 -> 2 -> 3 -> 4 -> 1 and persistence.
- U8 resume store: saves every 3 s, removes when within 10 s of the end, ignores entries older than 12 h or when `t=` present.
- U9 `wantPlaying` logic: user pause (touch within 1.5 s) clears it; system pause in background resumes at most 2 times per 5 s.
- U10 splash timeline: events at 0 / 0.4 / 2.0 / 2.35 / 3.15 / 3.5 s (with fake timers); no splash on second launch or tray restore.
- U11 Island: tiles for exactly two sources, labels, active highlight, `Ctrl+1/2` and `Ctrl+Tab`.
- U13 PiP button: visible only on watch pages, hidden during ads/PiP/Music, fades with controls, sits 8 px right of the speed pill.
- U12 mini-player persistence: `ytf_mini_pending` rebuilds the mini player within 15 s, ignores older.

E2E (Playwright for Electron, real window, real network; allow retries for network flakiness):
- E1 cold start shows the splash for about 3.5 s (3.3 to 3.9 s), then the YouTube home appears; second start via tray does not.
- E2 open a video: it starts within 4 s; the speed pill shows `1x`, cycles to `4x`, `video.playbackRate === 4`, then fades after about 3.5 s without input and returns on pointer move.
- E3 ad handling: with ads present, the pill appears at top center and is gone within 1.5 s after the ad ends; the main video then plays unmuted at the saved rate. Log how many ads were seen and how long each lasted.
- E4 minimize the window while a video plays: PiP window appears with only the video; play/pause toggled 10 times, the video keeps advancing after each resume (position advances >= 1.5 s within 3 s each time).
- E5 PiP "back to app": main window restores to the same video within 1 s of the position.
- E6 Back on a watch page creates the mini player (iframe present, id correct, position within 2 s); the mini player keeps playing while navigating home/search; its back arrow opens `/watch?v=<id>&t=<n>s` (NOT fullscreen) within 2 s of the mini position.
- E7 mini -> minimize -> PiP shows the video (not black) -> restore -> Back creates the mini player again.
- E8 display off / session lock simulation (`powerMonitor` events + minimize): audio element keeps `paused === false` and `currentTime` advances for 15 s (test on both a video and a Music track).
- E9 switching YouTube <-> YouTube Music keeps both pages alive; with "Allow both" off, starting one pauses the other; no "Leave this page?" dialog ever appears.
- E10 Google sign-in page loads in-app and reaches the password step for a test account supplied by the user at run time (never stored); if no account is provided mark NOT VERIFIED, but verify the email step renders and is not "This browser or app may not be secure".
- E11 caching: after two sessions, only CSS/JS/font entries exist in the cache directory; zero images/audio/video.
- E12 media keys and SMTC: simulate media key events and assert play/pause/next change playback; SMTC shows title and artwork (screenshot or API check).
- E13 tray: close button hides to tray while playing and quits when idle; tray menu items work.
- E14 fullscreen: F11 and video fullscreen work; Esc exits; the speed pill is visible inside the player in fullscreen.
- E15 packaging: install the NSIS build silently into a temp dir, launch, run E1 and E2, uninstall; run the portable exe and repeat E1.

Soak and budgets:
- S1 30-minute playback with background minimize/restore every 2 minutes: no crashes, memory growth under 100 MB, CPU under 8% average while minimized.
- S2 no URL strings from the config in the main log or the UI (grep the log and the DOM).

## 11. DELIVERABLES AND FINAL REPORT

1. Working source in `D:\YouTube-Freemium\apps\desktop` plus scripts and docs.
2. Built artifacts in `D:\YouTube-Freemium\dist\desktop\` with sizes and SHA-256.
3. `docs/DESKTOP.md` and `docs/DESKTOP-TEST-REPORT.md` listing each of U1-U12, E1-E15, S1-S2 with PASS/FAIL/NOT VERIFIED and evidence.
4. A short "how to update selectors / filter lists / bump version" section.
5. Final message: what was built, where the installer is, what failed or could not be verified, and the exact commands to reproduce the build and the tests.

Start now. First action: read the files in section 2, then post a numbered plan with the work-package assignment, and begin WP1.
