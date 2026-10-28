import { stat } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import type { OpenDialogOptions, OpenDialogReturnValue } from 'electron';

/** Returns only the path explicitly chosen in the native dialog; it never reads the key. */
export async function choosePrivateKeyPath(
  showDialog: (options: OpenDialogOptions) => Promise<OpenDialogReturnValue>,
): Promise<string | null> {
  const result = await showDialog({
    title: 'Choose SSH private key',
    buttonLabel: 'Choose key',
    properties: ['openFile', 'showHiddenFiles'],
  });
  if (result.canceled || !result.filePaths.length) return null;
  const path = result.filePaths[0]!;
  if (!isAbsolute(path) || !(await stat(path).catch(() => null))?.isFile()) {
    throw new Error('Choose an existing private key file, not a folder.');
  }
  return path;
}
