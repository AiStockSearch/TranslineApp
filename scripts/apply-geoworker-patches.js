#!/usr/bin/env node
/**
 * Apply TranslineGeoWorker host patches (app/connect/patches) to a React Native app.
 *
 * Run from the **host RN project root** (cwd = android/ + ios/).
 *
 * Usage:
 *   node /path/to/TranslineGeoWorker/scripts/apply-geoworker-patches.js \
 *     --root /path/to/TranslineGeoWorker \
 *     [--platform android|ios|all] \
 *     [--dry-run]
 *
 * Env:
 *   GEOWORKER_ROOT — default for --root
 *
 * Exit: 0 if all applied/skipped; 1 if any patch failed.
 */
'use strict';

const fs = require('fs');
const path = require('path');
const { spawnSync } = require('child_process');

function parseArgs(argv) {
  const out = {
    root: process.env.GEOWORKER_ROOT || '',
    platform: 'all',
    dryRun: false,
    host: process.cwd(),
  };
  for (let i = 2; i < argv.length; i++) {
    const a = argv[i];
    if (a === '--dry-run') out.dryRun = true;
    else if (a === '--root' && argv[i + 1]) out.root = argv[++i];
    else if (a.startsWith('--root=')) out.root = a.slice('--root='.length);
    else if (a === '--platform' && argv[i + 1]) out.platform = argv[++i];
    else if (a.startsWith('--platform=')) out.platform = a.slice('--platform='.length);
    else if (a === '--host' && argv[i + 1]) out.host = path.resolve(argv[++i]);
    else if (a.startsWith('--host=')) out.host = path.resolve(a.slice('--host='.length));
    else if (a === '--help' || a === '-h') out.help = true;
  }
  return out;
}

function resolveGeoWorkerRoot(explicit) {
  if (explicit) return path.resolve(explicit);
  const sibling = path.resolve(process.cwd(), '..', 'TranslineGeoWorker');
  if (fs.existsSync(path.join(sibling, 'app', 'connect', 'patch-package.index.js'))) {
    return sibling;
  }
  // Script lives in TranslineGeoWorker/scripts/
  const fromScript = path.resolve(__dirname, '..');
  if (fs.existsSync(path.join(fromScript, 'app', 'connect', 'patch-package.index.js'))) {
    return fromScript;
  }
  return null;
}

function loadIndex(geoRoot) {
  const indexPath = path.join(geoRoot, 'app', 'connect', 'patch-package.index.js');
  if (!fs.existsSync(indexPath)) {
    throw new Error(`Index not found: ${indexPath}`);
  }
  // Paths in index are relative to app/connect/
  // eslint-disable-next-line import/no-dynamic-require, global-require
  return require(indexPath);
}

function classifyPatchResult(status, stderr, stdout) {
  if (status === 0) return 'applied';
  const text = `${stdout || ''}\n${stderr || ''}`.toLowerCase();
  // GNU/BSD patch when hunk already present
  if (
    text.includes('previously applied') ||
    text.includes('already applied') ||
    text.includes('reversed (or previously applied)') ||
    text.includes('skipping patch') ||
    (text.includes('ignoring') && text.includes('patch'))
  ) {
    return 'skipped';
  }
  return 'failed';
}

function findPatchBin() {
  const candidates = ['patch', '/usr/bin/patch', '/bin/patch'];
  for (const bin of candidates) {
    const probe = spawnSync(bin, ['--version'], { encoding: 'utf8' });
    if (!probe.error && (probe.status === 0 || probe.status === 1)) {
      return bin;
    }
  }
  return null;
}

function runPatch(hostCwd, patchFile, dryRun, patchBin) {
  // -t: batch (no interactive prompts); -N: skip reversed/already-applied when possible
  const args = ['-p1', '-t', '-N', '--forward', '-i', patchFile];
  if (dryRun) args.push('--dry-run');
  const result = spawnSync(patchBin, args, {
    cwd: hostCwd,
    encoding: 'utf8',
    input: '',
  });
  const status = result.status == null ? 1 : result.status;
  const kind = classifyPatchResult(status, result.stderr, result.stdout);
  return {
    kind: status === 0 ? 'applied' : kind === 'skipped' ? 'skipped' : 'failed',
    status,
    stdout: result.stdout || '',
    stderr: result.stderr || '',
    error: result.error,
  };
}

