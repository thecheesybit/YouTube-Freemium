import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {
  ConfigError, DEFAULT_LISTS, configWarnings, hostMatches, isValidAppId, isValidHostPattern, listApps, loadAppConfig,
  loadResolved, parseAppConfig, resolveTarget, configPath, formatIssues, mergeTvProfiles,
} from '../src/index.mjs';

const valid = { name: 'demo', appName: 'Demo', appId: 'com.example.demo', url: 'https://demo.example.com/x' };
const parse = (o, name = 'demo') => parseAppConfig(JSON.stringify(o), 'demo.json', name);
const fails = (o, re) => assert.throws(() => parse(o), (e) => e instanceof ConfigError && re.test(e.message));

test('minimal config gets defaults', () => {
  const c = parse(valid);
  assert.equal(c.version, '1.0.0');
  assert.equal(c.versionCode, 1);
  assert.equal(c.externalLinks, 'block');
  assert.equal(c.adblock.enabled, true);
  assert.equal(c.adblock.lists, 'default');
  assert.deepEqual(c.targets, {});
});

test('name is filled from file name and must match', () => {
  const { name, ...rest } = valid;
  assert.equal(parse(rest).name, 'demo');
  assert.throws(() => parse({ ...valid, name: 'other' }), /must equal the file name/);
});

test('errors name file and field', () => {
  fails({ ...valid, url: 'ftp://x.com' }, /demo\.json: url: .*http/);
  fails({ ...valid, url: 'nope' }, /demo\.json: url:/);
  fails({ ...valid, appId: 'class.foo' }, /appId/);
  fails({ ...valid, appId: 'single' }, /appId/);
  fails({ ...valid, appId: 'com.1bad' }, /appId/);
  fails({ ...valid, allowedHosts: ['http://x.com'] }, /allowedHosts\.0/);
  fails({ ...valid, backgroundColor: 'red' }, /backgroundColor/);
  fails({ ...valid, orientation: 'sideways' }, /orientation/);
  fails({ ...valid, version: 'v1' }, /version/);
  fails({ ...valid, bogus: 1 }, /bogus/);
  fails({ ...valid, adblock: { lists: [] } }, /adblock\.lists/);
  fails({ ...valid, adblock: { extraLists: ['file:///x'] } }, /adblock\.extraLists\.0/);
  fails({ ...valid, targets: { watch: {} } }, /targets/);
  fails({ ...valid, targets: { tv: { name: 'x' } } }, /targets\.tv/);
  fails({ ...valid, targets: { tv: { adblock: { nope: 1 } } } }, /targets\.tv\.adblock/);
});

test('open-external is accepted, warned about and resolved to block', () => {
  const c = parse({ ...valid, externalLinks: 'open-external' });
  assert.equal(resolveTarget(c, 'phone').externalLinks, 'block');
  assert.equal(configWarnings(c, 'demo.json').length, 1);
  assert.equal(configWarnings(parse(valid)).length, 0);
  assert.equal(resolveTarget(parse({ ...valid, externalLinks: 'in-app' }), 'phone').externalLinks, 'in-app');
  assert.deepEqual(resolveTarget(parse({ ...valid, authHosts: ['sso.example.com'] }), 'phone').authHosts, ['sso.example.com']);
});

test('dohHosts is optional, validated and resolved', () => {
  assert.deepEqual(resolveTarget(parse(valid), 'phone').dohHosts, []);
  assert.deepEqual(resolveTarget(parse({ ...valid, dohHosts: ['api.example.org'] }), 'phone').dohHosts, ['api.example.org']);
  assert.throws(() => parse({ ...valid, dohHosts: ['https://api.example.org/'] }));
});

test('invalid JSON is reported', () => {
  assert.throws(() => parseAppConfig('{oops', 'x.json'), /x\.json: invalid JSON/);
});

test('host and appId helpers', () => {
  assert.ok(isValidHostPattern('example.com'));
  assert.ok(isValidHostPattern('*.example.com'));
  assert.ok(isValidHostPattern('localhost'));
  for (const bad of ['', '*', 'a*.com', 'a.com/x', 'a.com:80', '-a.com', 'a..com', 5]) assert.ok(!isValidHostPattern(bad), String(bad));
  assert.ok(isValidAppId('app.movy.shell'));
  assert.ok(!isValidAppId(7));
  assert.ok(hostMatches('*.Example.com', 'a.b.example.com'));
  assert.ok(hostMatches('*.example.com', 'example.com'));
  assert.ok(!hostMatches('*.example.com', 'badexample.com'));
  assert.ok(hostMatches('x.com', 'X.COM'));
  assert.ok(!hostMatches('x.com', 'a.x.com'));
});

