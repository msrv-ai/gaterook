import { writeFile } from 'node:fs/promises';
import { join } from 'node:path';
import { getAppUpdatePublishConfiguration } from 'app-builder-lib/out/publish/PublishManager.js';
import { serializeToYaml } from 'builder-util';

/** Include the update source before macOS signing, including directory-only packaging. */
export default async function afterPack(context) {
  if (context.electronPlatformName !== 'darwin') return;
  // electron-builder skips this file for --dir, and --prepackaged cannot add it to a signed app later.
  // Use its resolver so platform overrides, disabled publishing and updater cache names stay consistent.
  const config = await getAppUpdatePublishConfiguration(context.packager, null, context.arch, true);
  if (config === null) return;
  await writeFile(join(context.packager.getResourcesDir(context.appOutDir), 'app-update.yml'), serializeToYaml(config));
}
