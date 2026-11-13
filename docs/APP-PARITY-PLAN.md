# YouTube Freemium — make it behave like the real YouTube app

Goal: the Android phone app should feel as close to the official YouTube app as a web wrapper can get, with no ads. YouTube Music stays as the second source. No other site will ever be loaded in this app.

This is the master plan. It builds on `docs/BUGFIX-PLAN.md`; read that file's section 0 (files, rules, confidence labels) first — all of it applies here.

Nothing here was run on a phone. Items marked **VERIFY** name a fact to check in DevTools before coding against it.

## Order of work

| Phase | What | Size |
|---|---|---|
| 0 | Debuggable build and WebView inspection | `BUGFIX-PLAN.md` Phase 0 |
| 1 | Stabilise: bugs, ads, performance | `BUGFIX-PLAN.md` WP1–WP6, with the amendments below |
| 2 | Player like the app: docked mini player, correct Back | Large — the main work |
| 3 | App feel: ten small items | Small each |
| 4 | Final check | — |

Ship and test each phase on its own. Do not start Phase 2 until Phase 1 passes its acceptance list.

### Amendments to `BUGFIX-PLAN.md`

- WP1 step 2: the method that reloads a source's home page is `repo.openStart(id)` (`SourceRepository.java:124`).
- WP2 (delete the iframe mini player) is now mandatory and has no open question: Phase 2 replaces it.
- WP4 `navguard.js`: since only YouTube is loaded, make `onClickNonAnchor` return immediately. Do not delete the file; the off-site link blocking in it is still wanted.
- The three "questions for the owner" are answered: a docked mini player **is** required (Phase 2); treat Shorts as "swiping between Shorts fails" unless Phase 0 shows otherwise; "sponsored" means feed cards and video ads.

## Phase 2 — the player

### What the real app does

1. Tapping a video opens the player over the current list.
2. Back, or swiping the video down, shrinks it to a bar docked above the bottom navigation. It keeps playing. The list underneath is exactly where the user left it.
3. Tapping the bar expands it again. The bar has play/pause and close.
4. Tapping a related video replaces the current one. Back still minimises; it never returns to an earlier video.

### Why the current design cannot do this

One WebView holds one page. Leaving the watch page to show the list tears the player down. The old workaround re-opened the video in an embedded iframe, which reloads it and brings its own problems.

A fact confirmed on the live mobile site: `#player-container-id` is a direct child of `<body>`, next to `ytm-app`, with `position: fixed` and class `sticky-player`. So the player already floats above the page. That is why "the video stays at the top and the page scrolls behind it" — it is YouTube's layout, and it is correct on a watch page.

*Rejected alternative:* keep one WebView and re-style that fixed container as a bottom bar after navigating away. YouTube's own code stops the player on route change, so this means fighting its internals and breaks whenever they change.

### Design: two WebViews

- **Browse view** — the existing Capacitor WebView. Shows home, search, channels, subscriptions, library, Shorts. It never stays on a `/watch` page.
- **Player view** — a second, plain `WebView` in a container above the browse view. It only ever shows `/watch` pages.

Minimising is a native resize of the player container. The video never reloads and the browse view never moves. Back is correct by construction because watch pages are not in the browse history at all.

This applies only when the active source is YouTube. For YouTube Music the player view is never created; Music's own site already has a player bar.

### 2.1 `PlayerHost.java` (new, `app/movy/shell/`)

Owns the container and the second WebView.

```java
final class PlayerHost {
    enum State { HIDDEN, FULL, MINI }
    void open(String watchUrl);   // create WebView on first use, loadUrl, state = FULL
    void minimize();              // FULL -> MINI
    void expand();                // MINI -> FULL
    void close();                 // loadUrl("about:blank"), state = HIDDEN (keep the WebView for reuse)
    State state();
    WebView webView();            // null until first open
}
```

Layout, added to `android.R.id.content` **after** the Capacitor WebView and before the island/ad pill so those stay on top:

- `FULL`: container `MATCH_PARENT`; player WebView fills it.
- `MINI`: container is a bar, full width, 64dp high, bottom margin equal to the height of YouTube's bottom navigation bar (**VERIFY** the bar's height in CSS px in DevTools; it is `ytm-pivot-bar-renderer`). Inside: the player WebView at the left, 114×64dp; a `TextView` title and a `TextView` channel; an `ImageButton` play/pause; an `ImageButton` close. Tapping anywhere else on the bar calls `expand()`.
- Title, channel and playing state come from the media report the page already sends (`MediaBridge.reportMediaState`). Play/pause calls the existing `__ytf_play` / `__ytf_pause`.
- Animate state changes with `TransitionManager.beginDelayedTransition(container)`. Nothing fancier.
- Use the colours in `UiTokens.java`; do not invent new ones.

