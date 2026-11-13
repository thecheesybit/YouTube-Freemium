// Host pattern helpers shared by validation and (via copy of semantics) the native shells.
const LABEL = /^(?!-)[a-z0-9-]{1,63}(?<!-)$/i;

/** True for "example.com", "*.example.com", "localhost", IPv4 literals. */
export function isValidHostPattern(p) {
  if (typeof p !== 'string' || p.length === 0 || p.length > 253) return false;
  const host = p.startsWith('*.') ? p.slice(2) : p;
  if (host.length === 0 || host.includes('*') || host.includes('/') || host.includes(':')) return false;
  return host.split('.').every((l) => LABEL.test(l));
}

/** Does `host` match `pattern` ("*.x.com" matches x.com and any subdomain). */
export function hostMatches(pattern, host) {
  const h = host.toLowerCase();
  const p = pattern.toLowerCase();
  if (p.startsWith('*.')) {
    const base = p.slice(2);
    return h === base || h.endsWith('.' + base);
  }
  return h === p;
}

const JAVA_KEYWORDS = new Set([
  'abstract','assert','boolean','break','byte','case','catch','char','class','const','continue','default','do',
  'double','else','enum','extends','final','finally','float','for','goto','if','implements','import','instanceof',
  'int','interface','long','native','new','package','private','protected','public','return','short','static',
  'strictfp','super','switch','synchronized','this','throw','throws','transient','try','void','volatile','while',
  'true','false','null','_',
]);

/** Valid Android applicationId / Java package: >=2 segments, each [A-Za-z][A-Za-z0-9_]*, no keywords. */
export function isValidAppId(id) {
  if (typeof id !== 'string') return false;
  const parts = id.split('.');
  if (parts.length < 2) return false;
  return parts.every((s) => /^[A-Za-z][A-Za-z0-9_]*$/.test(s) && !JAVA_KEYWORDS.has(s));
}
