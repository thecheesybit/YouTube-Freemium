import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const { createObfuscator } = require('../src/core/obfuscator.js');
const live = require('../src/core/live-config.js');
const { NavRules } = require('../src/core/nav-rules.js');
const home = require('../src/core/home-page.js');
const health = require('../src/core/health-rules.js');
const { Prefs } = require('../src/core/prefs.js');
const { SourceRepository } = require('../src/core/repository.js');

const KEY = Buffer.from('0123456789abcdef').toString('base64');
const ob = createObfuscator(KEY);
const src = (id, host, extra = {}) => ({ id, name: id.toUpperCase(), urlEnc: ob.encode(`https://${host}/`), allowedHosts: [host, `*.${host}`], ...extra });
const DEFAULTS = {
  contentVersion: 1,
  sources: [src('a', 'source-1.example', { accent: '#3B82F6' }), src('b', 'source-2.example'), src('c', 'source-3.example', { enabled: false })],
  recommendedSource: 'b',
  splash: { tagline: 'Tag', credit: 'Me', minMs: 500 },
  dohHosts: ['api.source-1.example'],
};
const cfg = () => live.merge(null, DEFAULTS, ob.decode);

test('obfuscation round-trips and hides the plain URL', () => {
  const enc = ob.encode('https://source-1.example/path');
  assert.equal(ob.decode(enc), 'https://source-1.example/path');
  assert.ok(!enc.includes('source-1'));
  assert.throws(() => createObfuscator(''), /empty/);
});

test('live config: defaults parse, palette fills missing accents, order kept', () => {
  const c = cfg();
  assert.deepEqual(c.sources.map((s) => s.id), ['a', 'b', 'c']);
  assert.equal(c.sources[0].accent, '#3B82F6');
  assert.equal(c.sources[1].accent, live.PALETTE[1]);
  assert.equal(c.sources[0].url, 'https://source-1.example/');
  assert.equal(live.recommended(c).id, 'b');
  assert.deepEqual(live.enabledSources(c).map((s) => s.id), ['a', 'b']);
  assert.equal(c.splashMinMs, 500);
});

test('live config: remote merge overrides per field and keeps the rest', () => {
  const next = live.merge(cfg(), { contentVersion: 7, recommendedSource: 'a', chooser: { cta: 'Go' }, island: { visibility: 'always' } }, ob.decode);
  assert.equal(next.contentVersion, 7);
  assert.equal(next.recommendedSource, 'a');
  assert.equal(next.chooser.cta, 'Go');
  assert.equal(next.chooser.title, 'Choose your source');
  assert.equal(next.islandVisibility, 'always');
  assert.equal(next.sources.length, 3);
  assert.ok(Object.isFrozen(next));
});

test('live config: remote sources may carry plain urls and replace the list', () => {
  const list = [{ id: 'z', name: 'Z', url: 'https://source-9.example/' }];
  assert.throws(() => live.merge(cfg(), { sources: list }, ob.decode), /recommendedSource/, 'stale recommendation is rejected');
  const next = live.merge(cfg(), { sources: list, recommendedSource: 'z' }, ob.decode);
  assert.equal(next.sources.length, 1);
  assert.deepEqual(next.sources[0].allowedHosts, ['source-9.example', '*.source-9.example']);
  assert.equal(next.recommendedSource, 'z');
});

test('live config: invalid input throws and never half-applies', () => {
  const base = cfg();
  assert.throws(() => live.merge(base, '{nope', ob.decode), /invalid JSON/);
  assert.throws(() => live.merge(base, { sources: [] }, ob.decode), /sources empty/);
  assert.throws(() => live.merge(base, { recommendedSource: 'zzz' }, ob.decode), /recommendedSource/);
  assert.throws(() => live.merge(base, { sources: [{ id: 'A!', name: 'x', url: 'https://x.example/' }] }, ob.decode), /source id/);
  assert.throws(() => live.merge(base, { sources: [{ id: 'a', name: 'x', url: 'ftp://x.example/' }] }, ob.decode), /source url/);
  assert.throws(() => live.merge(base, { island: { visibility: 'sometimes' } }, ob.decode), /visibility/);
  assert.throws(() => live.merge(base, { dohHosts: ['bad host'] }, ob.decode), /host pattern/);
  assert.throws(() => live.merge(base, { sources: [{ id: 'a', name: 'x', url: 'https://x.example/', enabled: false }], recommendedSource: 'a' }, ob.decode), /no enabled/);
  assert.equal(base.sources.length, 3);
});