function main() {
  const opts = parseArgs(process.argv);
  if (opts.help) {
    console.log(`Usage: apply-geoworker-patches.js --root <TranslineGeoWorker> [--platform android|ios|all] [--dry-run] [--host <RN>]`);
    process.exit(0);
  }

  const geoRoot = resolveGeoWorkerRoot(opts.root);
  if (!geoRoot) {
    console.error(
      'ERROR: cannot find TranslineGeoWorker. Pass --root /path/to/TranslineGeoWorker or set GEOWORKER_ROOT.',
    );
    process.exit(1);
  }

  const platform = String(opts.platform || 'all').toLowerCase();
  if (!['android', 'ios', 'all'].includes(platform)) {
    console.error(`ERROR: invalid --platform ${opts.platform} (use android|ios|all)`);
    process.exit(1);
  }

  const hostCwd = path.resolve(opts.host);
  const connectDir = path.join(geoRoot, 'app', 'connect');
  const index = loadIndex(geoRoot);

  const patchBin = findPatchBin();
  if (!patchBin) {
    console.error('ERROR: `patch` command not found. Install GNU/BSD patch (e.g. /usr/bin/patch).');
    process.exit(1);
  }

  /** @type {string[]} */
  let relativePatches = [];
  if (platform === 'android' || platform === 'all') {
    relativePatches = relativePatches.concat(index.android || []);
  }
  if (platform === 'ios' || platform === 'all') {
    relativePatches = relativePatches.concat(index.ios || []);
  }

  console.log(`GeoWorker root: ${geoRoot}`);
  console.log(`Host RN cwd:    ${hostCwd}`);
  console.log(`Platform:       ${platform}${opts.dryRun ? ' (dry-run)' : ''}`);
  console.log(`patch binary:   ${patchBin}`);
  console.log('');

  const summary = { applied: 0, skipped: 0, failed: 0 };
  const failures = [];

  for (const rel of relativePatches) {
    // index paths are like 'patches/android/01-....patch' relative to app/connect
    const patchFile = path.join(connectDir, rel);
    const label = rel;
    if (!fs.existsSync(patchFile)) {
      console.error(`FAIL  ${label} — file missing: ${patchFile}`);
      summary.failed += 1;
      failures.push(label);
      continue;
    }

    const result = runPatch(hostCwd, patchFile, opts.dryRun, patchBin);
    if (result.error && result.error.code === 'ENOENT') {
      console.error('ERROR: `patch` command not found. Install GNU/BSD patch.');
      process.exit(1);
    }

    if (result.kind === 'applied') {
      console.log(`${opts.dryRun ? 'OK    ' : 'APPLY '} ${label}`);
      summary.applied += 1;
    } else if (result.kind === 'skipped') {
      console.log(`SKIP  ${label} (already applied or no-op)`);
      summary.skipped += 1;
    } else {
      console.error(`FAIL  ${label}`);
      if (result.stdout.trim()) console.error(result.stdout.trim());
      if (result.stderr.trim()) console.error(result.stderr.trim());
      summary.failed += 1;
      failures.push(label);
    }
  }

  console.log('');
  console.log(
    `Summary: applied=${summary.applied} skipped=${summary.skipped} failed=${summary.failed}`,
  );

  if (summary.failed > 0) {
    console.error('');
    console.error(
      'Some patches failed (RN layout/version mismatch). Apply manually from:',
    );
    console.error(`  ${path.join(connectDir, 'templates', 'android', 'INTEGRATION.md')}`);
    console.error(`  ${path.join(connectDir, 'templates', 'ios', 'INTEGRATION.md')}`);
    console.error('Do not force hunks — edit host files by hand.');
    process.exit(1);
  }

  process.exit(0);
}

main();
