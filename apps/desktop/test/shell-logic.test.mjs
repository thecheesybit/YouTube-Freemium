// Tests for the shell logic added in the desktop polish pass: layout maths, per-view history, PiP rules,
// load-failure classification and the ad pruning shipped in the page script.
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const { CHROME_H, computeLayout, chromeHeight } = require('../src/core/layout');
const { readHistory, step, NavHistory } = require('../src/core/nav-history');
const { canEnterPip, pipBlocker, pipButtonVisible, pipButtonLeft } = require('../src/core/pip-rules');
const { classifyLoadFailure, describeFailure, LoadErrors } = require('../src/core/load-errors');

// ---------- layout / safe area ----------
test('layout: site area starts below the chrome strip so window buttons never cover the page', () => {
  const l = computeLayout({ width: 1280, height: 800 });
  assert.deepEqual(l.chrome, { x: 0, y: 0, width: 1280, height: CHROME_H });
  assert.deepEqual(l.site, { x: 0, y: CHROME_H, width: 1280, height: 800 - CHROME_H });
  assert.equal(l.chrome.y + l.chrome.height, l.site.y, 'no gap and no overlap between chrome and site');
  assert.deepEqual(l.panel, l.site);
  assert.equal(l.bar.y, CHROME_H);
});

test('layout: fullscreen gives the whole window to the page and hides the island', () => {
  const l = computeLayout({ width: 1920, height: 1080, fullscreen: true });
  assert.equal(chromeHeight({ fullscreen: true }), 0);
  assert.deepEqual(l.site, { x: 0, y: 0, width: 1920, height: 1080 });
  assert.equal(l.island, null);
});

test('layout: integers only (no blurry fractional bounds) and tiny windows never go negative', () => {
  const l = computeLayout({ width: 1279.6, height: 800.4 });
  for (const r of [l.chrome, l.site, l.bar, l.pill]) for (const v of Object.values(r)) assert.ok(Number.isInteger(v) && v >= 0);
  const tiny = computeLayout({ width: 300, height: 20 });
  assert.equal(tiny.site.height, 0);
  assert.ok(tiny.pill.width <= 300);
});

test('layout: island stays inside the site area, clamped away from the chrome and the bottom', () => {
  const collapsed = computeLayout({ width: 1000, height: 600, islandMode: 'collapsed' }).island;
  assert.equal(collapsed.x, 1000 - 28);
  assert.ok(collapsed.y >= CHROME_H + 8 && collapsed.y + collapsed.height <= 600 - 8);
  const dragged = computeLayout({ width: 1000, height: 600, islandMode: 'expanded', islandHeight: 220, dragTop: 9999 }).island;
  assert.ok(dragged.y + dragged.height <= 600 - 8);
  const top = computeLayout({ width: 1000, height: 600, islandMode: 'collapsed', dragTop: -50 }).island;
  assert.ok(top.y >= CHROME_H + 8);
  assert.equal(computeLayout({ width: 1000, height: 600, islandMode: 'hidden' }).island, null);
});

// ---------- history state ----------
const fakeWc = (back, fwd) => {
  const calls = [];
  return {
    calls,
    isDestroyed: () => false,
    navigationHistory: { canGoBack: () => back, canGoForward: () => fwd, goBack: () => calls.push('back'), goForward: () => calls.push('forward') }
  };
};

test('history: buttons enable only when there is somewhere to go', () => {
  assert.deepEqual(readHistory(fakeWc(false, false)), { canGoBack: false, canGoForward: false });
  assert.deepEqual(readHistory(fakeWc(true, false)), { canGoBack: true, canGoForward: false });
  assert.deepEqual(readHistory(null), { canGoBack: false, canGoForward: false });
});

test('history: step navigates only when possible and reports whether it did', () => {
  const wc = fakeWc(true, false);
  assert.equal(step(wc, 'back'), true);
  assert.equal(step(wc, 'forward'), false);
  assert.deepEqual(wc.calls, ['back']);
});

test('history: each view keeps its own state and changes are reported once', () => {
  const seen = [];
  const nav = new NavHistory((s) => seen.push(s));
  const youtube = fakeWc(true, false);
  const music = fakeWc(false, false);
  assert.equal(nav.refresh(youtube), true);
  assert.equal(nav.refresh(youtube), false, 'unchanged state is not re-reported');
  assert.equal(nav.refresh(music), true);
  assert.deepEqual(seen, [{ canGoBack: true, canGoForward: false }, { canGoBack: false, canGoForward: false }]);
  const broken = { isDestroyed: () => false, navigationHistory: { canGoBack: () => { throw new Error('gone'); }, canGoForward: () => false } };
  assert.deepEqual(readHistory(broken), { canGoBack: false, canGoForward: false });
});

// ---------- PiP rules ----------
const base = { enabled: true, inPip: false, sourceId: 'youtube', media: { playing: true, isVideo: true }, pathname: '/watch' };

