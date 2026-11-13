// Per-app build numbers (Android versionCode): auto-incremented on every build, stored in
// config/apps/.build-numbers.json (git-ignored). `--build-number N` overrides.
import fs from 'node:fs';
import path from 'node:path';

const file = (root) => path.join(root, 'config', 'apps', '.build-numbers.json');

function readAll(root) {
  try {
    const raw = JSON.parse(fs.readFileSync(file(root), 'utf8'));
    return raw && typeof raw === 'object' ? raw : {};
  } catch {
    return {};
  }
}

/** The number this build will use (not yet persisted). */
export function pickBuildNumber(root, app, configVersionCode, override) {
  if (override !== undefined) {
    const n = Number(override);
    if (!Number.isInteger(n) || n < 1 || n > 2100000000) throw new Error(`--build-number must be an integer >= 1, got "${override}"`);
    return n;
  }
  const stored = Number(readAll(root)[app]);
  return Math.max(Number.isInteger(stored) ? stored + 1 : 1, configVersionCode);
}

/** Records a successfully built number. */
export function commitBuildNumber(root, app, n) {
  const all = { ...readAll(root), [app]: n };
  fs.mkdirSync(path.dirname(file(root)), { recursive: true });
  fs.writeFileSync(file(root), JSON.stringify(all, null, 2) + '\n');
}
