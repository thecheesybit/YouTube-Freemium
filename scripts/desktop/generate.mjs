// Writes apps/desktop/gen/: everything the Electron app reads at runtime (packaged to resources/ytfreemium or resources/movy).
// Source URLs are stored obfuscated (XOR with a per-build key + base64), never as plain strings.
import fs from 'node:fs';
import path from 'node:path';
import { DESKTOP_DIR, ROOT } from '../paths.mjs';
import { writeDesktopIcons } from './icon.mjs';
import { buildDefaults, newObfuscationKey, obfuscate } from '../android/defaults.mjs';

export const GEN = path.join(DESKTOP_DIR, 'gen');
const INJECT = [
  'cosmetic.js',
  'loading.js',
  'scamguard.js',
  'navguard.js',
  'youtube.js',
  'youtube-selectors.json'
];

/** Host lists are obfuscated too (allowedHostsEnc / dohHostsEnc); the app decodes them with the build key. */
export function obfuscateHosts(defaults, key, authHosts) {
  const enc = (list) => list.map((h) => obfuscate(h, key));
  const { dohHosts, ...rest } = defaults;
  return {
    ...rest,
    sources: defaults.sources.map(({ allowedHosts, ...s }) => ({ ...s, allowedHostsEnc: enc(allowedHosts) })),
    dohHostsEnc: enc(dohHosts ?? []),
    authHosts: authHosts ?? [],
  };
}

/** The app-config.json object (pure; unit tested). */
export function buildAppConfig(cfg, { adblockOn, buildNumber, key }) {
  return {
    name: cfg.name,
    appName: cfg.appName,
    appId: cfg.appId,
    version: cfg.version,
    build: buildNumber,
    credit: cfg.credit ?? 'Ayush Kumar',
    tagline: cfg.tagline ?? 'Ad-free YouTube and YouTube Music',
    obfKey: key.toString('base64'),
    defaults: obfuscateHosts(buildDefaults(cfg, key), key, cfg.authHosts ?? []),
    externalLinks: cfg.externalLinks ?? 'block',
    userAgent: cfg.userAgent ?? null,
    backgroundColor: cfg.backgroundColor ?? '#0F0F0F',
    adblock: { enabled: adblockOn, cosmetic: cfg.adblock?.cosmetic ?? true, blockPopups: cfg.adblock?.blockPopups ?? true },
    cache: cfg.cache ?? { enabled: true, maxMb: 100, maxAgeDays: 14 }
  };
}

/** @param {object} cfg resolved desktop config  @param {{engineDat:string,resourcesJson:string}|null} engine  @param {string} nodeFile */
export async function writeGenerated(cfg, engine, nodeFile, buildNumber = cfg.versionCode) {
  fs.rmSync(GEN, { recursive: true, force: true });
  fs.mkdirSync(GEN, { recursive: true });
  const adblockOn = cfg.adblock?.enabled && !!engine;
  const key = newObfuscationKey();
  const app = buildAppConfig(cfg, { adblockOn, buildNumber, key });
  fs.writeFileSync(path.join(GEN, 'app-config.json'), JSON.stringify(app, null, 2) + '\n');
  for (const f of INJECT) {
    const src = path.join(ROOT, 'packages', 'inject', f);
    if (fs.existsSync(src)) {
      fs.copyFileSync(src, path.join(GEN, f));
    }
  }
  await writeDesktopIcons(cfg, ROOT, GEN);
  if (adblockOn) {
    fs.copyFileSync(engine.engineDat, path.join(GEN, 'engine.dat'));
    fs.copyFileSync(engine.resourcesJson, path.join(GEN, 'resources.json'));
    fs.copyFileSync(nodeFile, path.join(GEN, 'movy_adblock_node.node'));
  }
  return app;
}