Set up the player WebView exactly like the browse one, reusing existing code:

- `configureSettings(wv)` from `MainActivity` (make it callable for both).
- `CosmeticBridge.install(...)` — injects the same scripts and the `MovyAdblock` interface.
- `new AdblockWebViewClient(bridge, cfg, rules, stats)` — a second instance. Its base class calls `bridge.reset()` in `onPageStarted` and serves Capacitor's local files; this app uses no Capacitor plugins, so that is harmless. **VERIFY** by reading `node_modules/@capacitor/android/capacitor/src/main/java/com/getcapacitor/BridgeWebViewClient.java` and confirming nothing there loads a URL into the *main* WebView (`bridge.getErrorUrl()` must be null).
- `new ShellWebChromeClient(bridge, this, cfg, rules, stats, loadingUi)` — a second instance, so fullscreen video works from the player view. Every `chrome.isFullscreen()` check in `MainActivity` must become "either chrome client is fullscreen"; `chrome.onHideCustomView()` must go to the one that is.
- Its own `MediaBridge` instance (see 2.2).
- Do **not** attach failover, loading splash, health or history-clearing events to the player view.

### 2.2 Tell each page which view it is in

`MediaBridge` gets a constructor argument and one method:

```java
@JavascriptInterface public String role() { return role; }   // "browse" or "player"
```

In `youtube.js`, once at the top: `let ROLE = 'browse'; try { ROLE = window.YouTubeFreemiumBridge.role(); } catch (e) {}`. On desktop the method does not exist, so desktop stays `'browse'` with **no** interception — guard every new block below with `IS_PHONE_SPLIT`, a flag that is true only when `role()` returned a value and the host is not `music.youtube.com`.

New bridge methods, each just forwarding to `MainActivity` on the UI thread: `openVideo(String url)`, `openBrowse(String url)`, `minimize()`.

### 2.3 Routing — keep each view on its own kind of page

A "watch URL" is: host `youtube.com`, `www.youtube.com` or `m.youtube.com`, path exactly `/watch`. Put this test in one pure Java method (`BackPolicy.isWatch(String url)`) and one JS function, and use them everywhere.

**Browse view → player**

1. JS, capture phase, in `youtube.js` when `ROLE === 'browse'`:
   ```js
   document.addEventListener('click', (e) => {
     const a = e.target.closest && e.target.closest('a[href]');
     if (!a || new URL(a.href).pathname !== '/watch') return;
     e.preventDefault(); e.stopImmediatePropagation();
     window.YouTubeFreemiumBridge.openVideo(a.href);
   }, true);
   ```
2. Safety net in Java for anything the click handler misses (script-driven navigation, autoplay): in the browse view's `PageEvents.onUrl`, if `BackPolicy.isWatch(url)` → `player.open(url)`, then `wv.goBack()` if `wv.canGoBack()`, else `repo.openStart(...)`.
3. So the browse view never makes sound while that correction happens: in `youtube.js`, when `ROLE === 'browse'` and the path is `/watch`, pause every `<video>` on each tick of the existing 250 ms poll.
4. `handleDeepLink` (`MainActivity.java:747`): a watch URL goes to `player.open(url)`; the browse view loads its home if it has nothing loaded.

**Player view → browse**

1. JS when `ROLE === 'player'`: same click listener, opposite test — a link whose path is **not** `/watch` → `preventDefault`, `openBrowse(a.href)`.
2. `openBrowse` in Java: `bridge.getWebView().loadUrl(url)` then `player.minimize()`.
3. Safety net: the player view's URL becomes a non-watch URL → send that URL to the browse view, `player.webView().goBack()`, `player.minimize()`.
4. Links to other watch pages (related videos, autoplay, playlists) are left alone — they load inside the player view.

**Shorts** stay in the browse view. When the browse URL path starts with `/shorts/`, call `player.close()` (the real app also drops the mini player when Shorts starts).

**Source switch** (island → YouTube Music): `player.close()`.

### 2.4 The player page should look like the app's player

Only when `ROLE === 'player'`, add CSS:

