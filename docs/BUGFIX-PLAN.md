# YouTube Freemium (Android phone) — bug-fix plan

Written for an engineer or model that has not seen this repo. Do the work packages in order. Each one names the files, the cause, the change, and how to prove it worked.

## 0. Read this first

**What the app is.** A Capacitor Android shell around a WebView that loads `https://www.youtube.com/`. On a phone that serves YouTube's **mobile website** (`m.youtube.com`, custom elements named `ytm-*`), not the desktop site (`ytd-*`). Everything "app-like" is done by scripts injected at document start plus a little Java.

**Files that matter**

| File | Role |
|---|---|
| `packages/inject/youtube.js` | The YouTube-specific script: ad pruning, ad skipping, PiP, mini player, speed chip. Most bugs are here. |
| `packages/inject/navguard.js` | Blocks off-site navigation and removes "invisible overlays". |
| `packages/inject/cosmetic.js` | Applies adblock hide rules and uBlock scriptlets. |
| `apps/mobile/android/app/src/main/java/app/movy/shell/MainActivity.java` | Back button (`onBack`, line ~802), PiP, lifecycle. |
| `apps/mobile/android/app/src/main/java/app/movy/adblock/CosmeticBridge.java` | Injects the scripts (lines 103–110). |
| `apps/mobile/android/app/src/main/java/app/movy/shell/MediaBridge.java` | JS → Java bridge, exposed as `window.YouTubeFreemiumBridge`. |
| `config/apps/youtube-freemium.json` | Private app config. **Git-ignored. Never commit it, never print its contents.** |

**Rules**

- `youtube.js` is shared with the desktop (Electron) app and Android TV. `apps/desktop/src/main/shell.js:152` calls `__ytf_miniOnBack`, and `apps/desktop/src/main/pip.js:80` expects `__ytf_setPip(true, true)` to return `'mini'` or `'ok'`. Keep every `window.__ytf_*` function defined and keep those return values, even if a function becomes a stub.
- Do not touch TV code paths (`cfg.tv`, `tvnav*.js`, `tvfs.js`).
- Smallest change that fixes the bug. No new dependencies. Delete code rather than add flags.
- Never commit `restore-private.mjs` or `config/apps/*.json`.

**Confidence labels used below**

- **CONFIRMED** — the cause is visible in the code; no device needed to believe it.
- **SUSPECTED** — a likely cause found by reading; it must be reproduced on a phone (Phase 0) before it is changed.

Nothing in this plan was tested on a device. No phone was connected when it was written.

## Phase 0 — Build, connect, get a debuggable WebView

Without this, the SUSPECTED items cannot be confirmed.

