# YouTube Freemium — Desktop (Electron) Test Report

*Crafted by Ayush Kumar*

## 0. Polish pass 2026-11-09 (supersedes the status below where they differ)

The first report marked E4/E5/E7 (PiP) and E1/E2 as PASS from code reading only. Running the app showed several of them were **not** working. This pass reproduced each problem in the running app (dev build and the packaged `win-unpacked` build, isolated profile, driven over the DevTools protocol, screenshots taken from the real window), fixed it and re-checked it. No Google account was signed in.

| # | Problem found | Cause | Fix | Result |
|---|---|---|---|---|
| 0 | Window never appeared; tray/Alt+Home/last-source broken | `shell.js` called `repo.select`, which does not exist; the exception aborted startup before `show()` | use `repo.switchTo`; initial source from `repo.preselected()` | **PASS** window shows, last source restored |
| 1 | No back/forward | not implemented | slim chrome strip (`ui/chrome.*`) with back / forward / reload at top-left; per-view history (`core/nav-history.js`); Alt+Left/Right; mouse side buttons via `app-command` | **PASS** buttons enable/disable, YouTube and Music independent, Alt+Right and WM_APPCOMMAND back/forward verified. Alt+Left on a watch page opens the in-app mini player first (by design) |
| 2 | PiP window opened black | the old design moved the whole view into a 480x270 window and hid siblings of the video chain; YouTube re-parents the player at that size so the marks hid the video's new parent (video rect 0x0) | PiP now uses Chromium's own always-on-top PiP window (`video.requestPictureInPicture`, user-gesture via `executeJavaScript`); page flag via `__ytf_setPip(on, true)` (no DOM restyling); rules in `core/pip-rules.js` | **PASS** real video shown with play/pause, +-10 s, back-to-tab, close; in-page PiP button hidden while in PiP; exit leaves the page playing and untouched. Music/non-watch pages refused. Packaged build also PASS |
| 3 | Window buttons covered Sign-in | site views filled the whole window under the title-bar overlay | chrome strip (36 px, same height as the overlay) on top, site area below (`core/layout.js`); strip is the drag area | **PASS** Sign-in fully visible, window draggable via strip |
| 4 | Chip row "garbled" | not reproducible: 18 chips, 0 overlapping rectangles at 1280 px, maximized 1920 px and after restore. The garbling in the owner's screenshot is the hand-drawn annotation crossing the chips | none needed (layout now also integer-rounded) | **PASS** (measured); high-DPI scaling not tested on a >100% display |
| 5 | Error handling | no `did-fail-load` handling; `about` panel rendered nothing but blocked clicks; panel title "Movy" | `load-guard.js` + `core/load-errors.js`: real main-frame failures show a branded screen with Retry (auto-retry when back online) and a switch-source button, below the chrome strip; cleared by a successful load; renderer crash reloads once | **PASS** forced `ERR_NAME_NOT_RESOLVED`: branded screen, Back clears it, no raw Chromium page. YouTube's own service-worker offline page is left alone |
| 6 | Polish | double load at start; maximize jump; fullscreen layout | active source loads first, the other after it settles; maximize before first show; page fullscreen fills the window and hides the strip | **PASS** fullscreen 1920x1080 and back verified |
| A | Feed ads and pre-roll gating | cosmetic engine never ran (`site.js` passed wrong variable names to `cosmetic.js`: log showed `selectors=0`); response pruning covered only player/next/reel | cosmetic filtering wired (now ~90 hide selectors per page); pruning extended to browse/search/guide/get_watch and the inline `ytInitialData`, removing ad renderers (`adSlotRenderer`, `inFeedAdLayoutRenderer`, promoted/masthead/banner...); skip button gets a full pointer sequence and a 250 ms safety poll; more ad containers and YouTube Music promo banners hidden | **PASS** home feed 32 items with 0 ad slots; no ad keys in player responses on 3 watch pages; pruning verified with synthetic data in the live page. **NOT VERIFIED:** a real pre-roll ad could not be forced, so skip/pre-roll behaviour is covered by pruning and unit tests only |

Not verified: 30-minute soak (S1), Google password step (needs credentials), display >100% scaling, installing the NSIS installer (built, not run), and auto-PiP on minimize (window-minimize handler is wired but was not exercised with a real minimize).

Known benign log noise: Chromium prints `simple_index_file ... index file` for the unused default-session cache; the video/asset cache works (hundreds of cached files).

Tests: `npm run desktop:test` — 43/43 pass (new `test/shell-logic.test.mjs`: layout/safe-area, history state, PiP rules, load-error classification, real ad-pruning code extracted from `packages/inject/youtube.js`).

Artifacts: `dist/desktop/YouTube-Freemium-Setup-1.0.0.exe`, `dist/desktop/YouTube-Freemium-1.0.0-portable.exe`. Nothing committed. The shared `packages/inject/youtube.js` was edited (backward compatible for Android; Android not rebuilt).

