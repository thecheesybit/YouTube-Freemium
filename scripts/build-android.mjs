// config -> filters -> native libs -> generated project inputs -> cap sync -> gradle -> dist/<app>/<app>-<target>.apk
import fs from 'node:fs';
import path from 'node:path';
import { loadResolved } from '@movy/config';
import { ADBLOCK_DIR, DIST, MOBILE_DIR, ROOT } from './paths.mjs';
import { run } from './run.mjs';
import { buildEngine } from './filters.mjs';
import { findAndroidSdk, findJavaHome } from './android/toolchain.mjs';
import { resolveSigning } from './android/signing.mjs';
import { newObfuscationKey } from './android/defaults.mjs';
import {
  ANDROID, MAIN, writeAdblockAssets, writeAndroidConfigs, writeBlankPage, writeCapacitorConfig, writeFirebaseFiles, writeIcons,
} from './android/generate.mjs';

const JNI_DIR = path.join(MAIN, 'jniLibs');
const FLAVOR = { phone: 'Phone', tv: 'Tv' };

function newestMtime(dir, skip = new Set(['target'])) {
  let newest = 0;
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (skip.has(e.name)) continue;
    const p = path.join(dir, e.name);
    newest = Math.max(newest, e.isDirectory() ? newestMtime(p, skip) : fs.statSync(p).mtimeMs);
  }
  return newest;
}

/** Rebuilds libmovy_adblock.so only when the Rust sources are newer than the outputs. */
function ensureNativeLibs() {
  const abis = ['arm64-v8a', 'armeabi-v7a', 'x86_64'];
  const sos = abis.map((a) => path.join(JNI_DIR, a, 'libmovy_adblock.so'));
  const srcTime = Math.max(
    newestMtime(path.join(ADBLOCK_DIR, 'src')),
    fs.statSync(path.join(ADBLOCK_DIR, 'Cargo.toml')).mtimeMs,
    fs.statSync(path.join(ADBLOCK_DIR, 'Cargo.lock')).mtimeMs,
  );
  if (sos.every((f) => fs.existsSync(f) && fs.statSync(f).mtimeMs > srcTime)) {
    console.log('[native] libmovy_adblock.so up to date');
    return;
  }
  console.log('[native] building libmovy_adblock.so (3 ABIs)');
  run('pwsh', ['-NoProfile', '-File', path.join(ADBLOCK_DIR, 'build-android.ps1'), '-OutDir', JNI_DIR]);
}

function gradleEnv(javaHome, sdk) {
  return { ...process.env, JAVA_HOME: javaHome, ANDROID_HOME: sdk, ANDROID_SDK_ROOT: sdk };
}

/** Builds one Android target ('phone' | 'tv'); returns the APK path. */
export async function buildAndroid(app, target, { buildNumber } = {}) {
  if (!FLAVOR[target]) throw new Error(`Android target must be phone or tv, got "${target}"`);
  console.log(`\n=== android ${target}: ${app} ===`);
  const cfg = { ...loadResolved(ROOT, app, target) };
  if (buildNumber !== undefined) cfg.versionCode = buildNumber;
  console.log(`[build] ${cfg.appName} v${cfg.version} (build ${cfg.versionCode})${cfg.remote ? ', remote config on' : ''}`);
  const javaHome = findJavaHome();
  const sdk = findAndroidSdk();
  const signing = resolveSigning(javaHome, app, cfg.appName);

  const engine = cfg.adblock.enabled ? await buildEngine(cfg.adblock) : null;
  ensureNativeLibs();

  writeCapacitorConfig(cfg);
  writeBlankPage(cfg);
  writeAndroidConfigs(cfg, signing, newObfuscationKey());
  writeFirebaseFiles(cfg, ROOT);
  writeAdblockAssets(cfg, engine);
  await writeIcons(cfg, ROOT);

  const env = gradleEnv(javaHome, sdk);
  run('npx', ['cap', 'sync', 'android'], { cwd: MOBILE_DIR, env, shell: true });

  const gradlew = path.join(ANDROID, process.platform === 'win32' ? 'gradlew.bat' : 'gradlew');
  run(`"${gradlew}"`, [`assemble${FLAVOR[target]}Release`, '--console=plain'], { cwd: ANDROID, env, shell: true });

  const apk = path.join(ANDROID, 'app', 'build', 'outputs', 'apk', target, 'release', `app-${target}-release.apk`);
  if (!fs.existsSync(apk)) throw new Error(`Gradle succeeded but ${apk} is missing`);
  const out = path.join(DIST, app, `${app}-${target}.apk`);
  fs.mkdirSync(path.dirname(out), { recursive: true });
  fs.copyFileSync(apk, out);
  const namedApk = path.join(DIST, `YouTube-Freemium-${cfg.version}.apk`);
  fs.copyFileSync(apk, namedApk);
  console.log(`[done] ${out} (${(fs.statSync(out).size / 1048576).toFixed(1)} MiB)`);
  console.log(`[done] ${namedApk}`);
  return namedApk;
}
