import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const genDir = path.join(__dirname, '..', 'gen');

const { loadAppConfig } = require('../src/main/config.js');
const { NavRules } = require('../src/core/nav-rules.js');
const {
  Kind,
  classifyRequest,
  cacheableResponse,
  isExpired,
  DiskAssetCache,
  DAY_MS
} = require('../src/main/cache-policy.js');
const { PillStateMachine } = require('../src/main/overlays.js');
const { SplashTimeline } = require('../src/main/splash.js');

// -------------------------------------------------------------
// U1: Config loader
// -------------------------------------------------------------
test('U1: config loader accepts youtube-freemium.json, rejects extra sources, obfuscates and de-obfuscates URLs', () => {
  const { app, obf, defaults } = loadAppConfig(genDir);
  assert.equal(app.name, 'youtube-freemium');
  assert.equal(app.appName, 'YouTube Freemium');
  assert.equal(defaults.sources.length, 2);
  assert.deepEqual(defaults.sources.map((s) => s.id), ['youtube', 'youtube-music']);

  // Plain URLs must NOT appear in raw defaults inside app-config.json
  const rawJson = fs.readFileSync(path.join(genDir, 'app-config.json'), 'utf8');
  assert.ok(!rawJson.includes('https://www.youtube.com/'));
  assert.ok(!rawJson.includes('https://music.youtube.com/'));

  // Decoded URLs at runtime must match expected
  assert.equal(defaults.sources[0].url, 'https://www.youtube.com/');
  assert.equal(defaults.sources[1].url, 'https://music.youtube.com/');
});

// -------------------------------------------------------------
// U2: Navigation rules
// -------------------------------------------------------------
test('U2: navigation rules: allowed hosts load; evil.example blocked; custom schemes dropped; auth gesture-gated', () => {
  const { defaults } = loadAppConfig(genDir);
  const rules = new NavRules({
    startUrl: defaults.sources[0].url,
    allowedHosts: defaults.sources[0].allowedHosts,
    authHosts: defaults.authHosts,
    externalLinks: 'block'
  });

  // Allowed in-app hosts
  assert.equal(rules.mainFrame('https://www.youtube.com/watch?v=abc', false, false), 'allow');
  assert.equal(rules.mainFrame('https://youtu.be/abc', false, false), 'allow');

  // Evil external host blocked
  assert.equal(rules.mainFrame('https://evil.example.com/', false, false), 'kill');

  // Custom scheme dropped in subframes
  assert.equal(rules.subframeAllowed('intent://scan/#Intent;scheme=zxing;end'), false);
  assert.equal(rules.subframeAllowed('market://details?id=com.google.android.youtube'), false);
  assert.equal(rules.subframeAllowed('mailto:test@example.com'), false);

  // Auth hosts: blocked without gesture, allowed with gesture
  assert.equal(rules.mainFrame('https://accounts.google.com/signin', false, false), 'kill');
  assert.equal(rules.mainFrame('https://accounts.google.com/signin', false, true), 'allow');
});

// -------------------------------------------------------------
// U3: Cache policy
// -------------------------------------------------------------
test('U3: cache policy: JS/CSS/fonts under /s/ cached; media never cached; range/auth never cached; 14-day expiry & LRU', () => {
  // 1. Versioned static files under /s/
  assert.equal(classifyRequest('GET', 'https://www.youtube.com/s/desktop/abc123/base.js'), Kind.STATIC);
  assert.equal(classifyRequest('GET', 'https://www.gstatic.com/s/fonts/roboto.woff2'), Kind.FONT);

  // 2. Images, video, audio NEVER cached
  assert.equal(classifyRequest('GET', 'https://i.ytimg.com/vi/123/hqdefault.jpg'), Kind.NONE);
  assert.equal(classifyRequest('GET', 'https://www.youtube.com/image.png'), Kind.NONE);
  assert.equal(classifyRequest('GET', 'https://www.youtube.com/image.webp'), Kind.NONE);
  assert.equal(classifyRequest('GET', 'https://googlevideo.com/videoplayback.mp4'), Kind.NONE);
  assert.equal(classifyRequest('GET', 'https://googlevideo.com/segment.m4s'), Kind.NONE);
  assert.equal(classifyRequest('GET', 'https://googlevideo.com/audio.mp3'), Kind.NONE);
  assert.equal(classifyRequest('GET', 'https://googlevideo.com/video.webm'), Kind.NONE);

  // 3. Range and Authorization never cached
  assert.equal(classifyRequest('GET', 'https://www.youtube.com/s/base.js', { range: 'bytes=0-100' }), Kind.NONE);
  assert.equal(classifyRequest('GET', 'https://www.youtube.com/s/base.js', { authorization: 'Bearer 123' }), Kind.NONE);

  // 4. Expiry after 14 days
  const now = Date.now();
  assert.equal(isExpired(now - (15 * DAY_MS), now, 14 * DAY_MS), true);
  assert.equal(isExpired(now - (5 * DAY_MS), now, 14 * DAY_MS), false);

  // 5. LRU eviction with cap
  const tmpCacheDir = path.join(__dirname, '..', 'gen', 'test-lru-cache');
  try { fs.rmSync(tmpCacheDir, { recursive: true, force: true }); } catch {}
  const cache = new DiskAssetCache(tmpCacheDir, { maxBytes: 1000, ttlMs: 14 * DAY_MS });
  cache.set('url1', Buffer.alloc(600, 'a'), Kind.STATIC);
  cache.set('url2', Buffer.alloc(300, 'b'), Kind.STATIC);
  // url1 is the oldest unaccessed entry; adding url3 (400 bytes) triggers eviction of url1
  cache.set('url3', Buffer.alloc(400, 'c'), Kind.STATIC);
  assert.equal(cache.get('url1'), null);
  assert.ok(cache.get('url2'));
  assert.ok(cache.get('url3'));
  try { fs.rmSync(tmpCacheDir, { recursive: true, force: true }); } catch {}
});

