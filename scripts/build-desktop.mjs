// config -> filters -> napi binding -> apps/desktop/gen -> electron-builder -> dist/desktop
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { loadResolved } from '@movy/config';
import { DESKTOP_DIR, DIST, ROOT } from './paths.mjs';
import { buildEngine } from './filters.mjs';
import { ensureNodeBinding } from './desktop/native.mjs';
import { GEN, writeGenerated } from './desktop/generate.mjs';
import { electronBuilderConfig } from './desktop/builder-config.mjs';

function sha256File(file) {
  const hash = crypto.createHash('sha256');
  hash.update(fs.readFileSync(file));
  return hash.digest('hex');
}

/** Builds the Windows desktop app (NSIS installer + portable exe + unpacked dir); returns artifacts summary. */
export async function buildDesktop(app, opts = {}) {
  console.log(`\n=== desktop: ${app} ===`);
  const cfg = loadResolved(ROOT, app, 'desktop');
  const engine = cfg.adblock?.enabled ? await buildEngine(cfg.adblock) : null;
  const nodeFile = engine ? ensureNodeBinding() : null;
  await writeGenerated(cfg, engine, nodeFile, opts.buildNumber ?? cfg.versionCode);

  const outDir = path.join(DIST, 'desktop');
  fs.rmSync(outDir, { recursive: true, force: true });
  const { build, Platform, Arch } = await import('electron-builder');
  await build({
    targets: Platform.WINDOWS.createTarget(['nsis', 'portable', 'dir'], Arch.x64),
    projectDir: DESKTOP_DIR,
    config: electronBuilderConfig(cfg, { outDir, gen: GEN }),
    publish: 'never',
  });

  const files = fs.readdirSync(outDir);
  const nsisFile = files.find((f) => f.endsWith('.exe') && f.includes('Setup'));
  const portableFile = files.find((f) => f.endsWith('.exe') && f.includes('portable'));

  console.log('\n[artifacts]');
  const artifacts = [];
  if (nsisFile) {
    const full = path.join(outDir, nsisFile);
    const sz = (fs.statSync(full).size / (1024 * 1024)).toFixed(2);
    const hash = sha256File(full);
    console.log(`  NSIS Installer: ${nsisFile} (${sz} MiB) SHA-256: ${hash}`);
    artifacts.push(full);
  }
  if (portableFile) {
    const full = path.join(outDir, portableFile);
    const sz = (fs.statSync(full).size / (1024 * 1024)).toFixed(2);
    const hash = sha256File(full);
    console.log(`  Portable EXE:   ${portableFile} (${sz} MiB) SHA-256: ${hash}`);
    artifacts.push(full);
  }

  return artifacts[0] || outDir;
}
