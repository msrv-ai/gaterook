#!/usr/bin/env node
// Package a committed application revision with matching Windows native inputs in Docker/Wine.
import { execFileSync, spawnSync } from 'node:child_process';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, rmSync, writeFileSync } from 'node:fs';
import { dirname, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { validateWindowsInputs } from './windows-inputs.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const help = `Usage: npm run dist:win:docker -- [options]
  --inputs <dir>  Windows native input bundle (default: target/windows-inputs)
  --out <dir>     New or empty output directory (default: desktop/release/windows-x64)
  --image <ref>   Wine builder image (default: electronuserland/builder:wine)
  --dry-run      Print the Docker plan without downloading or building
  --help         Show this help

Prepare inputs on Windows with npm run prepare:win, using the same committed source.
The local installer is unsigned. Nothing is uploaded or published.`;

export function parseArgs(argv, base = root) {
  const options = { inputs: join(base, 'target/windows-inputs'), out: join(base, 'desktop/release/windows-x64'),
    image: 'electronuserland/builder:wine', dryRun: false, help: false };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '--help') options.help = true;
    else if (arg === '--dry-run') options.dryRun = true;
    else if (['--inputs', '--out', '--image'].includes(arg)) {
      const value = argv[++i];
      if (!value || value.startsWith('--')) throw new Error(`${arg} needs a value`);
      options[arg.slice(2)] = arg === '--image' ? value : resolve(value);
    } else throw new Error(`Unknown argument: ${arg}`);
  }
  if (!/^[a-zA-Z0-9][a-zA-Z0-9._/:@-]*$/.test(options.image)) throw new Error('Invalid Docker image reference');
  return options;
}

function run(file, args, options = {}) {
  const result = spawnSync(file, args, { cwd: root, stdio: 'inherit', ...options });
  if (result.error || result.status !== 0) throw new Error(`${file} failed: ${result.error?.message ?? result.status}`);
}

function git(...args) {
  return execFileSync('git', args, { cwd: root, encoding: 'utf8' }).trim();
}

function contains(parent, child) {
  const path = relative(parent, child);
  return path === '' || (!path.startsWith(`..${sep}`) && path !== '..' && !path.startsWith(sep));
}

// Resolve existing parents too: a symlink must not bypass the overlap checks.
function actualPath(path) {
  return existsSync(path) ? realpathSync(path) : join(actualPath(dirname(path)), path.slice(dirname(path).length + 1));
}

export function validateOutput(out, inputs, source = root) {
  const output = actualPath(out);
  const input = actualPath(inputs);
  if (contains(output, actualPath(source)) || contains(output, input) || contains(input, output)) {
    throw new Error('Output must not overlap the input bundle or contain the source checkout');
  }
  if (existsSync(output) && readdirSync(output).length) throw new Error(`Output must be new or empty: ${output}`);
}

export function dockerArgs({ inputs, out, image }, snapshot) {
  const mount = (source, target, readOnly = false) => {
    if (source.includes(',')) throw new Error('Docker bind mount paths cannot contain commas');
    return ['--mount', `type=bind,source=${source},target=${target}${readOnly ? ',readonly' : ''}`];
  };
  return ['run', '--rm', '--platform', 'linux/amd64', '--workdir', '/project',
    ...mount(snapshot, '/input', true), ...mount(inputs, '/windows-inputs', true), ...mount(out, '/output'),
    '--volume', 'gaterook-windows-npm:/root/.npm',
    '--volume', 'gaterook-windows-electron:/root/.cache/electron',
    '--volume', 'gaterook-windows-builder:/root/.cache/electron-builder',
    '--volume', 'gaterook-windows-cloudflared:/root/.cache/mcp-gateway/cloudflared',
    '--env', 'ELECTRON_CACHE=/root/.cache/electron',
    '--env', 'ELECTRON_BUILDER_CACHE=/root/.cache/electron-builder',
    '--env', 'ELECTRON_SKIP_BINARY_DOWNLOAD=1', '--env', 'CSC_IDENTITY_AUTO_DISCOVERY=false',
    '--env', 'CI=true', '--entrypoint', '/bin/bash', image, '-c',
    'set -euo pipefail; tar -xf /input/source.tar -C /project; exec bash scripts/windows-container.sh'];
}

export function main(argv = process.argv.slice(2)) {
  const options = parseArgs(argv);
  if (options.help) { console.log(help); return; }
  if (process.platform === 'win32') throw new Error('Run Docker packaging on macOS or Linux; prepare inputs on Windows.');
  if (options.dryRun) {
    console.log(JSON.stringify({ command: 'docker', args: dockerArgs(options, '<source-snapshot>'),
      checks: 'A real run requires clean committed source, matching validated Windows inputs, and empty output.' }, null, 2));
    return;
  }
  const revision = git('rev-parse', 'HEAD');
  if (git('status', '--porcelain', '--untracked-files=no')) throw new Error('Commit tracked source changes before building.');
  git('cat-file', '-e', 'HEAD:scripts/windows-container.sh');
  const { version } = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
  if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('This local Windows workflow requires a stable x.y.z version for latest.yml.');
  if (JSON.parse(readFileSync(join(root, 'desktop/package.json'), 'utf8')).version !== version) {
    throw new Error('Root and desktop application versions differ.');
  }
  validateWindowsInputs(options.inputs, { revision, version });
  validateOutput(options.out, options.inputs);
  run('docker', ['info', '--format', '{{.OSType}}']);
  run('docker', ['pull', '--platform', 'linux/amd64', options.image]);
  const image = JSON.parse(execFileSync('docker', ['image', 'inspect', options.image], { encoding: 'utf8' }))[0];
  if (image.Os !== 'linux' || image.Architecture !== 'amd64') throw new Error('The Wine image must be linux/amd64.');
  mkdirSync(join(root, 'target'), { recursive: true });
  const snapshot = mkdtempSync(join(root, 'target', 'windows-docker-'));
  try {
    git('archive', '--format=tar', `--output=${join(snapshot, 'source.tar')}`, revision);
    writeFileSync(join(snapshot, 'build.json'), JSON.stringify({ applicationRevision: revision, version,
      image: options.image, imageId: image.Id, imageDigests: image.RepoDigests ?? [],
      uid: process.getuid(), gid: process.getgid() }));
    mkdirSync(options.out, { recursive: true });
    // Use the inspected immutable image ID even when the requested tag moves during the build.
    run('docker', dockerArgs({ ...options, inputs: realpathSync(options.inputs), out: realpathSync(options.out), image: image.Id }, snapshot));
    console.log(`Windows x64 local artifacts: ${options.out}`);
  } finally {
    rmSync(snapshot, { recursive: true, force: true });
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try { main(); } catch (error) { console.error(`windows-docker: ${error.message}`); process.exitCode = 1; }
}