1. Build tools (already installed on the author's machine): Node 22, JDK 21 via `MOVY_JAVA_HOME`, Rust gnu + cargo-ndk, NDK 27, PowerShell 7. Node 22 must be first in `PATH`:
   ```powershell
   $env:PATH = "$env:NVM_HOME\v22.23.5;$env:USERPROFILE\.cargo\bin;C:\Program Files\PowerShell\7;$env:PATH"
   npm run build -- --app youtube-freemium --target phone
   ```
2. Enable WebView inspection in debuggable builds only. In `MainActivity.load()`, after `configureSettings(wv)`, add:
   ```java
   if (cfg.debug) WebView.setWebContentsDebuggingEnabled(true);
   ```
   `cfg.debug` is true only when the APK is debuggable (`ShellConfig.java:61`).
3. The npm build only makes a release APK. After one npm build has generated the project, build a debuggable one directly:
   ```powershell
   cd apps\mobile\android; .\gradlew.bat assemblePhoneDebug
   ```
   It is signed with a different key, so uninstall the release app before installing it.
4. Install with `adb install`, open `chrome://inspect` on the PC, and attach to the WebView. Use the DevTools **Console**, **Elements** and **Performance** tabs.
5. Turn on the script's own logging: set `const DEBUG = true` at `youtube.js:5` (revert before finishing). Watch it with:
   ```
   adb logcat -s YTF MovyAdblock MovyShell MovyDebug
   ```
6. Reproduce each bug once and write down, for each: the URL (`location.href`), a screenshot, and the checks listed in that work package. Put the notes in `docs/BUGFIX-NOTES.md`.

## WP1 — Back button behaves like a browser (CONFIRMED) — highest priority

**Symptom.** Open video A, tap a related video B, press Back → returns to video A. Expected: Back from any video returns to the list the user came from (home, search results, a channel), never to an earlier video.

**Cause.** `MainActivity.onBack()` (line 802) calls `__ytf_miniOnBack()` (`youtube.js:1138`), which does `history.back()`; otherwise `backDefault()` calls `wv.goBack()`. Both go one history entry back, and the entry before video B is video A.

**Fix (Java, unit-testable).**

1. New file `apps/mobile/android/app/src/main/java/app/movy/shell/BackPolicy.java`, pure Java, no Android imports (same style as `NavRules.java` / `HomePageRule.java`):
   ```java
   /** Back from a video skips earlier videos and lands on the last list page. */
   final class BackPolicy {
       static final int DEFAULT = 0;   // caller does normal goBack()
       static final int HOME = 1;      // caller loads the source's home URL
       // negative return value = steps for goBackOrForward()
       static int decide(java.util.List<String> urls, int current) { ... }
       static boolean isPlayerPage(String url) { ... } // path starts with /watch or /shorts/
   }
   ```
   Logic of `decide`: if `urls.get(current)` is not a player page → `DEFAULT`. Otherwise walk `i = current-1 … 0`; the first entry that is not a player page gives `i - current` (negative). If none exists → `HOME`.
   Apply only when the host is `youtube.com` / `m.youtube.com` / `www.youtube.com`. For `music.youtube.com` always return `DEFAULT` (its player is an overlay, not a page).
2. In `MainActivity.onBack()`, replace the `!cfg.tv` branch (lines 819–822) with:
   ```java
   WebBackForwardList h = wv.copyBackForwardList();
   List<String> urls = ...; // h.getItemAtIndex(i).getUrl() for each i
   int d = BackPolicy.decide(urls, h.getCurrentIndex());
   if (d < 0) wv.goBackOrForward(d);
   else if (d == BackPolicy.HOME) repo.openUrl(repo.active().id, repo.active().url); // loader already clears history
   else backDefault(wv);
   ```
   Check `SourceRepository` for the exact method that reloads the active source's home; `repo.setLoader` (MainActivity line 328) already sets `clearHistoryOnFinish = true`.
3. Fullscreen, settings sheet, island and chooser checks above that branch stay exactly as they are.
4. New test `apps/mobile/android/app/src/test/java/app/movy/shell/BackPolicyTest.java` covering: home→A→B gives `-2`; search→A gives `-1`; deep link straight to a video gives `HOME`; home→shorts→shorts→shorts gives `-3`; a non-video page gives `DEFAULT`; a music.youtube.com watch URL gives `DEFAULT`.

**Verify in Phase 0 before coding:** YouTube's mobile site navigates with `history.pushState`. Log `copyBackForwardList()` after home→A→B and confirm it has three entries with the right URLs. WebView normally records pushState entries. If it does not, do the same walk in JS with `navigation.entries()` and `history.go(n)`.

**Acceptance.** home→A→B→Back lands on home. search→A→B→C→Back lands on the search results. Back on home exits the app as today.

## WP2 — Remove the iframe mini player (CONFIRMED source of glitches)

**Symptom.** "When I press back, the video doesn't do what the YouTube app does", "the video gets stuck".

**Cause.** Section 10 of `youtube.js` (lines 1012–1192). On Back it pauses the real video, opens a *second* player — the public `youtube.com/embed/` iframe — in a floating box at `right:12px; bottom:84px`, then navigates. The embed reloads the video from the network, can show its own ads, fails on videos with embedding disabled, and is converted back to a full page load whenever the app is backgrounded (`restoreMiniToPage`, line 1125). It never looked like the YouTube app's docked mini player.

**Fix.** Delete it on phone. With WP1, Back simply leaves the video.

1. In `youtube.js` remove: the `mini` object, `miniFront`, `miniCmd`, `closeMini`, `openMini`, the `message` listener at 1096, `restoreMiniToPage`, the `ytf_mini_pending` block (1160–1172), the 700 ms interval (1175), `miniPip`, and the mini branch at the top of `reportCurrentMediaState` (535–548).
2. Keep stubs so desktop does not break:
   ```js
   window.__ytf_miniOnBack = function () { return false; };
   ```
   In `__ytf_setPip`, drop the `restoreMiniToPage()` and `miniPip` calls; it must still return `'ok'` when `native` is true. In `__ytf_play`, `__ytf_pause`, `__ytf_resumeIfWanted`, `__ytf_setBg` drop the `miniFront()` branches.
3. Run `npm test`. If a desktop test asserts the mini player exists, update the test and note it in the summary.

**Open product question for the owner (do not build without a yes):** a real YouTube-app-style mini player docked at the bottom. It is a separate feature, not a bug fix, and is hard in a web wrapper.

## WP3 — Video pinned over the page / scrolling behind it (SUSPECTED)

**Symptom.** The video stays fixed at the top or covers the screen while the page scrolls behind it.

**Note.** On YouTube's mobile site the player *is* meant to stay at the top of a watch page while comments scroll. That alone is not a bug. The bug is when it stays on a non-video page, covers more than its normal 16:9 area, or the page under it is the wrong one.

**Suspect A — stale PiP isolation (most likely).** `youtube.js` stores "in PiP" in `sessionStorage` key `ytf_inpip` (lines 618, 681). An interval (line 1197) re-applies `pipEnter()` whenever that flag is `1` on a watch page. `pipEnter()` marks the video and all its ancestors `position:fixed; 100vw × 100vh; z-index:2147483000` (`PIP_CSS`, line 623). If the "left PiP" call from Java (`MainActivity.onPictureInPictureModeChanged`, line 648) is lost — for example it runs while a page is loading — the flag stays `1` for the rest of the session and every later video is forced into that fixed layout.

**Suspect B — the mini player** leaving a paused real player behind. WP2 removes it.

**Confirm.** When the bug is on screen, run in the DevTools console:
```js
[sessionStorage.ytf_inpip, document.querySelectorAll('[data-ytf-pip]').length, !!document.getElementById('ytf-mini'), location.pathname]
```
`'1'` with a non-zero count while not in PiP confirms Suspect A.

**Fix for A.** Make Java the only source of truth; stop persisting the flag.

1. `MediaBridge.java`: add a `BooleanSupplier` set by `MainActivity` and
   ```java
   @JavascriptInterface public boolean inPip() { return pipState != null && pipState.getAsBoolean(); }
   ```
   `MainActivity` supplies a `volatile boolean` it updates in `onPictureInPictureModeChanged` (the bridge is called off the UI thread, so do not call `isInPictureInPictureMode()` from it).
2. `youtube.js`: delete both `sessionStorage` uses of `ytf_inpip`. In the 400 ms interval, read `window.YouTubeFreemiumBridge.inPip()` (guarded with `try`; on desktop the bridge has no such method, so treat a throw as "keep current value"). If it says false and `pipSaved.length > 0`, call `pipExit()`.
3. `pipExit()` must also clear leftovers from an earlier page: `document.querySelectorAll('[data-ytf-pip],[data-ytf-hide],[data-ytf-pip-root]')` → remove those attributes.

**Acceptance.** Enter PiP, return, open another video, rotate, background and resume: `document.querySelectorAll('[data-ytf-pip]').length` is `0` whenever the app is not in PiP.

## WP4 — Shorts: cannot move to the next or previous video (SUSPECTED)

**First, pin down the symptom.** The report is ambiguous. Record which of these fails: (a) swipe up/down to the next/previous Short, (b) seeking within a Short, (c) either only after the first tap.

**Bisect — do this before changing code.** Test Shorts after each step and stop at the first step where it works:

| Step | How | If Shorts now works, the culprit is |
|---|---|---|
| 1 | Settings sheet in the app → turn ad blocking off | network blocking or cosmetic rules |
| 2 | Comment out the `youtube` line in `CosmeticBridge.install` (line 109) | `youtube.js` → go to the list below |
| 3 | Comment out the `navguard` line (107) | `navguard.js` |
| 4 | Comment out the `script` line (105) | `cosmetic.js` / a uBlock rule |

**Suspects inside `youtube.js`, most likely first**

1. **Response pruning of the Shorts feed.** `AD_URL_RE` (line 33) includes `reel/reel_item_watch` and `reel/reel_watch_sequence`. `pruneAds` splices any array element that contains an ad key within 3 levels (line 54). If a real Short's entry carries an ad-related key, it is removed and the sequence breaks. Test by removing the two `reel/…` alternatives from the regex.
2. **Auto-click on "unmute".** `hideOpenApp` (lines 994–999) clicks any visible element whose class contains `unmute` every 900 ms. On Shorts that can be the sound button, toggled over and over. Delete that block; the `ytf_unmute` handling at line 1198 only existed for the mini player and goes with WP2.
3. **`findMainVideo()`** (line 283) returns the first `<video>`. The Shorts page holds several. `applyRate`, `restoreProgress` and the pause/resume logic (803–818) then act on the wrong one. Fix: prefer the video that is playing or most visible:
   ```js
   function findMainVideo() {
     const vs = [...document.querySelectorAll('video')];
     return vs.find((v) => !v.paused && v.readyState > 2) || document.querySelector('video.html5-main-video') || vs[0] || null;
   }
   ```
4. **Forced resume.** The `pause` listener (803–818) restarts a video that paused without a recent touch during a 4 s "grace" window. When YouTube pauses the outgoing Short, this can fight it. Skip the whole listener when `location.pathname.startsWith('/shorts')`.

**Suspect in `navguard.js`.** `onClickNonAnchor` (line 195) deletes a tapped element when it is transparent, absolutely positioned, a direct child of `<body>`, and covers over 30 % of the screen. A Shorts gesture layer could match after the first tap — which fits symptom (c). If step 3 of the bisect points here, return early from `onClickNonAnchor` when `location.hostname` ends with `youtube.com`; the click-jack protection is for other sites.

**Acceptance.** Swipe through 20 Shorts both directions, tap to pause and resume several times, sound stays as the user set it.

## WP5 — Ads still appear, and the "Skipping ads for you" pill (SUSPECTED cause, CONFIRMED gaps)

The app has two layers: (1) remove ad data from YouTube's JSON so ads never load, (2) if an ad plays anyway, mute it, play at 16×, jump to the end, click Skip, and show the pill. **Seeing the pill means layer 1 failed.** The goal is to make layer 1 work, not to tune layer 2.

**5a. Find out why pruning misses (Phase 0 logging).** With `DEBUG = true`, play 10 videos. The log prints which `/youtubei/v1/*` responses had ad keys and whether they were pruned (lines 83–85, 128). Record for one video that showed an ad: was there a `pruned ads from …/player` line before it? If no, the ad data arrived another way. Check, in this order:

- `fetch` called with a `Request` object whose URL is read correctly (line 78) — log `url` when empty.
- Responses that are not a single JSON object (line 82 `JSON.parse` throws and the original is returned silently — log the exception).
- Player data embedded in the page: `window.ytInitialPlayerResponse` is hooked (line 93) but `ytcfg`/`ytplayer.bootstrapPlayerResponse` are not. In the console on a fresh watch page, search the globals for `adPlacements`.
- Ad keys not in `AD_KEYS` (line 27). Dump the top-level keys of an unpruned player response and compare.

Fix whichever is found, inside `pruneAds` / the hooks. Add the missing key or hook; do not add a new mechanism.

**5b. uBlock scriptlets may be silently failing (SUSPECTED).** The default lists include uBlock Origin's filters, which carry YouTube-specific scriptlets. `cosmetic.js:58–59` runs them with `eval(res.injected_script)` inside an empty `catch`. YouTube enforces Trusted Types, under which `eval` of a plain string throws. Check: temporarily log in that `catch`, and log `res.injected_script.length` for a `youtube.com` URL.

- If it throws: create a Trusted Types policy and eval through it —
  ```js
  var code = res.injected_script;
  try { if (window.trustedTypes) code = trustedTypes.createPolicy('movy-scriptlets', { createScript: function (s) { return s; } }).createScript(code); } catch (e) {}
  ```
  then `eval(code)`. If YouTube's CSP rejects the policy name or eval itself, stop and report; do not work around CSP further.
- If scriptlets do run, `youtube.js` pruning and uBlock's pruning both rewrite the same responses. That is harmless; leave it.

**5c. Sponsored cards in feeds — CONFIRMED gap.** The hide list (`SELECTORS.adContainers`, `youtube.js:210–247`) is almost all desktop `ytd-*` elements. The phone gets the mobile site. Add the mobile equivalents. Candidates — **confirm each in DevTools Elements on a real sponsored card before adding**:

```
ad-slot-renderer
ytm-promoted-sparkles-web-renderer        (already present)
ytm-promoted-sparkles-text-search-renderer
ytm-promoted-video-renderer
ytm-companion-slot
ytm-companion-ad-renderer
ytm-statement-banner-renderer
ytm-rich-item-renderer:has(ad-slot-renderer)
ytm-item-section-renderer:has(> lazy-list > ad-slot-renderer)
```

For any sponsored card still visible, inspect it, note its tag name and the JSON renderer key behind it, add the tag to `adContainers` and the key to `AD_ITEM_KEYS` (line 29). The same list is duplicated in `packages/inject/youtube-selectors.json` (used by desktop) — keep the two identical.

**5d. Make the fallback less disruptive — CONFIRMED.**

- `reloadOnceForAd` (line 313, triggered at line 361) reloads the whole page if an ad lasts 4 s. That is a visible glitch. Remove the call and the function.
- Pill: `AdPill.java` keeps it up at least 1.4 s. Leave the Java alone. In `youtube.js` only call `showAdCaption(true)` after the ad has been active for more than 1 s (`Date.now() - adState.since > 1000`), so instantly skipped ads show nothing.

**Limits to state honestly in the final report.** Ads stitched into the video stream by the server cannot be removed by any of this. And if "sponsored content" means a creator's in-video sponsor read, that is a SponsorBlock-style feature — out of scope; ask the owner.

**Acceptance.** 20 videos in a row signed out: no ad plays, the pill never appears, no sponsored card in home feed, search results, or under the player.

## WP6 — General glitchiness and heaviness (CONFIRMED)

`youtube.js` does a lot of work continuously on every page:

| Where | What it does | Cost |
|---|---|---|
| lines 402–418 | `MutationObserver` on the whole document, including every `class`/`style` change, calling `checkAndSkipAds()` each time | ~15 `querySelector` calls, three with `:has()`, on every DOM change. YouTube mutates constantly while scrolling. |
| lines 304, 427 | two 250 ms intervals | same function again |
| line 1207 → 990 | `hideOpenApp` every 900 ms reads `textContent` of **every** `a, button, ytm-button-renderer, yt-button-shape` | very expensive on a long feed |
| line 1206 | `ensureSpeedChip` every 300 ms | layout reads (`offsetWidth`) |
| line 840 | `timeupdate` handler | fine |

**Changes**

1. Replace the document-wide observer with one on the player only: observe `#movie_player` (fallback `.html5-video-player`) with `{ attributes: true, attributeFilter: ['class'] }`. Re-attach when the element changes; check for it in the existing 250 ms poll. Keep **one** 250 ms poll (line 427) and delete the interval at 304 by folding its two lines into `checkAndSkipAds`.
2. In `checkAndSkipAds`, return immediately after the skip-button check when `!isAdPlaying() && !adState.active`, except run the enforcement-dialog and YouTube Music checks at most once per second.
3. Join `SELECTORS.skipButtons` into one selector string once, at startup: `document.querySelector(SKIP_SEL)`.
4. `hideOpenApp`: delete the text scan (lines 1000–1007). The CSS at line 264 already hides the known "open app" elements. If a button survives, add its selector to that CSS. Delete the 900 ms interval.
5. `ensureSpeedChip`: slow to 1000 ms; call it immediately from the existing `play` and touch listeners so it still feels instant.
6. `cosmetic.js` generic hiding costs a Java call per batch of new class names, forever, on a site where it finds nothing useful. In the **private** config add to `adblock.customRules`: `@@||youtube.com^$generichide`. This is a local edit to a git-ignored file — do not commit it; tell the owner to add it to their own config.

**Acceptance.** DevTools Performance, 10 s recording while scrolling the home feed: no long task (> 50 ms) whose call stack is inside the injected scripts. Scrolling and Shorts swiping feel the same as in Chrome on the same phone.

## WP7 — Finish

1. Set `DEBUG = false` again in `youtube.js`. Remove any bisect comment-outs in `CosmeticBridge.java`.
2. `npm test` — must pass.
3. Java unit tests: `cd apps\mobile\android; .\gradlew.bat testPhoneDebugUnitTest` (run `.\gradlew.bat tasks --all | findstr /i test` if the task name differs).
4. `npm run build -- --app youtube-freemium --target phone` and install.
5. Manual pass on the phone — every line must hold:
   - home → video A → related B → Back = home.
   - search → A → B → C → Back = search results.
   - Back on home exits.
   - Fullscreen video → Back = leaves fullscreen only.
   - PiP in and out three times; next video lays out normally.
   - 20 Shorts swiped up and down; pause/resume by tap.
   - 20 videos: no ad, no pill.
   - Home feed and search: no sponsored cards.
   - Background play with screen off still works (Settings toggle on).
   - YouTube Music still plays, Back there behaves as before.
6. Write a short summary: what was confirmed in Phase 0, what was changed per work package, anything that could not be fixed, and the answers still needed from the owner.

## Questions for the owner

1. After Back from a video: is "video closes, you are on the list" acceptable, or is a docked mini player required? (WP2)
2. Shorts: is the problem swiping between Shorts, or seeking inside one? (WP4)
3. "Sponsored content": feed cards marked *Sponsored*, or sponsor reads inside videos? (WP5)
