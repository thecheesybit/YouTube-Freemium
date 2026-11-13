// Writes assets/movy-defaults.json: the full default live config, with start URLs obfuscated
// (XOR with a per-build random key + base64). The key goes into BuildConfig; URLs never appear as plain strings.
import crypto from 'node:crypto';

export function newObfuscationKey() {
  return crypto.randomBytes(16);
}

export function obfuscate(plain, key) {
  const data = Buffer.from(plain, 'utf8');
  const out = Buffer.alloc(data.length);
  for (let i = 0; i < data.length; i++) out[i] = data[i] ^ key[i % key.length];
  return out.toString('base64');
}

/** Inverse of obfuscate (tests / verification). */
export function deobfuscate(enc, key) {
  const data = Buffer.from(enc, 'base64');
  const out = Buffer.alloc(data.length);
  for (let i = 0; i < data.length; i++) out[i] = data[i] ^ key[i % key.length];
  return out.toString('utf8');
}

/** Default live config for the app (everything remote may override). Sources carry `urlEnc`, never `url`. */
export function buildDefaults(cfg, key) {
  return {
    schema: 1,
    contentVersion: 0,
    sources: cfg.sources.map((s) => ({
      id: s.id,
      name: s.name,
      urlEnc: obfuscate(s.url, key),
      allowedHosts: s.allowedHosts,
      accent: s.accent,
      enabled: s.enabled,
    })),
    recommendedSource: cfg.recommendedSource,
    splash: { tagline: cfg.tagline, credit: cfg.credit, minMs: 2600 },
    island: { visibility: cfg.island.visibility },
    extraAllowedHosts: [],
    dohHosts: cfg.dohHosts ?? [],
    // TV navigation profiles (selectors only); empty for the phone flavor so nothing TV-specific ships there.
    tvProfiles: cfg.target === 'tv' ? cfg.tvProfiles ?? {} : {},
  };
}
