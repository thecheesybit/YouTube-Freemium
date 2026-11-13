/** Tiny `--key value` / `--flag` parser. */
export function parseArgs(argv, { flags = [] } = {}) {
  const out = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith('--')) throw new Error(`Unexpected argument "${a}"`);
    const key = a.slice(2);
    if (flags.includes(key)) {
      out[key] = true;
    } else {
      const v = argv[++i];
      if (v === undefined || v.startsWith('--')) throw new Error(`--${key} needs a value`);
      out[key] = v;
    }
  }
  return out;
}