test('resolveTarget merges overrides, adblock one level deep', () => {
  const c = parse({
    ...valid,
    adblock: { extraLists: ['https://a.example/l.txt'], customRules: ['||x.com^'] },
    targets: { tv: { orientation: 'landscape', adblock: { cosmetic: false } } },
  });
  const tv = resolveTarget(c, 'tv');
  assert.equal(tv.orientation, 'landscape');
  assert.equal(tv.adblock.cosmetic, false);
  assert.deepEqual(tv.adblock.customRules, ['||x.com^']);
  assert.equal(tv.target, 'tv');
  const phone = resolveTarget(c, 'phone');
  assert.equal(phone.orientation, 'any');
  assert.equal(phone.adblock.cosmetic, true);
});

test('override does not reset base values to defaults', () => {
  const c = parse({ ...valid, orientation: 'portrait', versionCode: 5, targets: { tv: { appName: 'TV' } } });
  const tv = resolveTarget(c, 'tv');
  assert.equal(tv.orientation, 'portrait');
  assert.equal(tv.versionCode, 5);
  assert.equal(tv.appName, 'TV');
});

test('lists default resolves and dedupes; custom lists replace', () => {
  const c = parse({ ...valid, adblock: { extraLists: [DEFAULT_LISTS[0], 'https://z.example/z.txt'] } });
  const r = resolveTarget(c, 'phone');
  assert.equal(r.adblock.listUrls.length, DEFAULT_LISTS.length + 1);
  const c2 = parse({ ...valid, adblock: { lists: ['https://only.example/a.txt'] } });
  assert.deepEqual(resolveTarget(c2, 'phone').adblock.listUrls, ['https://only.example/a.txt']);
});

test('allowedHosts default and cleartext', () => {
  const r = resolveTarget(parse(valid), 'phone');
  assert.deepEqual(r.allowedHosts, ['demo.example.com', '*.demo.example.com']);
  assert.equal(r.cleartext, false);
  assert.equal(resolveTarget(parse({ ...valid, url: 'http://192.168.1.2:8080' }), 'phone').cleartext, true);
  assert.throws(() => resolveTarget(parse(valid), 'watch'), /Unknown target/);
});

test('file loading', () => {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'movy-cfg-'));
  fs.mkdirSync(path.join(dir, 'config', 'apps'), { recursive: true });
  assert.deepEqual(listApps(dir), []);
  fs.writeFileSync(configPath(dir, 'demo'), JSON.stringify(valid));
  assert.deepEqual(listApps(dir), ['demo']);
  assert.equal(loadAppConfig(dir, 'demo').appName, 'Demo');
  assert.equal(loadResolved(dir, 'demo', 'desktop').target, 'desktop');
  assert.throws(() => loadAppConfig(dir, 'missing'), /not found.*demo/);
  assert.throws(() => loadAppConfig(dir, 'Bad_Name'), /Invalid app name/);
  assert.throws(() => loadAppConfig(dir, undefined), /Invalid app name/);
  assert.deepEqual(listApps(path.join(dir, 'nope')), []);
});

