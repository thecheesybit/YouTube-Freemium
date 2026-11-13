// Resolves release signing.
// 1. config/signing.json (git-ignored) if present.
// 2. Otherwise a stable per-app key at %USERPROFILE%\.movy\keystore\<app>.jks with its passwords in <app>.properties.
//    An old .cache/keystore key is migrated (copied) for apps that were already built with it, so installed
//    apps keep updating in place. A lost key means uninstalling the app, which loses all logins: back it up.
import crypto from 'node:crypto';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { CACHE, DIST, ROOT } from '../paths.mjs';
import { capture } from '../run.mjs';

const REQUIRED = ['storeFile', 'storePassword', 'keyAlias', 'keyPassword'];
const LEGACY = { file: path.join(CACHE, 'keystore', 'movy-debug.jks'), storePassword: 'movy-debug', keyAlias: 'movy', keyPassword: 'movy-debug' };

export function keystoreDir() {
  return path.join(process.env.USERPROFILE || os.homedir(), '.movy', 'keystore');
}

function readProps(file) {
  const out = {};
  for (const line of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const m = /^([A-Za-z]+)=(.*)$/.exec(line);
    if (m) out[m[1]] = m[2];
  }
  return out;
}

function writeProps(file, p) {
  fs.writeFileSync(file, Object.entries(p).map(([k, v]) => `${k}=${v}`).join('\n') + '\n', { mode: 0o600 });
}

function warnBackup(storeFile, why) {
  console.warn(
    `\n!!! [sign] ${why}\n` +
      `!!! Back up this folder NOW (and keep it safe): ${path.dirname(storeFile)}\n` +
      '!!! Every update must be signed with this key. If it is lost, the app must be uninstalled to update, which erases all logins.\n',
  );
}

function resolveCustom(custom) {
  let s;
  try {
    s = JSON.parse(fs.readFileSync(custom, 'utf8'));
  } catch (e) {
    throw new Error(`${custom}: invalid JSON: ${e.message}`);
  }
  for (const k of REQUIRED) if (typeof s[k] !== 'string' || !s[k]) throw new Error(`${custom}: ${k}: required string`);
  const storeFile = path.resolve(path.dirname(custom), s.storeFile);
  if (!fs.existsSync(storeFile)) throw new Error(`${custom}: storeFile: ${storeFile} does not exist`);
  return { ...s, storeFile, generated: false };
}

function builtBefore(app) {
  const dir = path.join(DIST, app);
  return fs.existsSync(dir) && fs.readdirSync(dir).some((f) => f.endsWith('.apk'));
}

export function resolveSigning(javaHome, app, appName = app) {
  const custom = path.join(ROOT, 'config', 'signing.json');
  if (fs.existsSync(custom)) return resolveCustom(custom);

  const dir = keystoreDir();
  const storeFile = path.join(dir, `${app}.jks`);
  const propsFile = path.join(dir, `${app}.properties`);
  if (fs.existsSync(storeFile) && fs.existsSync(propsFile)) {
    const p = readProps(propsFile);
    for (const k of ['storePassword', 'keyAlias', 'keyPassword']) if (!p[k]) throw new Error(`${propsFile}: ${k} missing`);
    return { storeFile, storePassword: p.storePassword, keyAlias: p.keyAlias, keyPassword: p.keyPassword, generated: false };
  }
  fs.mkdirSync(dir, { recursive: true });

  if (fs.existsSync(LEGACY.file) && builtBefore(app)) {
    fs.copyFileSync(LEGACY.file, storeFile);
    const p = { storePassword: LEGACY.storePassword, keyAlias: LEGACY.keyAlias, keyPassword: LEGACY.keyPassword };
    writeProps(propsFile, p);
    warnBackup(storeFile, `Migrated the old local key for "${app}" so already-installed builds can be updated in place.`);
    return { storeFile, ...p, generated: false };
  }

  const pw = crypto.randomBytes(24).toString('base64url');
  const p = { storePassword: pw, keyAlias: app, keyPassword: pw };
  const keytool = path.join(javaHome, 'bin', process.platform === 'win32' ? 'keytool.exe' : 'keytool');
  capture(keytool, [
    '-genkeypair', '-keystore', storeFile, '-storepass', p.storePassword, '-keypass', p.keyPassword,
    '-alias', p.keyAlias, '-keyalg', 'RSA', '-keysize', '2048', '-validity', '10000',
    '-dname', `CN=${appName.replace(/[^A-Za-z0-9 ._-]/g, ' ')}, O=Ayush Kumar`,
  ]);
  writeProps(propsFile, p);
  warnBackup(storeFile, `Generated a new release key for "${app}".`);
  return { storeFile, ...p, generated: false };
}
