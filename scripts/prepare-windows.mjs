#!/usr/bin/env node
// Compile Windows-native resources once, then transfer this input bundle to the Wine packager.
import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, lstatSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, renameSync, rmSync } from 'node:fs';
import { dirname, isAbsolute, join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { writeWindowsInputsManifest } from './windows-inputs.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');

function run(command, args, { capture = false, batch = false } = {}) {
  console.log(`windows-inputs: ${command} ${args.join(' ')}`);
  // Only fixed npm/Maven arguments use cmd.exe; paths supplied by the caller never pass through a shell.
  const result = spawnSync(command, args, {
    cwd: root, encoding: 'utf8', stdio: capture ? 'pipe' : 'inherit', shell: batch,
  });
  if (result.error || result.status !== 0) {
    throw new Error(`${command} failed: ${result.error?.message ?? result.stderr?.trim() ?? `exit ${result.status}`}`);
  }
  return result.stdout?.trim();
}

function cleanRevision(expected) {
  const status = run('git', ['status', '--porcelain', '--untracked-files=all'], { capture: true });
  if (status) throw new Error('Preparing Windows inputs requires clean committed source, including no nonignored untracked files.');
  const revision = run('git', ['rev-parse', 'HEAD'], { capture: true });
  if (!/^[0-9a-f]{40}$/.test(revision)) throw new Error('Could not identify the application revision.');
  if (expected && revision !== expected) throw new Error('The application revision changed during the build.');
  return revision;
}

/** Native Image can emit runtime DLLs beside the executable as well as in native-libs/. */
export function copyRuntimeLibraries(directories, destination) {
  mkdirSync(destination, { recursive: true });
  const copied = new Map();
  for (const directory of directories) {
    if (!existsSync(directory)) continue;
    for (const name of readdirSync(directory).sort()) {
      if (!name.toLowerCase().endsWith('.dll')) continue;
      const source = join(directory, name);
      if (!lstatSync(source).isFile() || lstatSync(source).isSymbolicLink()) {
        throw new Error(`Native runtime DLL must be a regular file: ${source}`);
      }
      const key = name.toLowerCase();
      if (copied.has(key)) {
        if (!readFileSync(source).equals(readFileSync(copied.get(key)))) {
          throw new Error(`Conflicting native runtime DLLs named ${name}`);
        }
      } else {
        const target = join(destination, name);
        cpSync(source, target);
        copied.set(key, target);
      }
    }
  }
}

export function prepareWindows(out = join(root, 'target/windows-inputs')) {
  if (process.platform !== 'win32' || process.arch !== 'x64') {
    throw new Error('Prepare native inputs on Windows x64 with GraalVM JDK 25, Maven, and a Visual Studio x64 Developer Command Prompt.');
  }
  const pinnedNode = readFileSync(join(root, '.nvmrc'), 'utf8').trim().replace(/^v/, '');
  if (process.versions.node !== pinnedNode) throw new Error(`Use Node.js ${pinnedNode} from .nvmrc; found ${process.versions.node}.`);
  out = resolve(out);
  if (existsSync(out)) throw new Error(`Output already exists; select a new directory: ${out}`);
  const localOutput = relative(root, out);
  if (!isAbsolute(localOutput) && localOutput !== '..' && !localOutput.startsWith(`..${sep}`)) {
    const ignored = spawnSync('git', ['check-ignore', '-q', '--', join(out, '.windows-inputs-output')], { cwd: root });
    if (ignored.error || ignored.status !== 0) {
      throw new Error('Choose an ignored output directory such as target/windows-inputs, or a path outside the checkout.');
    }
  }
  const revision = cleanRevision();
  const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version;
  if (!/^\d+\.\d+\.\d+$/.test(version)) throw new Error('Windows packaging requires a stable x.y.z version for latest.yml.');
  if (JSON.parse(readFileSync(join(root, 'desktop/package.json'), 'utf8')).version !== version) {
    throw new Error('Root and desktop application versions differ.');
  }
  run('npm.cmd', ['ci'], { batch: true });
  run('npm.cmd', ['ci', '--prefix', 'design'], { batch: true });
  run('npm.cmd', ['run', 'build', '--prefix', 'design'], { batch: true });
  run('mvn.cmd', ['-B', '-ntp', '-pl', 'app,bridge-cli,plugin-host', '-am', '-Pnative,release', '-DskipTests', 'clean', 'package'], { batch: true });
  const jre = join(root, 'target/plugin-host-jre');
  run(process.execPath, [join(root, 'scripts/jlink.mjs'), jre]);
  run(process.execPath, [join(root, 'desktop/scripts/build-native-unlock.mjs')]);
  cleanRevision(revision);

  mkdirSync(dirname(out), { recursive: true });
  const temporary = mkdtempSync(join(dirname(out), '.windows-inputs-'));
  const copy = (from, to) => {
    mkdirSync(dirname(join(temporary, to)), { recursive: true });
    cpSync(from, join(temporary, to), { recursive: true, verbatimSymlinks: true });
  };
  try {
    copy(join(root, 'app/target/gaterook-core.exe'), 'core/gaterook-core.exe');
    copyRuntimeLibraries([join(root, 'app/target'), join(root, 'app/target/native-libs')], join(temporary, 'core'));
    copy(jre, 'jre');
    copy(join(root, 'plugin-host/target/plugin-host.jar'), 'plugin-host/plugin-host.jar');
    copy(join(root, 'bridge-cli/target/gw-bridge.exe'), 'bridge/gw-bridge.exe');
    copy(join(root, 'bridge-cli/target/gw-bridge.jar'), 'bridge/gw-bridge.jar');
    copyRuntimeLibraries([join(root, 'bridge-cli/target'), join(root, 'bridge-cli/target/native-libs')], join(temporary, 'bridge'));
    copy(join(root, 'desktop/dist/native/gaterook-unlock.exe'), 'native/gaterook-unlock.exe');
    writeWindowsInputsManifest(temporary, { revision, version });
    cleanRevision(revision);
    if (existsSync(out)) throw new Error(`Output appeared during the build; refusing to replace it: ${out}`);
    renameSync(temporary, out);
    console.log(`windows-inputs: prepared ${out} for ${version} at ${revision}`);
  } finally { rmSync(temporary, { recursive: true, force: true }); }
}

if (process.argv[1] && pathToFileURL(resolve(process.argv[1])).href === import.meta.url) {
  try {
    const args = process.argv.slice(2);
    if (args.length === 1 && ['--help', '-h'].includes(args[0])) {
      console.log('Usage: node scripts/prepare-windows.mjs [--out <new directory>]\nRequires Windows x64, GraalVM JDK 25, Maven, the pinned Node.js, and Visual Studio C++/Windows 11 SDK.\nThe source must be committed and clean, with no nonignored untracked files. The output directory must not exist.');
    } else {
      if (args.length && (args.length !== 2 || args[0] !== '--out' || !args[1])) {
        throw new Error('Usage: node scripts/prepare-windows.mjs [--out <new directory>]');
      }
      prepareWindows(args[1]);
    }
  } catch (error) {
    console.error(`windows-inputs: ${error.message}`);
    process.exitCode = 1;
  }
}