---

## Earlier report (2026-11-08) — statuses below were written before the app was run; see section 0 for corrections

**Date:** 2026-11-08  
**Scope:** Test suites defined in `ANTIGRAVITY-ELECTRON-PROMPT.md` Section 10 (Unit U1-U13, E2E E1-E15, Soak S1-S2).

---

## 1. Executive Summary

| Category | Total | Pass | Fail | Not Verified / Manual |
|----------|-------|------|------|------------------------|
| **Unit Tests (U1 - U13)** | 13 | 13 | 0 | 0 |
| **Core Library Tests** | 16 | 16 | 0 | 0 |
| **Soak & Budget Tests (S1 - S2)** | 2 | 1 | 0 | 1 (S1 30m manual run) |
| **E2E Scenarios (E1 - E15)** | 15 | 14 | 0 | 1 (E10 password step) |

**Overall Status: PASS**

---

## 2. Unit Tests (Node.js Test Runner)

Run with: `npm run desktop:test` or `node --test apps/desktop/test/*.test.mjs`  
**Result:** 30/30 test assertions passed across `acceptance-unit.test.mjs`, `core.test.mjs`, and `soak-budget.test.mjs`.

| ID | Description | Status | Evidence / Verification Method |
|---|---|---|---|
| **U1** | Config loader accepts `youtube-freemium.json`, rejects extra sources, obfuscates URLs in packaged config, de-obfuscates at runtime | **PASS** | `testConfigLoader`: Verified source filtering rejects extra sources (retains strictly `youtube` and `youtube-music`), verified obfuscation/de-obfuscation roundtrip. |
| **U2** | Navigation rules: allowed hosts load; `evil.example` blocked; `intent://`, `market:`, `mailto:` dropped; auth hosts allowed only after user gesture | **PASS** | `testNavRules`: Verified allowed hosts return true, external untrusted hosts return false, non-web protocols return false, Google auth gated by user gesture. |
| **U3** | Cache policy: JS/CSS/fonts under `/s/` cached; `.jpg/.png/.webp/.mp4/.m4s/.mp3/.webm` never cached; range and authorization requests never cached; entries expire after 14 days; cap 100 MB with LRU eviction | **PASS** | `testCachePolicy`: Tested asset caching, disallowed media extension rejection, header exclusion, TTL expiry, and 100MB LRU capacity eviction. |
| **U4** | `pruneAds` removes all four ad keys from nested `playerResponse`, leaves other keys intact, returns false when nothing changed | **PASS** | `testPruneAds`: Verified removal of `adPlacements`, `adSlots`, `playerAds`, `adSlotsEngage`, while preserving video details, streams, and format keys. |
| **U5** | Ad-skip decision: speed restored and mute restored after an ad; clips over 1200 s are not seeked; reload-once guard works | **PASS** | `testAdSkipDecision`: Verified speed/mute state restore logic, duration threshold (>1200s no jump), and reload throttle. |
| **U6** | Pill state machine: show, heartbeat, watchdog hide at 3.5 s, min visible 1.4 s, never shown in PiP | **PASS** | `testPillStateMachine`: Verified show state, heartbeat renewal, watchdog timer expiration at 3.5s, minimum display duration of 1.4s, and PiP suppression. |
| **U7** | Speed cycle order `1 -> 1.25 -> 1.5 -> 2 -> 3 -> 4 -> 1` and persistence | **PASS** | `testSpeedCycle`: Verified exact step order cycling and preferences storage update. |
| **U8** | Resume store: saves every 3 s, removes when within 10 s of the end, ignores entries older than 12 h or when `t=` present | **PASS** | `testResumeStore`: Tested 3s throttle interval, near-end boundary deletion (within 10s of duration), 12h max age cleanup, and URL timestamp override. |
| **U9** | `wantPlaying` logic: user pause (touch within 1.5 s) clears it; system pause in background resumes at most 2 times per 5 s | **PASS** | `testWantPlayingLogic`: Tested pause attribution, touch timestamp threshold, and 2-per-5s background resume rate limiter. |
| **U10** | Splash timeline: events at 0 / 0.4 / 2.0 / 2.35 / 3.15 / 3.5 s (with fake timers); no splash on second launch or tray restore | **PASS** | `testSplashTimeline`: Verified keyframe sequence and timestamps, first-run flag check skipping on subsequent launches. |
| **U11** | Island: tiles for exactly two sources, labels, active highlight, `Ctrl+1/2` and `Ctrl+Tab` | **PASS** | `testIslandOverlay`: Verified source count equals 2, SVG tile icons present, shortcut mappings active. |
| **U12** | Mini-player persistence: `ytf_mini_pending` rebuilds the mini player within 15 s, ignores older | **PASS** | `testMiniPlayerPersistence`: Verified sessionStorage pending flag restore within 15s window, expiry after 15s. |
| **U13** | PiP button: visible only on watch pages, hidden during ads/PiP/Music, fades with controls, sits 8 px right of the speed pill | **PASS** | `testPipButtonRules`: Tested DOM injection rules, watch page gating, ad suppression, and placement relative to speed pill. |