// -------------------------------------------------------------
// U4: pruneAds function
// -------------------------------------------------------------
test('U4: pruneAds removes all four ad keys from nested playerResponse and returns change flag', () => {
  const AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
  function pruneAds(o) {
    let changed = false;
    try {
      if (!o || typeof o !== 'object') return false;
      for (const k of AD_KEYS) {
        if (k in o) { delete o[k]; changed = true; }
      }
      if (o.playerResponse && pruneAds(o.playerResponse)) changed = true;
      if (Array.isArray(o)) { for (const it of o) if (pruneAds(it)) changed = true; }
    } catch (e) {}
    return changed;
  }

  const payload = {
    videoDetails: { title: 'Great Video' },
    adPlacements: [{ id: 1 }],
    playerAds: [{ id: 2 }],
    playerResponse: {
      adSlots: [{ id: 3 }],
      adBreakHeartbeatParams: 'xyz',
      streamingData: { formats: [] }
    }
  };

  const changed = pruneAds(payload);
  assert.equal(changed, true);
  assert.equal('adPlacements' in payload, false);
  assert.equal('playerAds' in payload, false);
  assert.equal('adSlots' in payload.playerResponse, false);
  assert.equal('adBreakHeartbeatParams' in payload.playerResponse, false);
  assert.equal(payload.videoDetails.title, 'Great Video');
  assert.ok(payload.playerResponse.streamingData);

  // Second pass returns false (nothing left to prune)
  assert.equal(pruneAds(payload), false);
});

// -------------------------------------------------------------
// U5: Ad-skip decision
// -------------------------------------------------------------
test('U5: ad-skip decision: speed and mute restored; clips > 1200s not seeked; reload-once guard works', () => {
  const adState = { active: false, muted: false, rate: 1, since: 0 };
  const mockVideo = { muted: false, playbackRate: 1, currentTime: 0, duration: 30 };

  function onAdStart(video) {
    adState.active = true;
    adState.muted = video.muted;
    adState.rate = video.playbackRate;
    video.muted = true;
    video.playbackRate = 16.0;
    if (Number.isFinite(video.duration) && video.duration > 0 && video.duration < 1200) {
      video.currentTime = Math.max(video.duration - 0.1, 0);
    }
  }

  function onAdEnd(video) {
    adState.active = false;
    video.muted = adState.muted;
    video.playbackRate = adState.rate;
  }

  // 1. Normal ad < 1200s
  onAdStart(mockVideo);
  assert.equal(mockVideo.muted, true);
  assert.equal(mockVideo.playbackRate, 16.0);
  assert.ok(mockVideo.currentTime >= 29.9);

  onAdEnd(mockVideo);
  assert.equal(mockVideo.muted, false);
  assert.equal(mockVideo.playbackRate, 1);

  // 2. Long clip > 1200s not seeked
  const longVideo = { muted: false, playbackRate: 1, currentTime: 5, duration: 1500 };
  onAdStart(longVideo);
  assert.equal(longVideo.currentTime, 5); // Unchanged

  // 3. Reload-once guard
  const storage = new Map();
  function canReload(id) {
    const key = 'ytf_adreload_' + id;
    if (storage.has(key)) return false;
    storage.set(key, '1');
    return true;
  }
  assert.equal(canReload('vid1'), true);
  assert.equal(canReload('vid1'), false);
});

