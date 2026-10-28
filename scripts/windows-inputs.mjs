// Validate the Windows-native payload before Linux/Wine packages the Electron application.
import { createHash } from 'node:crypto';
import { closeSync, lstatSync, openSync, readFileSync, readSync, readdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { JRE_MODULES } from './jlink.mjs';

const manifestName = 'windows-inputs.json';
const required = [
  'core/gaterook-core.exe', 'core/sqlitejdbc.dll',
  'jre/bin/java.exe', 'jre/bin/server/jvm.dll', 'jre/lib/modules', 'jre/release',
  'plugin-host/plugin-host.jar', 'bridge/gw-bridge.exe', 'bridge/gw-bridge.jar',
  'native/gaterook-unlock.exe',
];

function hashFile(file) {
  const hash = createHash('sha256');
  const buffer = Buffer.alloc(1024 * 1024);
  const fd = openSync(file, 'r');
  try {
    let length;
    while ((length = readSync(fd, buffer, 0, buffer.length, null)) > 0) hash.update(buffer.subarray(0, length));
  } finally { closeSync(fd); }
  return hash.digest('hex');
}

function payloadFiles(directory, prefix = '') {
  const stat = lstatSync(directory);
  if (!stat.isDirectory() || stat.isSymbolicLink()) throw new Error(`Not a regular directory: ${directory}`);
  const files = {};
  for (const name of readdirSync(directory).sort()) {
    const relative = prefix ? `${prefix}/${name}` : name;
    const path = join(directory, name);
    const entry = lstatSync(path);
    if (entry.isSymbolicLink()) throw new Error(`Symlinks are not allowed in Windows inputs: ${relative}`);
    if (entry.isDirectory()) Object.assign(files, payloadFiles(path, relative));
    else if (!entry.isFile()) throw new Error(`Not a regular payload file: ${relative}`);
    else if (relative !== manifestName) files[relative] = hashFile(path);
  }
  return files;
}

function validatePath(path) {
  if (typeof path !== 'string' || /[\\:\u0000-\u001f]/.test(path)
      || path.split('/').some((part) => !part || part === '.' || part === '..')
      || !/^(core|jre|plugin-host|bridge|native)\//.test(path)) {
    throw new Error(`Invalid Windows payload path: ${path}`);
  }
}

/** Reject non-Windows or non-x64 binaries without executing untrusted inputs. */
function validatePe(file) {
  const fd = openSync(file, 'r');
  const dos = Buffer.alloc(64);
  const pe = Buffer.alloc(26);
  try {
    if (readSync(fd, dos, 0, dos.length, 0) !== dos.length || dos.toString('ascii', 0, 2) !== 'MZ') {
      throw new Error(`Not a Windows PE executable: ${file}`);
    }
    const offset = dos.readUInt32LE(60);
    if (offset < 64 || readSync(fd, pe, 0, pe.length, offset) !== pe.length
        || !pe.subarray(0, 4).equals(Buffer.from('PE\0\0'))
        || pe.readUInt16LE(4) !== 0x8664 || pe.readUInt16LE(24) !== 0x20b) {
      throw new Error(`Expected a Windows x64 PE32+ executable: ${file}`);
    }
  } finally { closeSync(fd); }
}

function validateRuntime(directory) {
  const release = readFileSync(join(directory, 'jre/release'), 'utf8');
  const properties = Object.fromEntries([...release.matchAll(/^([A-Z_]+)="([^"]*)"\r?$/gm)]
    .map((match) => [match[1], match[2]]));
  if (!/^25(?:[.+-]|$)/.test(properties.JAVA_VERSION ?? '')
      || !/^25(?:[.+-]|$)/.test(properties.JAVA_RUNTIME_VERSION ?? '')
      || properties.OS_NAME !== 'Windows' || !['amd64', 'x86_64', 'x64'].includes(properties.OS_ARCH)) {
    throw new Error('The bundled JRE must identify Windows x64 and Java 25 with JAVA_RUNTIME_VERSION in jre/release.');
  }
  const modules = new Set((properties.MODULES ?? '').split(/\s+/));
  const missing = JRE_MODULES.filter((module) => !modules.has(module));
  if (missing.length) throw new Error(`The bundled JRE is missing required modules: ${missing.join(', ')}`);
  if (!lstatSync(join(directory, 'jre/lib/modules')).size) throw new Error('The bundled JRE module image is empty.');
}

/** Check identity, complete file hashes, native architecture, and the plugin host runtime. */
export function validateWindowsInputs(directory, { revision, version } = {}) {
  directory = resolve(directory);
  const files = payloadFiles(directory);
  const manifestFile = join(directory, manifestName);
  if (!lstatSync(manifestFile).isFile()) throw new Error(`Missing regular ${manifestName}`);
  const manifest = JSON.parse(readFileSync(manifestFile, 'utf8'));
  if (manifest.schemaVersion !== 1 || manifest.platform !== 'windows-x64'
      || !/^[0-9a-f]{40}$/.test(manifest.applicationRevision ?? '')
      || !/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(manifest.version ?? '')
      || !manifest.files || Array.isArray(manifest.files) || typeof manifest.files !== 'object') {
    throw new Error('Invalid Windows input manifest schema, platform, or application identity.');
  }
  if (revision !== undefined && manifest.applicationRevision !== revision) {
    throw new Error('Windows inputs were built from a different application revision.');
  }
  if (version !== undefined && manifest.version !== version) throw new Error('Windows input version does not match the application.');
  const names = Object.keys(manifest.files).sort();
  const folded = new Set();
  for (const name of names) {
    validatePath(name);
    if (folded.has(name.toLowerCase())) throw new Error(`Case-colliding Windows payload path: ${name}`);
    folded.add(name.toLowerCase());
    if (!/^[0-9a-f]{64}$/.test(manifest.files[name])) throw new Error(`Invalid SHA-256 for ${name}`);
  }
  if (JSON.stringify(names) !== JSON.stringify(Object.keys(files).sort())) {
    throw new Error('Windows input files do not match the manifest (missing or extra payload).');
  }
  for (const name of required) {
    if (!Object.hasOwn(files, name)) throw new Error(`Missing required Windows input: ${name}`);
  }
  for (const name of names) {
    if (files[name] !== manifest.files[name]) throw new Error(`Windows input SHA-256 mismatch: ${name}`);
    if (/\.(exe|dll)$/i.test(name)) validatePe(join(directory, name));
  }
  for (const name of ['plugin-host/plugin-host.jar', 'bridge/gw-bridge.jar']) {
    const fd = openSync(join(directory, name), 'r');
    const magic = Buffer.alloc(4);
    try { readSync(fd, magic, 0, 4, 0); } finally { closeSync(fd); }
    if (!magic.equals(Buffer.from([0x50, 0x4b, 0x03, 0x04]))) throw new Error(`Not a JAR archive: ${name}`);
  }
  validateRuntime(directory);
  return manifest;
}

/** Write the manifest only after a preparation step has copied the complete native payload. */
export function writeWindowsInputsManifest(directory, { revision, version }) {
  const manifest = {
    schemaVersion: 1, platform: 'windows-x64', applicationRevision: revision, version,
    files: payloadFiles(directory),
  };
  writeFileSync(join(directory, manifestName), `${JSON.stringify(manifest, null, 2)}\n`);
  return validateWindowsInputs(directory, { revision, version });
}
