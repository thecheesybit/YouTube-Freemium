import { spawnSync } from 'node:child_process';

/** Runs a command synchronously with inherited stdio; throws on failure. */
export function run(cmd, args, opts = {}) {
  const { shell = false, ...rest } = opts;
  const r = spawnSync(cmd, args, { stdio: 'inherit', shell, ...rest });
  if (r.error) throw new Error(`Failed to run ${cmd}: ${r.error.message}`);
  if (r.status !== 0) throw new Error(`${cmd} ${args.join(' ')} exited with ${r.status}`);
}

/** Runs and returns stdout (string); throws on failure. */
export function capture(cmd, args, opts = {}) {
  const r = spawnSync(cmd, args, { encoding: 'utf8', ...opts });
  if (r.error) throw new Error(`Failed to run ${cmd}: ${r.error.message}`);
  if (r.status !== 0) throw new Error(`${cmd} ${args.join(' ')} exited with ${r.status}: ${r.stderr}`);
  return r.stdout;
}