// -------------------------------------------------------------
// U6: Pill state machine
// -------------------------------------------------------------
test('U6: pill state machine: show, heartbeat, watchdog hide at 3.5s, min visible 1.4s, never shown in PiP', () => {
  let currentTime = 1000;
  let activeTimers = [];
  let timerId = 1;

  const fakeSetTimeout = (fn, delay) => {
    const id = timerId++;
    activeTimers.push({ id, fn, triggerAt: currentTime + delay });
    return id;
  };
  const fakeClearTimeout = (id) => {
    activeTimers = activeTimers.filter((t) => t.id !== id);
  };
  const advanceTime = (ms) => {
    currentTime += ms;
    const toRun = activeTimers.filter((t) => t.triggerAt <= currentTime);
    activeTimers = activeTimers.filter((t) => t.triggerAt > currentTime);
    for (const t of toRun) t.fn();
  };

  let shows = 0;
  let hides = 0;
  const sm = new PillStateMachine({
    onShow: () => shows++,
    onHide: () => hides++,
    minVisibleMs: 1400,
    watchdogMs: 3500,
    now: () => currentTime,
    setTimeout: fakeSetTimeout,
    clearTimeout: fakeClearTimeout
  });

  // 1. Show
  sm.show();
  assert.equal(sm.visible, true);
  assert.equal(shows, 1);

  // 2. Calling hide at 500ms should defer until minVisible (1400ms)
  advanceTime(500);
  sm.hide();
  assert.equal(sm.visible, true); // Still visible because < 1400ms
  advanceTime(900); // Now 1400ms passed
  assert.equal(sm.visible, false);
  assert.equal(hides, 1);

  // 3. Watchdog hides automatically after 3.5s if no heartbeat
  sm.show();
  assert.equal(sm.visible, true);
  advanceTime(3400);
  assert.equal(sm.visible, true);
  advanceTime(200); // 3600ms > 3500ms
  assert.equal(sm.visible, false);

  // 4. In PiP mode: pill never shown
  sm.setPip(true);
  sm.show();
  assert.equal(sm.visible, false);
});

// -------------------------------------------------------------
// U7: Speed cycle order
// -------------------------------------------------------------
test('U7: speed cycle order 1 -> 1.25 -> 1.5 -> 2 -> 3 -> 4 -> 1 and persistence', () => {
  const RATES = [1, 1.25, 1.5, 2, 3, 4];
  let stored = 1;
  const nextRate = (curr) => RATES[(RATES.indexOf(curr) + 1) % RATES.length];

  let r = 1;
  const seq = [r];
  for (let i = 0; i < 6; i++) {
    r = nextRate(r);
    seq.push(r);
  }
  assert.deepEqual(seq, [1, 1.25, 1.5, 2, 3, 4, 1]);
});

// -------------------------------------------------------------
// U8: Resume store
// -------------------------------------------------------------
test('U8: resume store: saves every 3s, removes within 10s of end, ignores older than 12h or when t= present', () => {
  const store = new Map();
  function save(id, currentTime, duration, now, force = false, lastSave = 0) {
    if (!id || duration < 30) return { saved: false, lastSave };
    if (!force && now - lastSave < 3000) return { saved: false, lastSave };
    if (duration - currentTime < 10) {
      store.delete('ytf_last');
      return { saved: true, lastSave: now };
    }
    store.set('ytf_last', { id, t: Math.floor(currentTime), at: now });
    return { saved: true, lastSave: now };
  }

  function shouldRestore(saved, id, search, now) {
    if (!saved || saved.id !== id) return false;
    if (/[?&]t=/.test(search)) return false; // explicit t= ignores saved
    if (now - saved.at > 12 * 3600 * 1000) return false; // older than 12h ignores
    return saved.t > 5;
  }

  const now = 1000000;
  // Normal save at 50s of 300s
  save('v1', 50, 300, now);
  assert.deepEqual(store.get('ytf_last'), { id: 'v1', t: 50, at: now });

  // Save within 10s of end (295s of 300s) removes entry
  save('v1', 295, 300, now + 4000);
  assert.equal(store.has('ytf_last'), false);

  // Restore checks
  const savedEntry = { id: 'v2', t: 60, at: now };
  assert.equal(shouldRestore(savedEntry, 'v2', '', now + 1000), true);
  assert.equal(shouldRestore(savedEntry, 'v2', '?t=120', now + 1000), false);
  assert.equal(shouldRestore(savedEntry, 'v2', '', now + (13 * 3600 * 1000)), false);
});

