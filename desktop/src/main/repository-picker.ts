import { stat } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import type { OpenDialogOptions, OpenDialogReturnValue } from 'electron';

/** Selects the analysis scope. No repository files are read or executed by GateRook. */
export async function chooseRepositoryPath(
  showDialog: (options: OpenDialogOptions) => Promise<OpenDialogReturnValue>,
): Promise<string | null> {
  const result = await showDialog({
    title: 'Choose repository to analyze',
    buttonLabel: 'Choose repository',
    properties: ['openDirectory'],
  });
  if (result.canceled || !result.filePaths.length) return null;
  const path = result.filePaths[0]!;
  if (!isAbsolute(path) || path.length > 2000 || /[\u0000-\u001f\u007f]/.test(path)
    || !(await stat(path).catch(() => null))?.isDirectory()) {
    throw new Error('Choose an existing repository folder.');
  }
  return path;
}