const rules = () => new NavRules({ startUrl: 'https://source-1.example/', allowedHosts: ['source-1.example', '*.source-1.example'], externalLinks: 'block' });

test('nav: allowed hosts load, everything else is dropped', () => {
  const r = rules();
  assert.equal(r.mainFrame('https://www.source-1.example/movie/1', false, false, ''), 'allow');
  assert.equal(r.mainFrame('https://ads.example.net/x', false, true, ''), 'kill');
  assert.equal(r.mainFrame('intent://x#Intent;end', false, true, ''), 'kill');
  assert.equal(r.mainFrame('mailto:a@b.c', true, true, ''), 'kill');
  assert.equal(r.mainFrame('file:///c:/x', false, true, ''), 'kill');
  assert.equal(r.mainFrame('about:blank', false, false, ''), 'allow');
  assert.equal(r.mainFrame('about:srcdoc', false, false, ''), 'kill');
});

test('nav: a redirect out of the start page is learned as a mirror, one from a deep page is not', () => {
  const r = rules();
  assert.equal(r.mainFrame('https://source-1.example/', false, false, ''), 'allow');
  assert.equal(r.mainFrame('https://mirror.example.net/', true, false, 'https://source-1.example/'), 'allow');
  assert.equal(r.isAllowedHost('mirror.example.net'), true);
  const d = rules();
  assert.equal(d.mainFrame('https://source-1.example/watch/1', false, true, ''), 'allow');
  assert.equal(d.mainFrame('https://evil.example.net/', true, false, 'https://source-1.example/watch/1'), 'kill');
  assert.equal(d.isAllowedHost('evil.example.net'), false);
});

test('nav: beginLoad re-bases trust for programmatic loads', () => {
  const r = rules();
  r.mainFrame('https://source-1.example/watch/1', false, true, '');
  r.beginLoad('https://source-1.example/');
  assert.equal(r.mainFrame('https://moved.example.net/', true, false, ''), 'allow');
});

test('nav: sign-in hosts only with a user gesture or on an auth page', () => {
  const r = rules();
  assert.equal(r.mainFrame('https://accounts.google.com/o/oauth2/auth', false, false, 'https://source-1.example/'), 'kill');
  assert.equal(r.mainFrame('https://accounts.google.com/o/oauth2/auth', false, true, 'https://source-1.example/'), 'allow');
  assert.equal(r.mainFrame('https://accounts.google.com/signin', true, false, ''), 'allow', 'redirect inside an auth chain');
  assert.equal(r.mainFrame('https://www.facebook.com/somepage', false, true, ''), 'kill', 'path prefix is enforced');
  assert.equal(r.mainFrame('https://www.facebook.com/login/x', false, true, ''), 'allow');
});

test('nav: popups only open allowed hosts, with a gesture, never when the engine blocks them', () => {
  const r = rules();
  assert.equal(r.popupOpensInMain('https://source-1.example/x', true, false), true);
  assert.equal(r.popupOpensInMain('https://source-1.example/x', false, false), false);
  assert.equal(r.popupOpensInMain('https://source-1.example/x', true, true), false);
  assert.equal(r.popupOpensInMain('https://ads.example.net/', true, false), false);
});

test('nav: subframes keep web schemes only, external schemes are dropped; in-app mode allows other hosts', () => {
  const r = rules();
  assert.equal(r.subframeAllowed('https://player.example.net/e/1'), true);
  assert.equal(r.subframeAllowed('about:srcdoc'), true);
  assert.equal(r.subframeAllowed('intent://x'), false);
  const any = new NavRules({ startUrl: 'https://source-1.example/', allowedHosts: [], externalLinks: 'in-app' });
  assert.equal(any.mainFrame('https://other.example.net/', false, false, ''), 'allow');
});

test('nav: live update swaps hosts', () => {
  const r = rules();
  r.update({ allowedHosts: ['source-2.example'], startHosts: ['source-2.example'], authHosts: ['sso.example.org'] });
  assert.equal(r.isAllowedHost('source-1.example'), false);
  assert.equal(r.isAllowedHost('source-2.example'), true);
  assert.equal(r.mainFrame('https://sso.example.org/', false, true, ''), 'allow');
});

