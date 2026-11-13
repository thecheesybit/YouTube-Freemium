// npm run build -- --app <name> --target phone|tv|desktop|all
import fs from 'node:fs';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { TARGETS, configWarnings, loadAppConfig } from '@movy/config';
import { ROOT } from './paths.mjs';
import { parseArgs } from './args.mjs';
import { commitBuildNumber, pickBuildNumber } from './android/buildnumber.mjs';

/**
 * Target registry. A desktop builder plugs in by providing scripts/build-desktop.mjs that exports
 * `buildDesktop(app)`; nothing else needs to change.
 */
const BUILDERS = {
  phone: async (app, o) => (await import('./build-android.mjs')).buildAndroid(app, 'phone', o),
  tv: async (app, o) => (await import('./build-android.mjs')).buildAndroid(app, 'tv', o),
  desktop: async (app, o) => {
    const file = path.join(ROOT, 'scripts', 'build-desktop.mjs');
    if (!fs.existsSync(file)) throw new Error('Desktop target is not available yet (scripts/build-desktop.mjs missing)');
    return (await import(pathToFileURL(file).href)).buildDesktop(app, o);
  },
};

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (!args.app) throw new Error('Usage: npm run build -- --app <name> --target phone|tv|desktop|all');
  const loaded = loadAppConfig(ROOT, args.app); // validate early, with file/field errors
  for (const w of configWarnings(loaded, `config/apps/${args.app}.json`)) console.warn(`WARNING: ${w}`);
  const t = args.target ?? 'all';
  const targets = t === 'all' ? TARGETS : t.split(',');
  for (const x of targets) if (!BUILDERS[x]) throw new Error(`Unknown target "${x}"; expected ${TARGETS.join(', ')} or all`);
  const buildNumber = pickBuildNumber(ROOT, args.app, loaded.versionCode, args['build-number']);
  const results = [];
  for (const x of targets) results.push(await BUILDERS[x](args.app, { buildNumber }));
  if (targets.some((x) => x === 'phone' || x === 'tv')) commitBuildNumber(ROOT, args.app, buildNumber);
  console.log('\nBuilt:\n' + results.filter(Boolean).map((r) => '  ' + r).join('\n'));
}

main().catch((e) => {
  console.error(`\nERROR: ${e.message}`);
  process.exit(1);
});