test('pip: allowed only for a playing video on a YouTube watch page', () => {
  assert.equal(canEnterPip(base), true);
  assert.equal(pipBlocker({ ...base, sourceId: 'youtube-music' }), 'music');
  assert.equal(pipBlocker({ ...base, pathname: '/results' }), 'not-watch-page');
  assert.equal(pipBlocker({ ...base, pathname: '/' }), 'not-watch-page');
  assert.equal(pipBlocker({ ...base, media: { playing: false, isVideo: true } }), 'not-playing');
  assert.equal(pipBlocker({ ...base, media: { playing: true, isVideo: false } }), 'no-video');
  assert.equal(pipBlocker({ ...base, enabled: false }), 'disabled');
  assert.equal(pipBlocker({ ...base, inPip: true }), 'already-in-pip');
});

test('pip button: watch pages only, hidden on Music, during ads and in PiP; sits 8px right of the speed pill', () => {
  assert.equal(pipButtonVisible({ pathname: '/watch', host: 'www.youtube.com' }), true);
  assert.equal(pipButtonVisible({ pathname: '/', host: 'www.youtube.com' }), false);
  assert.equal(pipButtonVisible({ pathname: '/watch', host: 'music.youtube.com' }), false);
  assert.equal(pipButtonVisible({ pathname: '/watch', host: 'www.youtube.com', adActive: true }), false);
  assert.equal(pipButtonVisible({ pathname: '/watch', host: 'www.youtube.com', inPip: true }), false);
  assert.equal(pipButtonLeft(42), 62);
  assert.equal(pipButtonLeft(60), 80, 'follows the pill width');
});

// ---------- load failures ----------
test('load errors: only real main-frame failures get the branded screen', () => {
  assert.equal(classifyLoadFailure({ errorCode: -3, isMainFrame: true }), 'ignore'); // aborted
  assert.equal(classifyLoadFailure({ errorCode: -106, isMainFrame: false }), 'ignore'); // subframe
  assert.equal(classifyLoadFailure({ errorCode: -106, isMainFrame: true }), 'offline');
  assert.equal(classifyLoadFailure({ errorCode: -105, isMainFrame: true }), 'offline');
  assert.equal(classifyLoadFailure({ errorCode: -20, isMainFrame: true }), 'blocked');
  assert.equal(classifyLoadFailure({ errorCode: -118, isMainFrame: true }), 'failed');
});

test('load errors: copy never contains a URL and state is tracked per source', () => {
  for (const kind of ['offline', 'blocked', 'failed']) {
    const d = describeFailure(kind, 'YouTube');
    assert.ok(!/https?:|www\./i.test(d.title + d.body));
  }
  const changes = [];
  const errors = new LoadErrors((id, kind) => changes.push([id, kind]));
  assert.equal(errors.fail('youtube', 'ignore'), false);
  assert.equal(errors.fail('youtube', 'offline'), true);
  assert.equal(errors.fail('youtube', 'offline'), false);
  assert.equal(errors.get('youtube-music'), null);
  assert.equal(errors.clear('youtube'), true);
  assert.equal(errors.clear('youtube'), false);
  assert.deepEqual(changes, [['youtube', 'offline'], ['youtube', null]]);
});

// ---------- ad pruning in the page script (real code, extracted) ----------
const src = fs.readFileSync(path.join(here, '..', '..', '..', 'packages', 'inject', 'youtube.js'), 'utf8');
const start = src.indexOf('const AD_KEYS');
const end = src.search(/try \{\r?\n\s+const origFetch/);
assert.ok(start > 0 && end > start, 'ad pruning block found in youtube.js');
const { pruneAds, urlRe } = new Function(`${src.slice(start, end)}; return { pruneAds, urlRe: AD_URL_RE };`)();

test('ads: feed, search, masthead and player ad data are removed; real videos are kept', () => {
  const data = {
    adPlacements: [1],
    playerAds: [1],
    adSlots: [1],
    contents: [
      { richItemRenderer: { content: { adSlotRenderer: {} } } },
      { richItemRenderer: { content: { videoRenderer: { videoId: 'keep1' } } } },
      { adSlotRenderer: {} },
      { itemSectionRenderer: { contents: [{ promotedSparklesWebRenderer: {} }, { videoRenderer: { videoId: 'keep2' } }, { searchPyvRenderer: {} }] } }
    ],
    masthead: { videoMastheadAdV3Renderer: {} },
    playerResponse: { adPlacements: [1], videoDetails: { videoId: 'v' } }
  };
  assert.equal(pruneAds(data), true);
  assert.deepEqual(JSON.parse(JSON.stringify(data)), {
    contents: [
      { richItemRenderer: { content: { videoRenderer: { videoId: 'keep1' } } } },
      { itemSectionRenderer: { contents: [{ videoRenderer: { videoId: 'keep2' } }] } }
    ],
    masthead: {},
    playerResponse: { videoDetails: { videoId: 'v' } }
  });
  assert.equal(pruneAds(data), false, 'second pass changes nothing');
  assert.equal(pruneAds(null), false);
});

test('ads: the response hook covers player, next, browse, search and reel endpoints only', () => {
  for (const ok of ['/youtubei/v1/player?x=1', '/youtubei/v1/next', '/youtubei/v1/browse', '/youtubei/v1/search', '/youtubei/v1/reel/reel_item_watch']) {
    assert.ok(urlRe.test(ok), ok);
  }
  for (const no of ['/youtubei/v1/account/account_menu', '/api/timedtext', '/youtubei/v1/like/like']) assert.ok(!urlRe.test(no), no);
});
