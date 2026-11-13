// Dev helper: regenerate apps/desktop/gen for `electron apps/desktop` without packaging.
//   node scripts/desktop/gen-only.mjs --app youtube-freemium [--no-engine]
import { loadResolved } from '@movy/config';
import { ROOT } from '../paths.mjs';
import { parseArgs } from '../args.mjs';
import { buildEngine } from '../filters.mjs';
import { ensureNodeBinding } from './native.mjs';
import { writeGenerated } from './generate.mjs';

const args = parseArgs(process.argv.slice(2));
if (!args.app) throw new Error('Usage: node scripts/desktop/gen-only.mjs --app <name>');
const cfg = loadResolved(ROOT, args.app, 'desktop');
const engine = cfg.adblock?.enabled && !args['no-engine'] ? await buildEngine(cfg.adblock) : null;
const app = await writeGenerated(cfg, engine, engine ? ensureNodeBinding() : null, Number(args['build-number'] ?? cfg.versionCode));
console.log(`[gen] apps/desktop/gen written (${app.defaults.sources.length} sources, adblock ${app.adblock.enabled ? 'on' : 'off'})`);
