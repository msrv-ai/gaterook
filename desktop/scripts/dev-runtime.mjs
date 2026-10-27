// A macOS development runtime with the product's Dock/menu name. Keep the executable named Electron: Electron
// uses that basename to distinguish a development launch from an installed application.
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { constants, cpSync, existsSync, mkdirSync, mkdtempSync, readFileSync, realpathSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import { basename, dirname, join } from 'node:path';

const PRODUCT = 'GateRook';
const FORMAT = 1;

function run(command, args) {
  const result = spawnSync(command, args, { encoding: 'utf8' });
  if (result.error || result.status !== 0) {
    throw new Error(`Could not prepare the macOS development runtime: ${result.error?.message ?? result.stderr.trim()}`);
  }
  return result.stdout;
}

function plist(file) {
  return JSON.parse(run('/usr/bin/plutil', ['-convert', 'json', '-o', '-', file]));
}

/**
 * Return a launchable Electron executable. macOS gets a cached, locally signed copy under the supplied ignored
 * cache directory; Windows and Linux use the original runtime. No installed dependency is modified.
 */
export function prepareDevRuntime(electron, cacheDir, { platform = process.platform } = {}) {
  if (platform !== 'darwin') return electron;
  const sourceExecutable = realpathSync(electron);
  const sourceBundle = dirname(dirname(dirname(sourceExecutable)));
  if (basename(sourceExecutable) !== 'Electron' || !sourceBundle.endsWith('.app')) {
    throw new Error('Expected the development Electron.app runtime with its original Electron executable.');
  }
  const sourceInfo = join(sourceBundle, 'Contents', 'Info.plist');
  const info = plist(sourceInfo);
  if (info.CFBundleExecutable !== 'Electron') {
    throw new Error('The development runtime must keep CFBundleExecutable set to Electron.');
  }
  // The dependency version and executable invalidate the cache when npm installs another Electron runtime.
  const fingerprint = createHash('sha256').update(JSON.stringify({ format: FORMAT, sourceExecutable, product: PRODUCT }))
    .update(readFileSync(sourceInfo)).update(readFileSync(sourceExecutable)).digest('hex');
  const destination = join(cacheDir, fingerprint.slice(0, 24));
  const bundle = join(destination, `${PRODUCT}.app`);
  const executable = join(bundle, 'Contents', 'MacOS', 'Electron');
  const marker = join(destination, 'runtime.json');
  const ready = () => {
    try {
      return JSON.parse(readFileSync(marker, 'utf8')).fingerprint === fingerprint && existsSync(executable);
    } catch {
      return false;
    }
  };
  if (ready()) return executable;
  if (existsSync(destination)) {
    throw new Error(`The development runtime cache is incomplete. Remove ${destination} and start again.`);
  }

  mkdirSync(cacheDir, { recursive: true });
  const temporary = mkdtempSync(join(cacheDir, '.prepare-'));
  try {
    const temporaryBundle = join(temporary, `${PRODUCT}.app`);
    // APFS can clone file contents; preserve framework symlinks instead of flattening the bundle.
    cpSync(sourceBundle, temporaryBundle, { recursive: true, verbatimSymlinks: true, preserveTimestamps: true,
      mode: constants.COPYFILE_FICLONE });
    const temporaryInfo = join(temporaryBundle, 'Contents', 'Info.plist');
    run('/usr/bin/plutil', ['-replace', 'CFBundleName', '-string', PRODUCT, temporaryInfo]);
    run('/usr/bin/plutil', ['-replace', 'CFBundleDisplayName', '-string', PRODUCT, temporaryInfo]);
    // Only the outer bundle metadata changed. Re-sign it ad hoc for local use; keep nested Electron binaries,
    // entitlements and helper names intact. This does not create a distributable signed release.
    run('/usr/bin/codesign', ['--force', '--sign', '-', '--preserve-metadata=identifier,entitlements,flags,runtime', temporaryBundle]);
    writeFileSync(join(temporary, 'runtime.json'), JSON.stringify({ fingerprint, sourceExecutable, product: PRODUCT }) + '\n');
    try {
      renameSync(temporary, destination);
    } catch (error) {
      // Two local launchers can prepare the same runtime at once. Use the first complete copy.
      if (!ready()) throw error;
    }
    return executable;
  } finally {
    rmSync(temporary, { recursive: true, force: true });
  }
}