- Hide YouTube's top bar and bottom navigation: **VERIFY** the element names in DevTools; expected `ytm-mobile-topbar-renderer` and `ytm-pivot-bar-renderer`.
- The fixed player sits 48 px down to leave room for that top bar. With the bar hidden, set `#player-container-id { top: 0 !important }` and remove the matching top padding on the page content. **VERIFY** which element carries the padding.

Only when `ROLE === 'browse'`: nothing to hide.

**Mini mode.** When the container shrinks, the page must show only the video. The script already has exactly this: `pipEnter()` / `pipExit()` (`youtube.js:635–671`) mark the video's ancestor chain to fill the viewport and hide everything else. Expose it:

```js
window.__ytf_setMini = function (on) { if (on) pipEnter(); else pipExit(); };
```

`PlayerHost.minimize()` calls `__ytf_setMini(true)` **before** resizing; `expand()` resizes, then calls `__ytf_setMini(false)`. This replaces the idea of scaling the view.

### 2.5 Back button — final rules

Replace the `!cfg.tv` branch of `MainActivity.onBack()` (the existing checks for TV cursor, settings sheet, island and chooser stay first, in their current order):

1. Any view in fullscreen → leave fullscreen.
2. Player `FULL` → `player.minimize()`.
3. Otherwise the browse view goes back, using `BackPolicy.decide` from WP1 (it still matters for chains of Shorts).
4. Browse view cannot go back:
   - player `MINI` → `moveTaskToBack(true)` — the app leaves the screen and the video keeps playing, as the real app does.
   - player `HIDDEN` → `finish()`.

Add these cases to `BackPolicyTest` where they are pure logic; rules 1–4 need a small pure method too, e.g. `BackPolicy.action(boolean fullscreen, State player, boolean canGoBack)` returning an enum, so they can be unit-tested without Android.

### 2.6 Swipe down to minimise

In `youtube.js`, `ROLE === 'player'` only: on `touchstart` inside `#player-container-id` remember Y; on `touchend`, if the finger moved down more than 80 px, mostly vertically, and the page is not fullscreen → `window.YouTubeFreemiumBridge.minimize()`. No drag-follow animation.

### 2.7 Everything that talks to "the WebView" must pick the right one

`MainActivity` has about 30 uses of `bridge.getWebView()`. Add one helper:

```java
/** The view whose media the user is hearing: the player when open, else the browse view. */
private WebView mediaView() { return player != null && player.state() != PlayerHost.State.HIDDEN ? player.webView() : bridge.getWebView(); }
```

- `evalJs(...)` for `__ytf_play`, `__ytf_pause`, `__ytf_next`, `__ytf_prev`, `__ytf_seekTo`, `__ytf_setBg`, `__ytf_resumeIfWanted`, `__ytf_setPip` → `mediaView()`.
- `onResume` / `onPause` / `onStop` keep-alive calls (`onResume()`, `resumeTimers()`) → both views.
- Media reports: ignore reports from the browse view while the player is not `HIDDEN`.
- Picture-in-picture: on entering, if the player is `MINI` call `expand()` first, then the existing `__ytf_setPip(true)` path; hide the browse view while in PiP and show it again on exit.
- Cookies, failover, loading UI, source repository → browse view only, unchanged.

### Phase 2 acceptance

- Tap a video on home: it opens. Back: it docks above the bottom bar, still playing, home is at the same scroll position.
- With it docked: scroll, search, open a channel — playback never stops or restarts.
- Tap the bar: it expands at the same second. Close button: it stops and disappears.
- Docked video + tap another video: the new one replaces it, expanded.
- In the player, tap a related video, then Back: docks (does not go to the first video).
- In the player, tap the channel name: the channel opens in the list and the video docks.
- Swipe the video down: docks.
- Docked video, Back repeatedly to home, Back again: the app goes to the launcher and audio continues; notification controls work.
- Fullscreen and PiP work from the player; rotating back leaves fullscreen cleanly.
- Open Shorts while a video is docked: the docked video closes.
- Switch to YouTube Music and back: no leftover bar.
- A `youtube.com/watch?...` link from another app opens straight in the player.

## Phase 3 — app feel

Each item is independent. Do them in this order; skip none without saying so in the summary.