test('home page rule: host + start path, ignoring query, hash and trailing slash', () => {
  const s = { id: 'a', url: 'https://source-1.example/', allowedHosts: ['source-1.example', '*.source-1.example'] };
  assert.equal(home.isHome(s, 'https://source-1.example/'), true);
  assert.equal(home.isHome(s, 'https://source-1.example'), true);
  assert.equal(home.isHome(s, 'https://www.source-1.example/?q=1#x'), true);
  assert.equal(home.isHome(s, 'https://source-1.example/movie/12'), false);
  assert.equal(home.isHome(s, 'https://other.example/'), false);
  assert.equal(home.isHome(s, 'about:blank'), false);
  const deep = { id: 'd', url: 'https://source-2.example/en/', allowedHosts: ['source-2.example'] };
  assert.equal(home.isHome(deep, 'https://source-2.example/en'), true);
  assert.equal(home.isHome(deep, 'https://source-2.example/'), true);
  assert.equal(home.isHome(deep, 'https://source-2.example/en/watch'), false);
  assert.equal(home.key('https://Source-2.example/en/?a=1'), 'source-2.example/en');
  assert.equal(home.isHomeOfAny([s, deep], 'https://source-2.example/en/'), true);
});

test('health: classification and failover suggestion', () => {
  assert.equal(health.classify(200, 400), 'up');
  assert.equal(health.classify(403, 400), 'up');
  assert.equal(health.classify(200, 3000), 'slow');
  assert.equal(health.classify(200, 9000), 'down');
  assert.equal(health.classify(503, 100), 'down');
  assert.equal(health.classify(0, 100), 'down');
  assert.equal(health.headNeedsFallback(405), true);
  assert.equal(health.headNeedsFallback(200), false);
  const list = [{ id: 'a' }, { id: 'b' }, { id: 'c' }];
  const st = { a: 'down', b: 'slow', c: 'up' };
  assert.equal(health.suggest(list, (id) => st[id], 'a').id, 'c');
  assert.equal(health.suggest(list, (id) => ({ a: 'up', b: 'slow', c: 'down' })[id], 'a').id, 'b');
  assert.equal(health.suggest(list, () => 'down', 'a'), null);
});

test('prefs persist atomically and survive reload', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'movy-prefs-'));
  const p = new Prefs(dir);
  assert.equal(p.get('askEveryLaunch'), false);
  p.set('defaultSourceId', 'b');
  p.set('islandY', 0.7);
  p.flush();
  const q = new Prefs(dir);
  assert.equal(q.get('defaultSourceId'), 'b');
  assert.equal(q.get('islandY'), 0.7);
  const id = q.instanceId();
  assert.match(id, /^[0-9a-f]{22}$/);
  assert.equal(q.instanceId(), id);
  fs.writeFileSync(path.join(dir, 'prefs.json'), '{broken');
  assert.equal(new Prefs(dir).get('islandY'), 0.4, 'corrupt file falls back to defaults');
  fs.rmSync(dir, { recursive: true, force: true });
});

test('repository: launch source, chooser preselection, switching and last urls', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'movy-repo-'));
  const prefs = new Prefs(dir);
  const repo = new SourceRepository(prefs, cfg());
  const loads = [];
  repo.setLoader((s, url) => loads.push([s.id, url]));
  assert.equal(repo.launchSource(), null, 'first launch shows the chooser');
  assert.equal(repo.preselected().id, 'b', 'recommended is preselected');
  prefs.set('defaultSourceId', 'a');
  assert.equal(repo.launchSource().id, 'a');
  prefs.set('askEveryLaunch', true);
  assert.equal(repo.launchSource(), null);
  prefs.set('askEveryLaunch', false);
  prefs.set('defaultSourceId', 'c');
  assert.equal(repo.launchSource(), null, 'disabled default falls back to the chooser');
  repo.switchTo('a');
  repo.rememberUrl('https://source-1.example/movie/1');
  repo.rememberUrl('https://evil.example.net/x');
  repo.switchTo('b');
  repo.switchTo('a');
  assert.deepEqual(loads, [['a', 'https://source-1.example/'], ['b', 'https://source-2.example/'], ['a', 'https://source-1.example/movie/1']]);
  repo.switchTo('c');
  assert.equal(repo.active().id, 'a', 'disabled sources cannot be activated');
  assert.equal(prefs.get('lastSourceId'), 'a');
  fs.rmSync(dir, { recursive: true, force: true });
});
