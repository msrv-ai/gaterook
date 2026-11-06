import { lstat } from 'node:fs/promises';
import { isAbsolute } from 'node:path';
import type { OpenDialogOptions, OpenDialogReturnValue } from 'electron';

/** A Vault token file the person chose: the one-time token the core redeems, and the path to show in the form. */
export interface VaultTokenFile {
  readonly token: string;
  readonly path: string;
}

/**
 * The token file of a Vault that signs in with one (a Vault Agent sink, a file a deployment tool renews). Main shows
 * the native dialog and registers the chosen path with the core (`/internal/files/grant`, purpose `vault-token`); the
 * renderer gets the grant token to send with the store and the path only to display it. Nothing here reads the file:
 * the core reads it each time it signs in, and refuses links and anything that isn't one token.
 */
export async function chooseVaultTokenPath(
  showDialog: (options: OpenDialogOptions) => Promise<OpenDialogReturnValue>,
  grant: (path: string) => Promise<string>,
): Promise<VaultTokenFile | null> {
  const result = await showDialog({
    title: 'Choose Vault token file',
    buttonLabel: 'Choose file',
    properties: ['openFile', 'showHiddenFiles'],
  });
  if (result.canceled || !result.filePaths.length) return null;
  const path = result.filePaths[0]!;
  const stat = isAbsolute(path) ? await lstat(path).catch(() => null) : null;
  if (!stat?.isFile()) {
    throw new Error('Choose a file that holds the token itself, not a folder or a link.');
  }
  return { token: await grant(path), path };
}