test('repo example config is valid', () => {
  const root = path.resolve(import.meta.dirname, '..', '..', '..');
  const c = loadAppConfig(root, 'example');
  assert.equal(c.sources.length, 2);
  for (const s of c.sources) assert.match(s.url, /^https:\/\/example\.(com|org)\//);
  assert.equal(resolveTarget(c, 'phone').recommendedSource, 'one');
});

const multi = {
  ...valid,
  url: undefined,
  sources: [
    { id: 'a', name: 'Alpha', url: 'https://a.example.com/' },
    { id: 'b', name: 'Beta', url: 'https://b.example.org/', allowedHosts: ['b.example.org'], accent: '#112233', enabled: false },
    { id: 'c', name: 'Gamma', url: 'http://c.example.net/' },
  ],
  recommendedSource: 'c',
};

test('legacy url normalizes to one source', () => {
  const r = resolveTarget(parse({ ...valid, allowedHosts: ['demo.example.com'] }), 'phone');
  assert.equal(r.sources.length, 1);
  assert.equal(r.sources[0].id, 'demo');
  assert.equal(r.sources[0].name, 'Demo');
  assert.deepEqual(r.sources[0].allowedHosts, ['demo.example.com']);
  assert.equal(r.recommendedSource, 'demo');
  assert.equal(r.url, 'https://demo.example.com/x');
  assert.equal(r.island.visibility, 'home');
});

test('multi-source resolve: defaults, accents, union of enabled hosts, recommended', () => {
  const r = resolveTarget(parse(multi), 'phone');
  assert.equal(r.sources.length, 3);
  assert.deepEqual(r.sources[0].allowedHosts, ['a.example.com', '*.a.example.com']);
  assert.equal(r.sources[1].accent, '#112233');
  assert.match(r.sources[0].accent, /^#[0-9A-F]{6}$/);
  assert.equal(r.sources[1].enabled, false);
  assert.ok(!r.allowedHosts.includes('b.example.org'));
  assert.ok(r.allowedHosts.includes('c.example.net'));
  assert.equal(r.recommendedSource, 'c');
  assert.equal(r.url, 'http://c.example.net/');
  assert.equal(r.cleartext, true);
});

test('recommended falls back to first enabled when disabled or missing', () => {
  const r = resolveTarget(parse({ ...multi, recommendedSource: undefined }), 'phone');
  assert.equal(r.recommendedSource, 'a');
});

test('source validation', () => {
  fails({ ...multi, recommendedSource: 'zzz' }, /recommendedSource/);
  fails({ ...multi, sources: [multi.sources[0], multi.sources[0]] }, /duplicate source id/);
  fails({ ...multi, sources: [{ ...multi.sources[0], id: 'Bad Id' }] }, /sources\.0\.id/);
  fails({ ...multi, sources: [] }, /sources/);
  fails({ ...valid, url: undefined }, /either `url` or `sources`/);
  fails({ ...multi, sources: [{ ...multi.sources[0], url: 'nope' }] }, /sources\.0\.url/);
});

test('island and remote blocks', () => {
  assert.equal(parse({ ...valid, island: { visibility: 'always' } }).island.visibility, 'always');
  fails({ ...valid, island: { visibility: 'never' } }, /island\.visibility/);
  const remote = { provider: 'firebase-remote-config', projectId: 'p', googleServices: 'x.json' };
  assert.equal(parse({ ...valid, remote }).remote.parameter, 'movy_config');
  fails({ ...valid, remote: { ...remote, provider: 'other' } }, /remote\.provider/);
});

test('formatIssues handles root issues', () => {
  assert.match(formatIssues('f.json', [{ path: [], message: 'bad', code: 'custom' }]), /f\.json: \(root\): bad/);
});

const profile = {
  notes: 'text',
  header: ['header.top'],
  rows: ['.row'],
  cards: ['.row a'],
  skip: ['.arrow'],
  firstFocus: ['.hero .play'],
  playButtons: ['.play'],
  servers: { toggle: '.srv', item: '.srv li', active: '.on', failed: '.dead' },
  episodes: { item: '.ep', next: '.next' },
  player: { frame: '.pf', fullscreenTarget: '.pf', fullscreenButton: ['#fs'], depth: 1 },
  killOnTv: ['.trailer'],
  viewportWidth: 1280,
};

test('tvProfiles: valid profiles parse and resolve for every target', () => {
  const c = parse({ ...multi, tvProfiles: { a: profile } });
  assert.deepEqual(c.tvProfiles.a.player.fullscreenButton, ['#fs']);
  assert.deepEqual(resolveTarget(c, 'tv').tvProfiles.a.rows, ['.row']);
  assert.deepEqual(resolveTarget(parse(valid), 'tv').tvProfiles, {});
});

test('tvProfiles: strict keys, types and ranges', () => {
  fails({ ...multi, tvProfiles: { a: { bogus: 1 } } }, /tvProfiles\.a/);
  fails({ ...multi, tvProfiles: { a: { header: 'x' } } }, /tvProfiles\.a\.header/);
  fails({ ...multi, tvProfiles: { a: { servers: { nope: 'x' } } } }, /tvProfiles\.a\.servers/);
  fails({ ...multi, tvProfiles: { a: { player: { depth: 9 } } } }, /tvProfiles\.a\.player\.depth/);
  fails({ ...multi, tvProfiles: { a: { viewportWidth: 100 } } }, /tvProfiles\.a\.viewportWidth/);
  fails({ ...multi, tvProfiles: { 'Bad Id': {} } }, /tvProfiles/);
  fails({ ...multi, tvProfiles: { a: { header: [''] } } }, /tvProfiles\.a\.header\.0/);
});

test('tvProfiles: allowed as a per-target override', () => {
  const c = parse({ ...multi, targets: { tv: { tvProfiles: { b: { rows: ['.r'] } } } } });
  assert.deepEqual(resolveTarget(c, 'tv').tvProfiles.b.rows, ['.r']);
  assert.deepEqual(resolveTarget(c, 'phone').tvProfiles, {});
});

test('tvProfiles: remote override merges per source, remote wins per key', () => {
  const merged = mergeTvProfiles({ a: { rows: ['.r'], skip: ['.s'] }, b: { rows: ['.b'] } }, { a: { rows: ['.new'] }, c: { skip: ['.c'] } });
  assert.deepEqual(merged.a, { rows: ['.new'], skip: ['.s'] });
  assert.deepEqual(merged.b, { rows: ['.b'] });
  assert.deepEqual(merged.c, { skip: ['.c'] });
});

test('cache block: on by default for tv only', () => {
  const c = parse(valid);
  assert.equal(resolveTarget(c, 'tv').cache.enabled, true);
  assert.equal(resolveTarget(c, 'phone').cache.enabled, false);
  assert.equal(resolveTarget(c, 'tv').cache.maxMb, 300);
  assert.equal(resolveTarget(c, 'tv').cache.maxAgeDays, 30);
  assert.equal(resolveTarget(parse({ ...valid, cache: { enabled: false } }), 'tv').cache.enabled, false);
  fails({ ...valid, cache: { maxMb: 1 } }, /cache\.maxMb/);
});
