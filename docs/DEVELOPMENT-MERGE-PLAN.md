# Development branch: merge and portrait plan (2026-11-13)

Hand this file to the model named in each work package. It is self-contained: do not rely on earlier chat history.

## Which model to give this to

| Work package | Model | Why |
|---|---|---|
| WP1 (portrait audit and fixes) | **Claude Opus 5.5** | Needs root-cause work on YouTube's live page with an emulator and DevTools. A weaker model tends to guess at CSS. |
| WP2, WP3, WP4 | **Claude Sonnet 5.5** | Mechanical: a gated code restore, test runs, build, push. |

If you only want to use one model for everything, use Opus 5.5.

## Where things stand

- Repo: `https://github.com/thecheesybit/YouTube-Freemium`. Upstream is `origin/main` at `98323da`.
- Branch `development` = upstream `main` + the app-parity work. It is already merged: upstream's only commit since the fork point (`60251f4`) is `docs/KNOWN-ISSUES.md`, so there were **no code conflicts**. Do not redo the merge.
- What `development` adds over `main` (phone):
  - A second WebView for the watch page (`PlayerHost.java`), states HIDDEN / FULL / MINI, with a native 64dp mini bar above the bottom nav.
  - Back behaviour through `BackPolicy.java` (unit tested), `OrientationPolicy.java`.
  - Ad fixes for the mobile site in `packages/inject/youtube.js` and `youtube-selectors.json`.
  - Crash fix (chrome client built in the `PlayerHost` constructor), mini-bar video, sound on start, chip row pinned under the video.
- What `development` dropped from `main`: the iframe mini player in `youtube.js` (`openMini`, `closeMini`, `miniCmd`, `miniFront`, `miniPip`, `restoreMiniToPage`). `__ytf_miniOnBack` is now a stub returning `false`. This changes the **desktop** app: Alt+Left on a watch page no longer opens the in-app mini player. WP2 restores it for desktop only.
- Reported by the main user: **portrait mode has layout problems on the phone; landscape (fullscreen) is fine.** No exact list was given. One known case was already fixed (the "All / For you" row floating 96px too low). WP1 finds and fixes the rest.

## Rules (apply to every work package)

1. **Never** `git add` or commit `restore-private.mjs`, `config/apps/*.json` (except the tracked `example.json`), or anything under `.movy`. Never print the URLs or keys inside them, in chat, logs, commits or docs.
2. Keep builds on the existing signing key in `%USERPROFILE%\.movy\keystore`. Do not delete or regenerate it.
3. `packages/inject/youtube.js` is shared by phone, TV and desktop. Every phone-only change goes inside `IS_PHONE_SPLIT` (and `ROLE === 'player'` or `'browse'`). Keep every `window.__ytf_*` function defined.
4. Do not commit `apps/mobile/android/app/capacitor.build.gradle` or `apps/mobile/android/capacitor.settings.gradle`: their local changes are line endings only.
5. Work on `development`. Do not push to `main`. Do not force-push.
6. Fix causes, not symptoms: no `setTimeout` retries and no blanket `!important` resets to paper over a layout you have not measured.

## Environment (Windows, PowerShell)

```powershell
$env:PATH = "$env:NVM_HOME\v22.23.5;$env:USERPROFILE\.cargo\bin;C:\Program Files\PowerShell\7;$env:PATH"
$env:MOVY_JAVA_HOME = [Environment]::GetEnvironmentVariable('MOVY_JAVA_HOME','User')
```

- Global `node` is v20; the build and tests need the Node 22 on that PATH line. Run npm from PowerShell, not Git Bash.
- Release APK: `npm run build -- --app youtube-freemium --target phone` → `dist/youtube-freemium/youtube-freemium-phone.apk`.
- Debuggable APK: in `apps\mobile\android`, `.\gradlew.bat assemblePhoneDebug` → `app\build\outputs\apk\phone\debug\app-phone-debug.apk`.
- Tests: `npm test` (expect 5 / 24 / 43 pass), `npm run desktop:test` (43 pass), and in `apps\mobile\android`: `.\gradlew.bat testPhoneReleaseUnitTest`.
- Emulator: `& "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe" -avd ytf_test -no-snapshot -no-boot-anim -gpu swiftshader_indirect -memory 3072` (run in the background).
- Package: `com.ayushkumar.youtubefreemium`. Open a video: `adb shell am start -a android.intent.action.VIEW -d "https://www.youtube.com/watch?v=aqz-KE-bpKQ" com.ayushkumar.youtubefreemium`.

