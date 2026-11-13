// Downloads filter lists + resources.json (24h cache, offline fallback) and builds engine.dat.
// Output: .cache/engine/<hash>/{engine.dat,resources.json}
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { RESOURCES_URL } from '@movy/config';
import { ADBLOCK_DIR, CACHE, envWithCargo } from './paths.mjs';
import { run } from './run.mjs';

const TTL_MS = 24 * 60 * 60 * 1000;
const FETCH_TIMEOUT_MS = 60_000;
const FILTER_DIR = path.join(CACHE, 'filters');
const sha = (s, n = 16) => crypto.createHash('sha256').update(s).digest('hex').slice(0, n);

/** Fetches `url` into the cache (fresh within TTL); falls back to a stale copy offline. Returns file path. */
export async function fetchCached(url, { force = false, ttlMs = TTL_MS, fetchImpl = fetch } = {}) {
  fs.mkdirSync(FILTER_DIR, { recursive: true });
  const file = path.join(FILTER_DIR, sha(url) + '.txt');
  const have = fs.existsSync(file);
  if (have && !force && Date.now() - fs.statSync(file).mtimeMs < ttlMs) return file;
  try {
    const res = await fetchImpl(url, { signal: AbortSignal.timeout(FETCH_TIMEOUT_MS), headers: { 'user-agent': 'movy-build' } });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const body = await res.text();
    if (body.length < 10) throw new Error('empty response');
    fs.writeFileSync(file, body);
    return file;
  } catch (e) {
    if (have) {
      console.warn(`[filters] ${url}: ${e.message} - using cached copy`);
      return file;
    }
    throw new Error(`Cannot download ${url} (${e.message}) and no cached copy exists. Connect to the internet once to populate .cache/filters.`);
  }
}

/** Ensures the host build-engine binary exists (cargo build is incremental). */
export function ensureBuildEngine() {
  const exe = path.join(ADBLOCK_DIR, 'target', 'release', process.platform === 'win32' ? 'build-engine.exe' : 'build-engine');
  run('cargo', ['build', '--release', '--bin', 'build-engine', '--quiet'], { cwd: ADBLOCK_DIR, env: envWithCargo() });
  if (!fs.existsSync(exe)) throw new Error(`build-engine not found at ${exe} after cargo build`);
  return exe;
}

/**
 * @param {{listUrls:string[], customRules?:string[]}} adblock
 * @returns {Promise<{dir:string, engineDat:string, resourcesJson:string, cached:boolean}>}
 */
export async function buildEngine({ listUrls, customRules = [] }, opts = {}) {
  const listFiles = [];
  for (const u of listUrls) listFiles.push(await fetchCached(u, opts));
  const resFile = await fetchCached(RESOURCES_URL, opts);
  const lock = fs.readFileSync(path.join(ADBLOCK_DIR, 'Cargo.lock'), 'utf8');
  const hash = sha(
    JSON.stringify({
      lists: listFiles.map((f) => sha(fs.readFileSync(f))),
      res: sha(fs.readFileSync(resFile)),
      custom: customRules,
      lock: sha(lock),
    }),
  );
  const dir = path.join(CACHE, 'engine', hash);
  const engineDat = path.join(dir, 'engine.dat');
  const resourcesJson = path.join(dir, 'resources.json');
  if (fs.existsSync(engineDat) && fs.existsSync(resourcesJson)) return { dir, engineDat, resourcesJson, cached: true };

  const exe = ensureBuildEngine();
  fs.mkdirSync(dir, { recursive: true });
  const args = ['--out', engineDat, '--resources', resFile];
  const all = [...listFiles];
  if (customRules.length) {
    const custom = path.join(dir, 'custom.txt');
    fs.writeFileSync(custom, customRules.join('\n') + '\n');
    all.push(custom);
  }
  run(exe, [...args, ...all]);
  fs.copyFileSync(resFile, resourcesJson);
  return { dir, engineDat, resourcesJson, cached: false };
}

// CLI: node scripts/filters.mjs --app <name> [--target phone] [--force]
if (process.argv[1] && path.resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const { loadResolved } = await import('@movy/config');
  const { ROOT } = await import('./paths.mjs');
  const args = process.argv.slice(2);
  const get = (k, d) => (args.includes(k) ? args[args.indexOf(k) + 1] : d);
  const cfg = loadResolved(ROOT, get('--app', 'example'), get('--target', 'phone'));
  const out = await buildEngine(cfg.adblock, { force: args.includes('--force') });
  console.log(out);
}
