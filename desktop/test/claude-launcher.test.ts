import assert from 'node:assert/strict';
import type { ChildProcess, SpawnOptions } from 'node:child_process';
import { EventEmitter } from 'node:events';
import { mkdirSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { describe, it } from 'node:test';
import { claudeDesktopLaunchers, openInClaudeDesktop, startLauncher, type Spawn } from '../src/main/claude-launcher.ts';
import { tempDir } from './helpers.ts';

const LOCAL = 'C:\\Users\\alex\\AppData\\Local';
const ALIAS = `${LOCAL}\\Microsoft\\WindowsApps\\claude-desktop.exe`;
const SQUIRREL = `${LOCAL}\\AnthropicClaude\\claude.exe`;
const FILE = 'C:\\Users\\alex\\.gaterook\\clients\\claude-desktop\\gaterook-dev.mcpb';

interface Started { readonly file: string; readonly args: readonly string[]; readonly options: SpawnOptions; unrefs: number }

/** A spawn whose processes start, or fail with `failing`'s error, on the next tick like Node's. */
function fakeSpawn(failing: ReadonlySet<string> = new Set()): { spawn: Spawn; started: Started[] } {
  const started: Started[] = [];
  const spawn: Spawn = (file, args, options) => {
    const record: Started = { file, args, options, unrefs: 0 };
    started.push(record);
    const child = Object.assign(new EventEmitter(), { unref: () => { record.unrefs++; } });
    process.nextTick(() => {
      if (failing.has(file)) child.emit('error', Object.assign(new Error(`spawn ${file} EACCES`), { code: 'EACCES' }));
      else child.emit('spawn');
    });
    return child as unknown as ChildProcess;
  };
  return { spawn, started };
}

describe('Claude Desktop launcher on Windows', () => {
  it('knows the MSIX alias first, then the per-user install, both under %LOCALAPPDATA%', () => {
    assert.deepEqual(claudeDesktopLaunchers({ LOCALAPPDATA: LOCAL }), [ALIAS, SQUIRREL]);
    assert.deepEqual(claudeDesktopLaunchers({}), []);
    assert.deepEqual(claudeDesktopLaunchers({ LOCALAPPDATA: '' }), []);
    assert.deepEqual(claudeDesktopLaunchers({ LOCALAPPDATA: 'AppData\\Local' }), [], 'a relative folder names no launcher');
  });

  it('hands the extension to the alias as its only argument, detached and with its window shown', async () => {
    const { spawn, started } = fakeSpawn();
    const asked: string[] = [];
    const launcher = await openInClaudeDesktop(FILE, { LOCALAPPDATA: LOCAL }, {
      exists: async (f) => { asked.push(f); return true; },
      spawn,
    });
    assert.equal(launcher, ALIAS);
    assert.deepEqual(asked, [ALIAS], 'the per-user install is not looked at once the alias starts');
    assert.equal(started.length, 1);
    const [run] = started;
    assert.equal(run!.file, ALIAS);
    assert.deepEqual(run!.args, [FILE]);
    assert.equal(run!.options.detached, true, 'quitting GateRook must not take a Claude Desktop it started with it');
    assert.equal(run!.options.stdio, 'ignore');
    assert.equal(run!.options.shell, false);
    assert.equal(run!.options.windowsHide, false, 'a hidden start would hide the window Claude Desktop opens');
    assert.equal(run!.unrefs, 1);
  });

  it('uses the per-user install when there is no alias, or when the alias does not start', async () => {
    const onlySquirrel = fakeSpawn();
    assert.equal(await openInClaudeDesktop(FILE, { LOCALAPPDATA: LOCAL },
      { exists: async (f) => f === SQUIRREL, spawn: onlySquirrel.spawn }), SQUIRREL);
    assert.deepEqual(onlySquirrel.started.map((s) => s.file), [SQUIRREL]);

    const brokenAlias = fakeSpawn(new Set([ALIAS]));
    assert.equal(await openInClaudeDesktop(FILE, { LOCALAPPDATA: LOCAL },
      { exists: async () => true, spawn: brokenAlias.spawn }), SQUIRREL);
    assert.deepEqual(brokenAlias.started.map((s) => s.file), [ALIAS, SQUIRREL]);
  });

  it('answers null when no launcher is there or none starts, so main falls back to the default app', async () => {
    const none = fakeSpawn();
    assert.equal(await openInClaudeDesktop(FILE, { LOCALAPPDATA: LOCAL }, { exists: async () => false, spawn: none.spawn }), null);
    assert.equal(none.started.length, 0);
    const failing = fakeSpawn(new Set([ALIAS, SQUIRREL]));
    assert.equal(await openInClaudeDesktop(FILE, { LOCALAPPDATA: LOCAL }, { exists: async () => true, spawn: failing.spawn }), null);
    assert.equal(await openInClaudeDesktop(FILE, {}, { exists: async () => true, spawn: none.spawn }), null);
  });

  it('treats a spawn that throws as a launcher that does not start', async () => {
    const throwing: Spawn = () => { throw Object.assign(new TypeError('bad argument'), { code: 'ERR_INVALID_ARG_VALUE' }); };
    assert.equal(await startLauncher(ALIAS, FILE, throwing), false);
  });

  it('starts nothing when the default check finds no launcher', async () => {
    const t = tempDir('claude-launcher');
    try {
      const { spawn, started } = fakeSpawn();
      assert.equal(await openInClaudeDesktop(FILE, { LOCALAPPDATA: t.dir }, { spawn }), null);
      assert.equal(started.length, 0);
    } finally { t.remove(); }
  });

  it('finds the per-user install with the default check', { skip: process.platform !== 'win32' && 'Windows paths' }, async () => {
    const t = tempDir('claude-launcher');
    try {
      const stub = join(t.dir, 'AnthropicClaude', 'claude.exe');
      mkdirSync(dirname(stub));
      writeFileSync(stub, '');
      const { spawn, started } = fakeSpawn();
      assert.equal(await openInClaudeDesktop(FILE, { LOCALAPPDATA: t.dir }, { spawn }), stub);
      assert.deepEqual(started.map((s) => s.file), [stub]);
    } finally { t.remove(); }
  });
});
