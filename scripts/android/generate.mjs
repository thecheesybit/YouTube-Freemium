// Writes every generated, git-ignored input of the Android project from a resolved app config.
import fs from 'node:fs';
import path from 'node:path';
import { MOBILE_DIR, ROOT } from '../paths.mjs';
import { generateIcons } from './icons.mjs';
import { buildDefaults } from './defaults.mjs';

export const TV_SCRIPTS = ['tvnav-pure.js', 'tvnav.js', 'tvfs.js'];
export const ANDROID = path.join(MOBILE_DIR, 'android');
export const APP = path.join(ANDROID, 'app');
export const MAIN = path.join(APP, 'src', 'main');
const TV = path.join(APP, 'src', 'tv');

const ORIENTATION = { any: 'unspecified', portrait: 'portrait', landscape: 'sensorLandscape' };

function writeJson(file, obj) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, JSON.stringify(obj, null, 2) + '\n');
}

/** capacitor.config.json used by `cap sync` (apps/mobile/capacitor.config.json). */
export function writeCapacitorConfig(cfg) {
  // No server.url / allowNavigation / errorPath: source URLs must not appear in generated assets.
  // The native shell loads the chosen source itself (and shows its own error panel).
  writeJson(path.join(MOBILE_DIR, 'capacitor.config.json'), {
    appId: cfg.appId,
    appName: cfg.appName,
    webDir: 'www',
    backgroundColor: cfg.backgroundColor,
    server: { cleartext: cfg.cleartext },
    android: { allowMixedContent: false },
    plugins: { SystemBars: { insetsHandling: 'disable' } },
  });
}

/** Capacitor needs a webDir; it only ever holds this blank page (the shell loads the sources itself). */
export function writeBlankPage(cfg) {
  const html = `<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><body style="background:${cfg.backgroundColor}">
`;
  fs.rmSync(path.join(MOBILE_DIR, 'www'), { recursive: true, force: true });
  fs.mkdirSync(path.join(MOBILE_DIR, 'www'), { recursive: true });
  fs.writeFileSync(path.join(MOBILE_DIR, 'www', 'index.html'), html);
}

/** android/app/movy.generated.json (read by app/build.gradle) and assets/movy.json (read at runtime). */
export function writeAndroidConfigs(cfg, signing, key) {
  const orientation = cfg.target === 'tv' && cfg.orientation === 'any' ? 'landscape' : cfg.orientation;
  writeJson(path.join(APP, 'movy.generated.json'), {
    appId: cfg.appId,
    appName: cfg.appName,
    version: cfg.version,
    versionCode: cfg.versionCode,
    defaultsKey: key.toString('base64'),
    remote: cfg.remote ? { parameter: cfg.remote.parameter } : null,
    orientation: ORIENTATION[orientation],
    cleartext: cfg.cleartext,
    signing,
  });
  writeJson(path.join(MAIN, 'assets', 'movy.json'), {
    backgroundColor: cfg.backgroundColor,
    authHosts: cfg.authHosts,
    externalLinks: cfg.externalLinks,
    userAgent: cfg.userAgent,
    adblock: { enabled: cfg.adblock.enabled, cosmetic: cfg.adblock.cosmetic, blockPopups: cfg.adblock.blockPopups },
    cache: { enabled: cfg.cache.enabled, maxMb: cfg.cache.maxMb, maxAgeDays: cfg.cache.maxAgeDays },
  });
  writeJson(path.join(MAIN, 'assets', 'movy-defaults.json'), buildDefaults(cfg, key));
}

/**
 * Firebase only when `remote` is configured: copies google-services.json for the google-services plugin;
 * otherwise removes any stale copy (Gradle then builds without Firebase).
 */
export function writeFirebaseFiles(cfg, rootDir) {
  const dest = path.join(APP, 'google-services.json');
  if (!cfg.remote) {
    fs.rmSync(dest, { force: true });
    return;
  }
  const src = path.resolve(rootDir, cfg.remote.googleServices);
  if (!fs.existsSync(src)) throw new Error(`remote.googleServices: ${src} does not exist`);
  const pkg = cfg.appId + (cfg.target === 'tv' ? '.tv' : '');
  const names = JSON.parse(fs.readFileSync(src, 'utf8')).client?.map((c) => c.client_info?.android_client_info?.package_name) ?? [];
  if (!names.includes(pkg)) throw new Error(`${src} has no client for package ${pkg} (has: ${names.join(', ')})`);
  fs.copyFileSync(src, dest);
}

/** Copies engine files + cosmetic.js into assets/adblock; removes stale engine when adblock is off. */
export function writeAdblockAssets(cfg, engine) {
  const dir = path.join(MAIN, 'assets', 'adblock');
  fs.rmSync(dir, { recursive: true, force: true });
  fs.mkdirSync(dir, { recursive: true });
  fs.copyFileSync(path.join(ROOT, 'packages', 'inject', 'cosmetic.js'), path.join(dir, 'cosmetic.js'));
  const shell = path.join(MAIN, 'assets', 'shell');
  fs.rmSync(shell, { recursive: true, force: true });
  fs.mkdirSync(shell, { recursive: true });
  for (const f of ['loading.js', 'navguard.js', 'scamguard.js', 'youtube.js', 'youtube-selectors.json', ...(cfg.target === 'tv' ? TV_SCRIPTS : [])]) {
    const src = path.join(ROOT, 'packages', 'inject', f);
    if (fs.existsSync(src)) fs.copyFileSync(src, path.join(shell, f));
  }
  if (cfg.adblock.enabled && engine) {
    fs.copyFileSync(engine.engineDat, path.join(dir, 'engine.dat'));
    fs.copyFileSync(engine.resourcesJson, path.join(dir, 'resources.json'));
  }
}

export async function writeIcons(cfg, rootDir) {
  const assets = path.join(rootDir, 'config', 'assets', cfg.name);
  const pick = (f) => (fs.existsSync(path.join(assets, f)) ? path.join(assets, f) : null);
  await generateIcons({
    resDir: path.join(MAIN, 'res'),
    tvResDir: path.join(TV, 'res'),
    name: cfg.name,
    appName: cfg.appName,
    backgroundColor: cfg.backgroundColor,
    iconPath: pick('icon.png'),
    bannerPath: pick('tv-banner.png'),
  });
}