### Inspecting the live page (debug APK only)

`adb shell pidof com.ayushkumar.youtubefreemium`, then `adb forward tcp:9222 localabstract:webview_devtools_remote_<pid>`. Save this as `cdp.mjs` outside the repo and run `node cdp.mjs "watch?v" probe.js` (use `"watch?v"`, not `"/watch"`: Git Bash rewrites leading slashes). The player WebView is the page whose URL contains `watch?v`; the browse WebView is the other one.

```js
// Usage: node cdp.mjs <urlSubstring> <file-with-js-expression>
import fs from 'node:fs';
const [match, file] = process.argv.slice(2);
const expr = fs.readFileSync(file, 'utf8');
const pages = await (await fetch('http://127.0.0.1:9222/json')).json();
const page = pages.find((p) => p.url.includes(match));
if (!page) { console.log('NO PAGE', pages.map((p) => p.url)); process.exit(1); }
const ws = new WebSocket(page.webSocketDebuggerUrl);
ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'Runtime.evaluate', params: { expression: expr, returnByValue: true, awaitPromise: true } }));
ws.onmessage = (m) => { const d = JSON.parse(m.data); if (d.id !== 1) return; console.log(JSON.stringify(d.result.result.value ?? d.result, null, 1)); ws.close(); };
```

---

## WP1 — Portrait audit and fixes (Opus 5.5)

**Background.** In the player WebView we hide YouTube's 48px top bar (`ytm-mobile-topbar-renderer`) and bottom nav, and move the fixed player to `top: 0` (`youtube.js`, the `IS_PHONE_SPLIT && ROLE === 'player'` CSS block near line 280). YouTube's stylesheet still positions other fixed and sticky elements as if the top bar were there. Each such element sits 48px (or 48px + something) too low, or leaves a gap, in portrait. Landscape fullscreen is unaffected because the video covers everything.

**Step 1: measure, do not guess.** On the emulator with the debug APK, open a video and run a probe in the player page that lists every element whose computed `position` is `fixed` or `sticky`, with its tag, class, computed `top`, and `getBoundingClientRect()`; plus the player's bottom edge (`#player-container-id`). Run it in each of these states:

| # | State (portrait) | What must be true |
|---|---|---|
| 1 | Video just opened, scrolled to top | Video starts at y=0 of the WebView. Title starts directly under the video, no gap, nothing hidden under it. |
| 2 | Scrolled down 1500px and 3000px | Video stays pinned. Nothing floats in the middle of the screen. Any pinned row sits exactly at the player's bottom edge. |
| 3 | Description opened (tap the title) | The panel's top edge equals the player's bottom edge; its header is fully visible; it scrolls. |
| 4 | Comments opened | Same as 3. The comment box is reachable above the keyboard. |
| 5 | Signed in (ask the user to sign in on the emulator, never type a password yourself) | The "All / For you" chip row sits at the player's bottom edge when it appears. |
| 6 | Playlist video (`&list=`) | The playlist panel is pinned at the player's bottom edge and expands correctly. |
| 7 | Vertical video and a live stream with chat | Player height differs from 16:9; pinned elements still meet its bottom edge. |
| 8 | Back pressed (MINI) then mini bar tapped (FULL) | Mini bar shows the video, sits right above the bottom nav. After expand, state 1 holds again. |
| 9 | MINI while the browse view is on Search with the keyboard open, on Shorts, and on a page without the bottom nav | Mini bar does not cover the keyboard, the Shorts controls, or leave a 48dp gap. |
| 10 | Rotate to landscape fullscreen and back | State 1 holds again; no leftover black band; status bar is back. |
| 11 | Small phone (360dp wide) and large font scale (1.3) | Mini bar title is not clipped by the buttons; nothing overlaps. |
| 12 | Shorts tab and Home feed in the browse view | Unchanged from upstream: swipe up/down works, bottom nav is visible. |

Take a screenshot of each failing state before and after (`adb exec-out screencap -p > file.png`).

**Step 2: known weak points to check first.**