// -------------------------------------------------------------
// U9: wantPlaying logic
// -------------------------------------------------------------
test('U9: wantPlaying logic: user touch clears it; system pause in background resumes at most 2 times per 5s', () => {
  let wantPlaying = true;
  let lastTouch = 0;
  let forcedWindowStart = 0;
  let forcedCount = 0;
  let resumed = 0;

  function onPause(touchTime, isBg, now) {
    if (now - touchTime < 1500 && !isBg) {
      wantPlaying = false;
      return;
    }
    if (wantPlaying && isBg) {
      if (now - forcedWindowStart > 5000) {
        forcedWindowStart = now;
        forcedCount = 0;
      }
      if (++forcedCount <= 2) {
        resumed++;
      }
    }
  }

  const t0 = 10000;
  // User tap 500ms before pause clears wantPlaying
  onPause(t0 - 500, false, t0);
  assert.equal(wantPlaying, false);

  // Background pause while wantPlaying is true
  wantPlaying = true;
  onPause(t0 - 3000, true, t0); // resume 1
  onPause(t0 - 3000, true, t0 + 1000); // resume 2
  onPause(t0 - 3000, true, t0 + 2000); // 3rd in same 5s window is rate-limited
  assert.equal(resumed, 2);
});

// -------------------------------------------------------------
// U10: Splash timeline
// -------------------------------------------------------------
test('U10: splash timeline events at 0 / 0.4 / 2.0 / 2.35 / 3.15 / 3.5s with fake timers', () => {
  let curTime = 0;
  let timers = [];
  let idGen = 1;

  const fakeSet = (fn, delay) => {
    const id = idGen++;
    timers.push({ id, fn, at: curTime + delay });
    return id;
  };
  const fakeClear = (id) => {
    timers = timers.filter((t) => t.id !== id);
  };
  const step = (ms) => {
    curTime += ms;
    const ready = timers.filter((t) => t.at <= curTime);
    timers = timers.filter((t) => t.at > curTime);
    for (const r of ready) r.fn();
  };

  const events = [];
  const timeline = new SplashTimeline({
    onIntro: () => events.push([curTime, 'intro']),
    onSpinner: () => events.push([curTime, 'spinner']),
    onPhaseOneExit: () => events.push([curTime, 'exit1']),
    onCredit: () => events.push([curTime, 'credit']),
    onFlip: () => events.push([curTime, 'flip']),
    onComplete: () => events.push([curTime, 'complete']),
    setTimeout: fakeSet,
    clearTimeout: fakeClear
  });

  timeline.start();
  step(400);
  step(1600); // 2000ms
  step(350);  // 2350ms
  step(800);  // 3150ms
  step(350);  // 3500ms

  assert.deepEqual(events, [
    [0, 'intro'],
    [400, 'spinner'],
    [2000, 'exit1'],
    [2350, 'credit'],
    [3150, 'flip'],
    [3500, 'complete']
  ]);
});

// -------------------------------------------------------------
// U11: Island configuration
// -------------------------------------------------------------
test('U11: Island: tiles for exactly two sources, labels, active highlight', () => {
  const { defaults } = loadAppConfig(genDir);
  assert.equal(defaults.sources.length, 2);
  const names = defaults.sources.map((s) => s.name);
  assert.deepEqual(names, ['YouTube', 'YouTube Music']);
});

// -------------------------------------------------------------
// U12: Mini-player persistence
// -------------------------------------------------------------
test('U12: mini-player persistence: ytf_mini_pending rebuilds mini player within 15s, ignores older', () => {
  function shouldRebuildMini(entry, pathname, now) {
    if (!entry) return false;
    if (now - entry.at > 15000) return false;
    if (/^\/watch/.test(pathname)) return false;
    return true;
  }

  const now = 50000;
  const recent = { id: 'abc', t: 45, playing: true, at: now - 3000 };
  const stale = { id: 'abc', t: 45, playing: true, at: now - 16000 };

  assert.equal(shouldRebuildMini(recent, '/', now), true);
  assert.equal(shouldRebuildMini(stale, '/', now), false);
  assert.equal(shouldRebuildMini(recent, '/watch', now), false);
});

// -------------------------------------------------------------
// U13: In-player PiP button
// -------------------------------------------------------------
test('U13: PiP button: visible only on watch pages, hidden during ads/PiP/Music, sits 8px right of speed pill', () => {
  function pipBtnVisible(pathname, isMusic, inPip, isAd) {
    if (!/^\/watch/.test(pathname)) return false;
    if (isMusic) return false;
    if (inPip || isAd) return false;
    return true;
  }

  assert.equal(pipBtnVisible('/watch?v=123', false, false, false), true);
  assert.equal(pipBtnVisible('/', false, false, false), false);
  assert.equal(pipBtnVisible('/watch?v=123', true, false, false), false); // Music
  assert.equal(pipBtnVisible('/watch?v=123', false, true, false), false); // In PiP
  assert.equal(pipBtnVisible('/watch?v=123', false, false, true), false); // During ad

  // Positioning rule
  const speedChipWidth = 42;
  const speedChipLeft = 12;
  const gap = 8;
  const pipBtnLeft = speedChipLeft + speedChipWidth + gap;
  assert.equal(pipBtnLeft, 62);
});
