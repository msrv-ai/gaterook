#!/usr/bin/env node
// Container-side build: the host launcher supplies an immutable source archive and validated Windows inputs.
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chownSync, copyFileSync, cpSync, readFileSync, readdirSync, statSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { createRequire } from 'node:module';
import { stage } from './stage.mjs';
import { validateWindowsInputs } from './windows-inputs.mjs';

const root = '/project';
const inputs = '/windows-inputs';
const output = '/output';
const build = JSON.parse(readFileSync('/input/build.json', 'utf8'));
const manifest = validateWindowsInputs(inputs, { revision: build.applicationRevision, version: build.version });
if (process.platform !== 'linux' || process.arch !== 'x64' || process.cwd() !== root) {
  throw new Error('Use npm run dist:win:docker to run this script in the Linux amd64 Wine container.');
}
if (readdirSync(output).length) throw new Error('Output directory is not empty.');

function run(command, args, cwd = root) {
  const result = spawnSync(command, args, { cwd, stdio: 'inherit' });
  if (result.error || result.status !== 0) throw new Error(`${command} failed: ${result.error?.message ?? result.status}`);
}

run('npm', ['ci', '--no-audit', '--no-fund']);
run('npm', ['ci', '--prefix', 'design', '--no-audit', '--no-fund']);
run('npm', ['run', 'build', '--prefix', 'design']);
run('npm', ['run', 'build', '-w', 'ui']);
run(process.execPath, ['desktop/scripts/build.mjs', '--release']);
await stage({ out: join(root, 'desktop/stage'), platform: 'windows-x64',
  core: join(inputs, 'core/gaterook-core.exe'), jre: join(inputs, 'jre'), jar: false, skipCloudflared: false }, {
  pluginHost: join(inputs, 'plugin-host/plugin-host.jar'),
  bridgeNative: join(inputs, 'bridge/gw-bridge.exe'), bridgeJar: join(inputs, 'bridge/gw-bridge.jar'),
});
// Keep all native runtime libraries supplied with the Windows core, not only SQLite.
cpSync(join(inputs, 'core'), join(root, 'desktop/stage/core'), { recursive: true });
cpSync(join(inputs, 'bridge'), join(root, 'desktop/stage/bridge'), { recursive: true });
copyFileSync(join(inputs, 'native/gaterook-unlock.exe'), join(root, 'desktop/dist/native/gaterook-unlock.exe'));

const require = createRequire(join(root, 'desktop/package.json'));
const builder = require.resolve('electron-builder/cli.js');
run(process.execPath, [builder, '--config', 'electron-builder.yml', '--win', 'nsis', '--x64', '--publish', 'never',
  '-c.win.signExecutable=false',
  '-c.directories.output=/project/desktop/release/windows-x64',
  '-c.artifactName=GateRook-${version}-win-${arch}.${ext}'], join(root, 'desktop'));

const release = join(root, 'desktop/release/windows-x64');
const installer = `GateRook-${build.version}-win-x64.exe`;
const names = [installer, `${installer}.blockmap`, 'latest.yml'];
const { load } = require('js-yaml');
const metadata = load(readFileSync(join(release, 'latest.yml'), 'utf8'));
const bytes = readFileSync(join(release, installer));
const sha512 = createHash('sha512').update(bytes).digest('base64');
if (metadata.version !== build.version || metadata.path !== installer || metadata.sha512 !== sha512
  || !Array.isArray(metadata.files) || metadata.files.length !== 1
  || metadata.files[0].url !== installer || metadata.files[0].size !== bytes.length || metadata.files[0].sha512 !== sha512) {
  throw new Error('Updater metadata does not match the generated Windows installer.');
}
for (const name of names) {
  if (!statSync(join(release, name)).isFile() || statSync(join(release, name)).size === 0) throw new Error(`Missing artifact: ${name}`);
}
const checksums = names.map((name) => `${createHash('sha256').update(readFileSync(join(release, name))).digest('hex')}  ${name}`);
// Export only after the complete file set and updater hashes have passed validation.
for (const name of names) copyFileSync(join(release, name), join(output, name));
writeFileSync(join(output, 'SHA256SUMS'), `${checksums.join('\n')}\n`);
writeFileSync(join(output, 'build-info.json'), `${JSON.stringify({ schemaVersion: 1,
  applicationRevision: build.applicationRevision, version: build.version, platform: manifest.platform,
  image: build.image, imageId: build.imageId, imageDigests: build.imageDigests,
  node: process.version, npm: JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).packageManager,
  windowsInputsSha256: createHash('sha256').update(readFileSync(join(inputs, 'windows-inputs.json'))).digest('hex'),
  signing: 'unsigned', installationSmokeTest: 'pending' }, null, 2)}\n`);
for (const name of readdirSync(output)) chownSync(join(output, name), build.uid, build.gid);
console.log('Windows x64 installer and updater artifacts created; installation and runtime acceptance are pending.');
