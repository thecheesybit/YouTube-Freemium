import fs from 'node:fs';
import path from 'node:path';
import { appConfigSchema } from './schema.mjs';
import { DEFAULT_LISTS, TARGETS } from './defaults.mjs';

export class ConfigError extends Error {
  constructor(message) {
    super(message);
    this.name = 'ConfigError';
  }
}

/** Formats zod issues as "<file>: <field>: <message>" lines. */
export function formatIssues(file, issues) {
  return issues
    .map((i) => {
      const field = i.path.length ? i.path.join('.') : '(root)';
      const extra = i.code === 'unrecognized_keys' ? ` (${i.keys.join(', ')})` : '';
      return `${file}: ${field}: ${i.message}${extra}`;
    })
    .join('\n');
}

/** Parses and validates raw JSON text for config file `file`. */
export function parseAppConfig(text, file, expectedName) {
  let raw;
  try {
    raw = JSON.parse(text);
  } catch (e) {
    throw new ConfigError(`${file}: invalid JSON: ${e.message}`);
  }
  if (raw && typeof raw === 'object' && raw.name === undefined && expectedName) raw = { ...raw, name: expectedName };
  const res = appConfigSchema.safeParse(raw);
  if (!res.success) throw new ConfigError(formatIssues(file, res.error.issues));
  if (expectedName && res.data.name !== expectedName) {
    throw new ConfigError(`${file}: name: "${res.data.name}" must equal the file name "${expectedName}"`);
  }
  return res.data;
}

/** Non-fatal config problems; the build prints these. */
export function configWarnings(config, file = 'config') {
  const out = [];
  const all = [config.externalLinks, ...Object.values(config.targets ?? {}).map((t) => t?.externalLinks)];
  if (all.includes('open-external')) {
    out.push(`${file}: externalLinks: "open-external" is deprecated and is treated as "block" (the app never launches other apps)`);
  }
  return out;
}

/** Merges targets.<target> over the base config (adblock merged one level deep). */
export function resolveTarget(config, target) {
  if (!TARGETS.includes(target)) {
    throw new ConfigError(`Unknown target "${target}"; expected one of ${TARGETS.join(', ')}`);
  }
  const { targets, ...base } = config;
  const ov = targets[target] ?? {};
  const { adblock: adOv = {}, ...rest } = ov;
  const merged = { ...base, ...rest, adblock: { ...base.adblock, ...adOv } };
  const lists = merged.adblock.lists === 'default' ? [...DEFAULT_LISTS] : [...merged.adblock.lists];
  const listUrls = [...new Set([...lists, ...merged.adblock.extraLists])];
  const sources = normalizeSources(merged);
  const enabled = sources.filter((s) => s.enabled);
  const active = enabled.length ? enabled : sources;
  const recommended = sources.find((s) => s.id === merged.recommendedSource && s.enabled) ?? active[0];
  return {
    ...merged,
    target,
    sources,
    recommendedSource: recommended.id,
    url: recommended.url,
    allowedHosts: [...new Set(active.flatMap((s) => s.allowedHosts))],
    authHosts: merged.authHosts ?? [],
    dohHosts: merged.dohHosts ?? [],
    externalLinks: merged.externalLinks === 'in-app' ? 'in-app' : 'block',
    adblock: { ...merged.adblock, listUrls },
    tvProfiles: merged.tvProfiles ?? {},
    cache: { enabled: merged.cache?.enabled ?? target === 'tv', maxMb: merged.cache?.maxMb ?? 300, maxAgeDays: merged.cache?.maxAgeDays ?? 30 },
    cleartext: sources.some((s) => new URL(s.url).protocol === 'http:'),
  };
}

export const ACCENT_PALETTE = Object.freeze(['#E50914', '#22C55E', '#FACC15', '#3B82F6', '#A855F7', '#F97316']);

/** Normalizes `sources` (or the legacy url + allowedHosts) into complete source records. */
export function normalizeSources(cfg) {
  const raw = cfg.sources ?? [{
    id: cfg.name, name: cfg.appName, url: cfg.url, allowedHosts: cfg.allowedHosts, enabled: true,
  }];
  return raw.map((s, i) => {
    const host = new URL(s.url).hostname;
    return {
      id: s.id,
      name: s.name,
      url: s.url,
      allowedHosts: s.allowedHosts ?? (cfg.sources ? undefined : cfg.allowedHosts) ?? [host, `*.${host}`],
      accent: s.accent ?? ACCENT_PALETTE[i % ACCENT_PALETTE.length],
      enabled: s.enabled !== false,
    };
  });
}

export function configPath(rootDir, name) {
  return path.join(rootDir, 'config', 'apps', `${name}.json`);
}

/** Lists app config names (files in config/apps). */
export function listApps(rootDir) {
  const dir = path.join(rootDir, 'config', 'apps');
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).filter((f) => f.endsWith('.json')).map((f) => f.slice(0, -5)).sort();
}

/** Loads config/apps/<name>.json; returns the validated (unresolved) config. */
export function loadAppConfig(rootDir, name) {
  if (!/^[a-z0-9]+(-[a-z0-9]+)*$/.test(name ?? '')) {
    throw new ConfigError(`Invalid app name "${name}": use lowercase letters, digits and dashes`);
  }
  const file = configPath(rootDir, name);
  if (!fs.existsSync(file)) {
    const known = listApps(rootDir);
    throw new ConfigError(`${file}: not found. Available apps: ${known.join(', ') || '(none)'}`);
  }
  return parseAppConfig(fs.readFileSync(file, 'utf8'), file, name);
}

/** Convenience: load + resolve for a target. */
export function loadResolved(rootDir, name, target) {
  return resolveTarget(loadAppConfig(rootDir, name), target);
}