| # | Behaviour in the real app | Change |
|---|---|---|
| 1 | Screen never sleeps during a video | In `onMediaStateUpdated`: add `FLAG_KEEP_SCREEN_ON` when `isPlaying && isVideo`, clear it otherwise. |
| 2 | Turn the phone sideways → fullscreen; upright → back | `OrientationEventListener` in `MainActivity`. Landscape while the player is `FULL` and playing → call `injectGestureKey()` on the player view (it already exists at line 542 for exactly this user-activation problem), then run JS to click the player's fullscreen button. Respect the system auto-rotate setting. Logic of "which angle means what" goes in the existing `OrientationPolicy.java` with a test. |
| 3 | Share opens the Android share sheet | WebView has no `navigator.share`. In `youtube.js`: `navigator.share = (d) => { window.YouTubeFreemiumBridge.share(d.url || location.href, d.title || ''); return Promise.resolve(); }`. Java: `Intent.ACTION_SEND`, `text/plain`, via `Intent.createChooser`. This is the one place the app hands off to other apps. |
| 4 | Pull down on a list to refresh | **VERIFY** first whether the mobile site already does this in the WebView. If not: add `androidx.swiperefreshlayout:swiperefreshlayout` and wrap the browse WebView only; `setOnChildScrollUpCallback` returns `wv.getScrollY() > 0`; refresh = `wv.reload()`. Disabled on `/shorts/`. |
| 5 | No text selection, no grey tap flash, no stretch glow | CSS in `youtube.js`: `* { -webkit-tap-highlight-color: transparent } body { -webkit-user-select: none; -webkit-touch-callout: none } input, textarea { -webkit-user-select: text }`. Java, both views: `setOverScrollMode(OVER_SCROLL_NEVER)`. |
| 6 | Status bar matches light/dark theme | `setupSystemBars` uses the fixed config colour. Have `youtube.js` report `getComputedStyle(document.body).backgroundColor` through a bridge method on load and when `html[dark]` changes; Java re-runs the colour part of `setupSystemBars`. |
| 7 | Hold on the video to play at 2× | **VERIFY** whether the mobile site already does it. If not, `ROLE === 'player'`: 500 ms hold on the video sets `playbackRate = 2`, release restores the saved rate. Must not fire during a swipe (item 2.6). |
| 8 | No floating extras on the video | The "1x" and "PiP" chips (`ensureSpeedChip`, `youtube.js:934`) are not in the real app. Remove the PiP chip — PiP is automatic on Home. Keep the speed chip only if the site's own speed menu stops at 2×; say which in the summary. |
| 9 | YouTube Music: Back closes the full-screen player first | In `onBack`, for the Music source, before history back: run JS that checks `ytmusic-app-layout[player-page-open]` and clicks the collapse button; if it returned true, stop. **VERIFY** both selectors. |
| 10 | Remembered video quality | Skip unless trivial: **VERIFY** whether the site keeps the user's choice in `localStorage` already. Do not build a quality menu. |

Leave alone: the island (the YouTube ⇄ YouTube Music switcher), the settings sheet, background play, the notification controls, the splash screen. They work and are not part of this request.

## Phase 4 — final check

`BUGFIX-PLAN.md` WP7, plus the Phase 2 acceptance list, plus one line of result for each Phase 3 item. Also test: sign in to a Google account, cold start with no network, and 30 minutes of mixed use watching memory in `adb shell dumpsys meminfo <package>` — two WebViews must not grow without bound.

## What a wrapper cannot do — say this plainly in the final report

- **Downloads / offline viewing.**
- **Cast to TV.** Needs Google's Cast SDK tied to the official app.
- **Push notifications** for subscriptions.
- **Ads stitched into the video stream** by YouTube's servers.
- **The app's queue**, native comment and description sheets, and its exact animations. The mobile site's versions are used as they are.
- **Scrolling smoothness** equal to a native list.
- **Google sign-in** can be refused inside a WebView at any time; the app already hides the WebView marker in its user agent, and that is all that can be done.

## Risks

- **Memory.** Two WebViews with YouTube loaded. `close()` must blank the player page. Test on a low-RAM phone.
- **YouTube changes its markup.** Phase 2 depends only on URL paths, `a[href]` clicks and `#player-container-id`. Everything marked VERIFY is a selector that can change; keep each in one named constant.
- **Shared state between two web clients.** `NavRules` keeps per-navigation flags (`chainTrusted`, `chainAuth`). If sign-in redirects start failing after Phase 2, give the player view its own `NavRules` instance.
- **Desktop and TV share `youtube.js`.** Every Phase 2 and 3 script change is behind `ROLE` / `IS_PHONE_SPLIT`. Run `npm test` after each phase.
