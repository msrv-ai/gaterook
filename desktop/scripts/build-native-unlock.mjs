// Build the small OS-authentication helper without Node addons or runtime package dependencies.
// Cache by source and compiler identity: ordinary UI/main rebuilds must not change its Keychain ACL identity.
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, renameSync, rmSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

const script = fileURLToPath(import.meta.url);
const defaultRoot = join(dirname(script), '..');

function run(file, args, options = {}) {
  const result = spawnSync(file, args, { encoding: 'utf8', ...options });
  if (result.error || result.status !== 0) {
    const prerequisite = process.platform === 'darwin'
      ? 'Install Xcode Command Line Tools with Swift.'
      : 'Use a Visual Studio Developer Command Prompt with the C++ build tools and Windows 11 SDK.';
    throw new Error(`Could not build the native unlock helper. ${prerequisite}\n${result.error?.message ?? result.stderr?.trim() ?? ''}`);
  }
  return result.stdout;
}

export function buildNativeUnlock({ root = defaultRoot, dist = join(root, 'dist') } = {}) {
  const output = join(dist, 'native');
  mkdirSync(output, { recursive: true });
  if (process.platform !== 'darwin' && process.platform !== 'win32') return null;
  const mac = process.platform === 'darwin';
  if (!['arm64', 'x64'].includes(process.arch)) throw new Error('Native unlock supports arm64 and x64 hosts.');
  const name = `gaterook-unlock${mac ? '' : '.exe'}`;
  const source = join(root, 'native', mac ? 'unlock-macos.swift' : 'unlock-windows.cpp');
  const info = join(root, 'native', 'Info.plist');
  const compiler = mac ? run('/usr/bin/xcrun', ['swiftc', '--version'])
    : `${process.env.VCToolsVersion ?? ''}:${process.env.WindowsSDKVersion ?? ''}:${process.env.VSCMD_ARG_TGT_ARCH ?? ''}`;
  const fingerprint = createHash('sha256').update(readFileSync(source)).update(readFileSync(script))
    .update(mac ? readFileSync(info) : '').update(`${process.platform}:${process.arch}:${compiler}`).digest('hex');
  const cache = join(root, '.dev', 'native-unlock');
  const destination = join(cache, fingerprint);
  const executable = join(destination, name);
  if (!existsSync(executable)) {
    mkdirSync(cache, { recursive: true });
    const temporary = mkdtempSync(join(cache, '.build-'));
    const built = join(temporary, name);
    try {
      if (mac) {
        run('/usr/bin/xcrun', ['swiftc', '-swift-version', '5', '-O', '-target',
          `${process.arch === 'arm64' ? 'arm64' : 'x86_64'}-apple-macos12.0`,
          '-framework', 'Foundation', '-framework', 'Security', '-framework', 'LocalAuthentication',
          '-Xlinker', '-sectcreate', '-Xlinker', '__TEXT', '-Xlinker', '__info_plist', '-Xlinker', info,
          source, '-o', built]);
        run('/usr/bin/codesign', ['--force', '--sign', '-', '--identifier', 'ai.msrv.gaterook.unlock', '--timestamp=none', built]);
      } else {
        // cl.exe comes from the active Developer Command Prompt. No PowerShell or shell parses the arguments.
        const target = process.env.VSCMD_ARG_TGT_ARCH;
        if (target && target !== (process.arch === 'arm64' ? 'arm64' : 'x64')) {
          throw new Error('Select the Visual Studio compiler architecture matching the Node.js architecture.');
        }
        run('cl.exe', ['/nologo', '/std:c++20', '/EHsc', '/O2', '/MT', '/DUNICODE', '/D_UNICODE',
          '/DWIN32_LEAN_AND_MEAN', '/DNOMINMAX', source, `/Fe:${built}`, `/Fo:${join(temporary, 'unlock.obj')}`,
          '/link', 'windowsapp.lib', 'user32.lib', '/DYNAMICBASE', '/NXCOMPAT'], { cwd: temporary });
      }
      try { renameSync(temporary, destination); }
      catch (error) { if (!existsSync(executable)) throw error; }
    } finally {
      rmSync(temporary, { recursive: true, force: true });
    }
  }
  const result = join(output, name);
  copyFileSync(executable, result);
  return result;
}

if (process.argv[1] && pathToFileURL(process.argv[1]).href === import.meta.url) {
  buildNativeUnlock();
}
