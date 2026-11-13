import path from 'node:path';
import { fileURLToPath } from 'node:url';

export const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
export const CACHE = path.join(ROOT, '.cache');
export const ADBLOCK_DIR = path.join(ROOT, 'native', 'adblock');
export const MOBILE_DIR = path.join(ROOT, 'apps', 'mobile');
export const DESKTOP_DIR = path.join(ROOT, 'apps', 'desktop');
export const DIST = path.join(ROOT, 'dist');

/** process.env with the rust toolchain on PATH. */
export function envWithCargo(extra = {}) {
  const home = process.env.USERPROFILE || process.env.HOME || '';
  const sep = path.delimiter;
  const key = Object.keys(process.env).find((k) => k.toLowerCase() === 'path') || 'PATH';
  return { ...process.env, [key]: `${path.join(home, '.cargo', 'bin')}${sep}${process.env[key] ?? ''}`, ...extra };
}