1. `ytm-related-chip-cloud-renderer { top: 56.25vw !important; }` assumes a full-width 16:9 player. It is wrong for vertical videos, live streams with a taller player, tablets and split-screen. Replace the constant with the measured player height: set a CSS variable (for example `--ytf-player-h`) on `document.documentElement` from `#player-container-id`'s height with a `ResizeObserver`, and use `top: var(--ytf-player-h, 56.25vw)`.
2. Other elements that inherit the hidden top bar's offset: the engagement panel (description, comments), the playlist panel, the live-chat frame, the "mix" header. Find the rule that gives each its `top` or `margin-top` (walk `document.styleSheets`, filter rules whose text has `48px`, `96px`, or a `--*header*` variable). If they share one CSS variable, override that variable once in the player role instead of patching each element.
3. `PlayerHost.applyMiniLayout()` hardcodes `bottomMargin = 48dp` (the bottom nav height). When the browse page has no bottom nav (search with keyboard, Shorts, sign-in pages) the bar floats or covers content. Have the browse page report the nav's real height (0 when hidden) through the existing `YouTubeFreemiumBridge`, and use that value. Hide the mini bar while the keyboard is open.
4. Insets: `MainActivity` applies system-bar and cutout insets to `android.R.id.content` (near line 547). Confirm the player container (a child of that view) is not drawn under the status bar or the gesture bar in portrait on a device with a notch.
5. After MINI → FULL, confirm `pipExit()` restores every `data-ytf-pip` style. A leftover inline style is a likely cause of "misaligned after coming back".

**Step 3: fix.** Smallest CSS or Java change per finding, inside the phone-split guards. One commit per finding, message says what was measured before and after.

**Done when:** every row of the table passes on the emulator at 360dp and 411dp widths, light and dark theme, and the before/after numbers are in the commit messages. If a row cannot be tested (for example signed-in), say so in the final report instead of claiming it passes. If the audit finds nothing wrong, stop and ask the user for a screenshot of what the main user saw.

## WP2 — Restore the desktop in-app mini player (Sonnet 5.5)

**Goal.** Desktop behaves as on upstream `main` (Alt+Left on a watch page opens the in-app mini player, `__ytf_setPip(true, true)` returns `'mini'` when it converts one). Phone keeps the native player.

1. Get the removed code: `git diff origin/main development -- packages/inject/youtube.js` and take the deleted mini-player functions and the original `__ytf_miniOnBack` and `__ytf_setPip` branch that calls `restoreMiniToPage()`.
2. Put it back behind one guard that is false on the phone: `const WEB_MINI = !IS_PHONE_SPLIT && <not TV>`. Use the flag the file already has for TV if there is one; do not add a new detection method. When `WEB_MINI` is false, `__ytf_miniOnBack` returns `false` as now.
3. Do **not** restore the two things removed on purpose: the 900ms `hideOpenApp` text scan with its "unmute" auto-click, and `reloadOnceForAd`. They caused the Shorts sound toggling and the heavy feel on the phone.
4. Check `apps/desktop/src/main/shell.js:152` and `apps/desktop/src/main/pip.js:80` still match the restored return values.

**Done when:** `npm run desktop:test` passes (43), `npm test` passes, and the phone debug build still opens a video, docks on Back, and expands (WP1 rows 1 and 8).

## WP3 — Regression pass (Sonnet 5.5)

Run on the emulator with the release APK after WP1 and WP2:

1. Open a 40-minute video: plays with sound, no crash. Open three videos in a row from the feed: no crash, Back from the third docks it, second Back leaves the app's player closed and goes home (not to video two).
2. Shorts: swipe to the next and previous Short five times each.
3. Speed pill 1x to 4x, the YouTube / YouTube Music switcher, resume position after closing and reopening a video.
4. Lock the screen during playback: audio continues, lock-screen controls work. Home button during playback: Picture-in-Picture starts and returns cleanly.
5. No "Sponsored" cards on Home and Search, no pre-roll on five popular videos, no "skipping ads" pill left on screen after an ad.
6. All unit tests in the Environment section pass.

Update `docs/KNOWN-ISSUES.md`: move what you verified from UNVERIFIED to a dated "verified on emulator" note, and add anything new you found. Real-phone checks stay UNVERIFIED unless the user confirms them.

## WP4 — Build and push (Sonnet 5.5)

1. `git status` shows only the two gradle files as modified and nothing private as untracked-and-staged. Run `git ls-files | Select-String 'restore-private|\.jks|config/apps'` and confirm the only hit is `config/apps/example.json`.
2. Build the release APK. Install it over the previous build on the emulator (`adb install -r`) to confirm the signature and build number are accepted as an update.
3. `git push origin development`.
4. Open a pull request from `development` to `main` only if the user asks. Do not merge it yourself.

## Out of scope

Brightness control (P3), the Android TV pass (V1–V3) and desktop code signing are open upstream items and are not part of this plan.
