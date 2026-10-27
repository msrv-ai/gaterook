// Files and folders on the renderer's behalf, without ever taking a path from it (ARCHITECTURE.md §7.1 "Preload",
// §6): a save or open dialog in main whose path goes to the core as a one-time token; the `.mcpb` of a client link
// and the config file of a client, both looked up at the core by id and checked here; the logs and backups folders.
import { execFile } from 'node:child_process';
import { lstat, realpath } from 'node:fs/promises';
import { isAbsolute, join, sep } from 'node:path';
import { dialog, shell, type BrowserWindow } from 'electron';
import { callCore, callCoreOk } from './core-client.ts';
import { clientId, defaultFileName, filePurpose, linkId, openableConfig, revealableFolder, revealKind } from './file-purposes.ts';
import type { Logger } from './log.ts';
import type { CoreEndpoint } from './proxy.ts';

export interface FilesOptions {
  readonly home: string;
  readonly core: () => CoreEndpoint | null;
  readonly window: () => BrowserWindow | null;
  readonly log: Logger;
}

function requireCore(o: FilesOptions): CoreEndpoint {
  const core = o.core();
  if (!core) throw new Error('The core isn’t running. Wait for it to start, then try again.');
  return core;
}

/** saveFile / openFile: the dialog, then `/internal/files/grant`; the renderer gets the token or null (cancelled). */
export async function chooseFile(o: FilesOptions, purposeId: unknown, save: boolean): Promise<string | null> {
  const purpose = filePurpose(purposeId, save);
  if (!purpose) throw new Error(`Unknown file purpose ${String(purposeId).slice(0, 40)}`);
  const core = requireCore(o);
  const win = o.window();
  let path: string | undefined;
  if (save) {
    const name = defaultFileName(purpose);
    const options = { title: purpose.title, filters: purpose.filters.map((f) => ({ ...f, extensions: [...f.extensions] })),
      ...(name ? { defaultPath: name } : {}), properties: ['createDirectory' as const, 'showOverwriteConfirmation' as const] };
    const r = win ? await dialog.showSaveDialog(win, options) : await dialog.showSaveDialog(options);
    path = r.canceled ? undefined : r.filePath;
  } else {
    const options = { title: purpose.title, filters: purpose.filters.map((f) => ({ ...f, extensions: [...f.extensions] })),
      properties: ['openFile' as const] };
    const r = win ? await dialog.showOpenDialog(win, options) : await dialog.showOpenDialog(options);
    path = r.canceled ? undefined : r.filePaths[0];
  }
  if (!path) return null;
  const grant = await callCoreOk<{ token?: unknown }>(core, 'POST', '/internal/files/grant', { path, purpose: purpose.id });
  if (typeof grant.token !== 'string') throw new Error('The core gave no file token');
  return grant.token;
}

/** Claude Desktop's extension of a client link: the core names the file, main checks it is one of ours and opens it. */
export async function openMcpb(o: FilesOptions, id: unknown): Promise<void> {
  const link = linkId(id);
  if (!link) throw new Error('Unknown link');
  const core = requireCore(o);
  const { path } = await callCoreOk<{ path?: unknown }>(core, 'GET', `/internal/clients/links/${link}/mcpb`);
  if (typeof path !== 'string' || !isAbsolute(path)) throw new Error('The core gave no extension file');
  const dir = await realpath(join(o.home, 'clients', 'claude-desktop')).catch(() => null);
  const real = await realpath(path).catch(() => null);
  if (!real) throw new Error('The extension file is missing. Connect Claude Desktop again.');
  // A file the core built: not a folder (an app bundle is one), which opening would run.
  const file = (await lstat(real).catch(() => null))?.isFile() === true;
  if (!dir || !real.startsWith(dir + sep) || !real.endsWith('.mcpb') || !file) {
    throw new Error('The extension isn’t where GateRook builds them');
  }
  if (process.platform === 'darwin') {
    // Claude Desktop installs the extension it opens; `open -a` names it even when another app claims .mcpb.
    const opened = await new Promise<boolean>((resolve) => execFile('/usr/bin/open', ['-a', 'Claude', real], (e) => resolve(!e)));
    if (opened) return;
  }
  const error = await shell.openPath(real);
  if (error) throw new Error(`Couldn’t open the extension: ${error}`);
}

/** Open config on a client card: the path comes from the core's card of that client, never from the renderer. */
export async function openClientConfig(o: FilesOptions, id: unknown): Promise<void> {
  const client = clientId(id);
  if (!client) throw new Error('Unknown client');
  const core = requireCore(o);
  const answer = await callCore<{ clients?: { client?: unknown; configPath?: unknown }[] }>(core, 'GET', '/api/clients');
  if (answer.status !== 200) throw new Error(`The core answered HTTP ${answer.status}`);
  const card = (answer.body.clients ?? []).find((c) => c.client === client);
  const path = card?.configPath;
  if (typeof path !== 'string' || !isAbsolute(path)) throw new Error('This client has no config file yet');
  const real = await openableConfig(path);
  if (real === 'missing') throw new Error('This client has no config file yet');
  if (real === 'refused') throw new Error('The config file isn’t a JSON or TOML file, so GateRook won’t open it');
  const error = await shell.openPath(real);
  if (error) throw new Error(`Couldn’t open the config file: ${error}`);
}

/** Show the logs or backups folder of the gateway home. */
export async function reveal(o: FilesOptions, kind: unknown): Promise<void> {
  const k = revealKind(kind);
  if (!k) throw new Error('Unknown folder');
  const dir = join(o.home, k);
  if (!(await revealableFolder(dir))) throw new Error(`There is no ${k} folder in ${o.home} yet`);
  const error = await shell.openPath(dir);
  if (error) throw new Error(`Couldn’t open the folder: ${error}`);
}
