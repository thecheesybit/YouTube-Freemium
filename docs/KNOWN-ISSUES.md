# YouTube Freemium — status and known issues (snapshot 2026-11-13)

Sources: `DESKTOP.md`, `DESKTOP-TEST-REPORT.md` (section 0 supersedes the older statuses). Never write private config values or keys into files, logs or commits.
Legend: **OPEN** = known gap. **UNVERIFIED** = code exists, never confirmed on real hardware / with real input.

## Build state
- `npm run build -- --app youtube-freemium --target all` builds the phone APK, the TV APK and the Windows installer + portable exe (checked 2026-11-13).
- Desktop: `npm run desktop:test` 43 pass (as of the polish pass).
- If a *local* Android release build fails in R8 with missing `com.google.firebase` classes, the Android `build/` folder holds stale classes from another project: run `gradlew clean` in `apps/mobile/android` and build again. A fresh clone is not affected.

## Android phone
| # | Item | Status |
|---|---|---|
| P1 | Ad skipping depends on YouTube's markup and responses. When YouTube changes them, ads can return. Selectors live in `packages/inject/youtube-selectors.json` and the logic in `packages/inject/youtube.js` (response pruning + skip button + cosmetic hide). | OPEN (recurring maintenance) |
| P2 | Not re-tested on a device since the shared platform code was last changed. Check: screen-off audio with lock-screen controls, Picture-in-Picture, in-app mini player on Back, speed pill 1x to 4x, YouTube and YouTube Music switcher, resume position, Google sign-in without a "browser not secure" warning. | UNVERIFIED |
| P3 | Brightness control (2-200 %) was added to Movy in Nov 2026 and is **not ported** to this repo. | OPEN (feature gap) |

## Android TV
| # | Item | Status |
|---|---|---|
| V1 | There is **no TV profile** (`tvProfiles`) for YouTube or YouTube Music and no TV test notes. The TV build uses the generic Round-4 D-pad layer only. Nothing is known to work or fail on a real TV: needs a full pass on a TV (focus ring, OK, BACK chain, player controls, sign-in). | UNVERIFIED |
| V2 | The Movy Round 5 TV shell (native drawer, start screen, search keyboard proxy, mic search, player settings engine, brightness) is not ported here. | OPEN (feature gap) |
| V3 | YouTube's own TV-hostile pieces to check: the player controls needing a mouse hover, the sign-in flow with the remote, the YouTube Music UI. | UNVERIFIED |

## Desktop (Electron, Windows)
Not code-signed, so SmartScreen shows "unknown publisher".
| # | Item | Status |
|---|---|---|
| D1 | A real pre-roll ad could not be forced during testing; skip/pre-roll behaviour is covered by response pruning and unit tests only. | UNVERIFIED |
| D2 | 30-minute soak with minimize/restore (memory growth under 100 MB, CPU under 8 % while minimized). | UNVERIFIED |
| D3 | Display scaling above 100 %; running the NSIS installer end to end; auto Picture-in-Picture on a real window minimize (handler wired, not exercised). | UNVERIFIED |
| D4 | Google sign-in password step (needs a test account). | UNVERIFIED |
| D5 | Alt+Left on a watch page opens the in-app mini player first (by design); confirm that is the wanted behaviour. | by design |
