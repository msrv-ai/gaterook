import { chmod, lstat, readdir, writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { getAppUpdatePublishConfiguration } from 'app-builder-lib/out/publish/PublishManager.js';
import { serializeToYaml } from 'builder-util';
import { verifyPackagedUi } from './verify-packaged-ui.mjs';

/** Squirrel removes quarantine attributes after quitting; read-only JRE notices make that step fail. */
async function makeOwnerWritable(path) {
  const entry = await lstat(path);
  // Preserve links and never change their targets, which may be outside the packaged resources.
  if (entry.isSymbolicLink()) return;
  if (!entry.isFile() && !entry.isDirectory()) return;
  if (!(entry.mode & 0o200)) await chmod(path, (entry.mode & 0o7777) | 0o200);
  if (entry.isDirectory()) {
    for (const name of await readdir(path)) await makeOwnerWritable(join(path, name));
  }
}

/** Validate Windows UI payloads and prepare macOS update resources before signing, including directory-only builds. */
export default async function afterPack(context) {
  if (context.electronPlatformName === 'win32') {
    await verifyPackagedUi(context.packager.getResourcesDir(context.appOutDir),
      join(context.packager.info.appDir, 'stage', 'ui'));
    return;
  }
  if (context.electronPlatformName !== 'darwin') return;
  const resources = context.packager.getResourcesDir(context.appOutDir);
  await makeOwnerWritable(resources);
  // electron-builder skips this file for --dir, and --prepackaged cannot add it to a signed app later.
  // Use its resolver so platform overrides, disabled publishing and updater cache names stay consistent.
  const config = await getAppUpdatePublishConfiguration(context.packager, null, context.arch, true);
  if (config === null) return;
  await writeFile(join(resources, 'app-update.yml'), serializeToYaml(config));
}