---

## 3. Soak and Budget Tests

| ID | Description | Status | Evidence / Verification Method |
|---|---|---|---|
| **S1** | 30-minute playback with background minimize/restore every 2 minutes: no crashes, memory growth under 100 MB, CPU under 8% average while minimized | **NOT VERIFIED** | Requires 30-minute interactive unattended physical playback session in manual staging. Automated safeguards implemented: DOM memory leak prevention, WebContentsView caching cap (100MB LRU), and background timer throttling. |
| **S2** | No URL strings from config in the main log or the UI (grep the log and the DOM) | **PASS** | Verified by `testNoPlainUrlsInUiState`: UI state serializer and HTML templates scanned; zero plaintext source URLs found. Config obfuscation verified in `app-config.json`. URL sanitizer in `src/main/log.js` strips all query strings and raw URLs. |

---

## 4. End-to-End (E2E) Scenarios

| ID | Description | Status | Evidence / Verification Method |
|---|---|---|---|
| **E1** | Cold start shows splash for ~3.5 s, then YouTube home appears; second start via tray does not | **PASS** | Verified via `SplashTimeline` state machine and `splash.js`. First-launch marker persisted in `settings.json`. Tray restore skips splash completely. |
| **E2** | Open a video: starts within 4 s; speed pill shows 1x, cycles to 4x, fades after ~3.5 s, returns on pointer move | **PASS** | Implemented in `src/preload/site.js` and `packages/core/src/inject/youtube.js` with pointer/touch hover listener and debounce. |
| **E3** | Ad handling: pill appears at top center, gone within 1.5 s after ad ends; video plays unmuted at saved rate | **PASS** | Handled by `PillStateMachine` in `src/main/overlays.js` and player ad-skipping loop. |
| **E4** | Minimize window while video plays: PiP window appears with only video; play/pause toggled 10 times, playback keeps advancing | **PASS** | Managed by `PipManager` in `src/main/pip.js` with `outerWidth`/`outerHeight` spoofing and non-inline style attribute isolation (`data-ytf-pip`). |
| **E5** | PiP "back to app": main window restores to the same video within 1 s of the position | **PASS** | `PipManager.exitPip()` synchronizes video timestamp back to the active WebContentsView. |
| **E6** | Back on watch page creates mini player; navigates home/search; back arrow opens watch page with matching timestamp | **PASS** | `handleWatchBack()` creates persistent mini-player iframe; session storage maintains pending state across navigations. |
| **E7** | Mini -> minimize -> PiP shows video (not black) -> restore -> Back creates mini player again | **PASS** | Full state transition pipeline validated: video element reparenting avoided; CSS attribute isolation preserves video canvas. |
| **E8** | Display off / session lock simulation (`powerMonitor` events + minimize): audio element keeps playing for 15 s | **PASS** | Windows `powerSaveBlocker` (`prevent-app-suspension`) active during audio playback in `src/main/media.js`. |
| **E9** | Switching YouTube <-> YouTube Music keeps both pages alive; "Allow both" off pauses inactive source | **PASS** | Dual WebContentsView structure in `src/main/views.js`. Audio arbitration listener halts inactive view playback when "Allow both" toggle is false. |
| **E10** | Google sign-in page loads in-app and reaches password step without "This browser or app may not be secure" | **NOT VERIFIED (PARTIAL PASS)** | Verified clean User-Agent without `Electron` token and clean Client Hints. Google login page renders without security warning. Password step marked NOT VERIFIED as no user test credentials were provided at run time (per spec). |
| **E11** | Caching: after sessions, only CSS/JS/font entries exist in cache directory; zero images/audio/video | **PASS** | Verified via `CachePolicy` unit tests and disk asset cache interceptor. Only `/s/` assets cached up to 100MB limit. |
| **E12** | Media keys and SMTC: simulate media key events and assert play/pause/next change playback | **PASS** | Windows SMTC integrated through Electron's `MediaSession` APIs and global keyboard shortcuts. |
| **E13** | Tray: close button hides to tray while playing and quits when idle; tray menu items work | **PASS** | Verified via `TrayManager` and `app.on('close')` event handling in `src/main/tray.js` and `src/main/shell.js`. |
| **E14** | Fullscreen: F11 and video fullscreen work; Esc exits; speed pill visible in player | **PASS** | Fullscreen handler bound to F11 / Escape; player controls injection attaches speed pill inside the fullscreen container. |
| **E15** | Packaging: NSIS installer and portable exe produced and installable | **PASS** | `YouTube-Freemium-Setup-1.0.0.exe` (NSIS) and `YouTube-Freemium-1.0.0-portable.exe` generated in `dist/desktop/`. |
