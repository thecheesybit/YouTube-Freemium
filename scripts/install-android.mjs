// npm run install:android -- --app <name> --target phone|tv [--serial <adb serial>]
import fs from 'node:fs';
import path from 'node:path';
import { loadResolved } from '@movy/config';
import { DIST, ROOT } from './paths.mjs';
import { parseArgs } from './args.mjs';
import { run } from './run.mjs';
import { adbPath } from './android/toolchain.mjs';

try {
  const args = parseArgs(process.argv.slice(2));
  const app = args.app;
  const target = args.target ?? 'phone';
  if (!app || !['phone', 'tv'].includes(target)) throw new Error('Usage: npm run install:android -- --app <name> --target phone|tv [--serial <id>]');
  const apk = path.join(DIST, app, `${app}-${target}.apk`);
  if (!fs.existsSync(apk)) throw new Error(`${apk} not found. Run: npm run build -- --app ${app} --target ${target}`);
  const cfg = loadResolved(ROOT, app, target);
  const pkg = cfg.appId + (target === 'tv' ? '.tv' : '');
  const adb = adbPath();
  const serial = args.serial ?? process.env.ANDROID_SERIAL;
  const base = serial ? ['-s', serial] : [];
  run(adb, [...base, 'install', '-r', apk]);
  run(adb, [...base, 'shell', 'am', 'start', '-n', `${pkg}/app.movy.shell.MainActivity`]); // never 'monkey': it injects a random event
  console.log(`Installed and launched ${pkg}`);
} catch (e) {
  console.error(`ERROR: ${e.message}`);
  process.exit(1);
}
