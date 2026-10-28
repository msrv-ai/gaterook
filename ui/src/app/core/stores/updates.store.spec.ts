import { TestBed } from '@angular/core/testing';
import { DesktopBridge, UpdateState } from '../desktop/desktop-bridge';
import { UpdatesStore } from './updates.store';

describe('UpdatesStore', () => {
  let push: (state: UpdateState) => void;
  let off: ReturnType<typeof vi.fn>;
  let install: ReturnType<typeof vi.fn>;
  let subscribe: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    off = vi.fn();
    install = vi.fn(async () => undefined);
    subscribe = vi.fn((cb: (state: UpdateState) => void) => { push = cb; return off; });
    TestBed.configureTestingModule({ providers: [{ provide: DesktopBridge, useValue: {
      available: true,
      onUpdateState: subscribe,
      installUpdate: install,
    } }] });
  });

  it('keeps one window subscription and offers an update only after main finds one', () => {
    const updates = TestBed.inject(UpdatesStore);
    expect(TestBed.inject(UpdatesStore)).toBe(updates);
    expect(subscribe).toHaveBeenCalledTimes(1);
    expect(updates.actionVisible()).toBe(false);
    for (const state of [{ state: 'checking' }, { state: 'up-to-date' }, { state: 'error', message: 'Offline' }] as const) {
      push(state);
      expect(updates.actionVisible()).toBe(false);
    }
    push({ state: 'available', version: '0.4.1' });
    expect(updates.actionVisible()).toBe(true);
    expect(updates.description()).toContain('0.4.1');
    expect(install).not.toHaveBeenCalled();
    TestBed.resetTestingModule();
    expect(off).toHaveBeenCalledTimes(1);
  });

  it('downloads only on activation, blocks repeat requests, and reports progress until restart', async () => {
    const updates = TestBed.inject(UpdatesStore);
    let finish!: () => void;
    install.mockImplementation(() => new Promise<void>((resolve) => { finish = resolve; }));
    push({ state: 'available', version: '0.4.1' });
    const pending = updates.install();
    expect(updates.label()).toBe('Downloading…');
    expect(updates.busy()).toBe(true);
    await updates.install();
    expect(install).toHaveBeenCalledTimes(1);
    push({ state: 'downloading', version: '0.4.1', percent: 42.9 });
    expect(updates.label()).toBe('Downloading 42%');
    push({ state: 'installing', version: '0.4.1' });
    expect(updates.label()).toBe('Restarting…');
    finish();
    await pending;
    expect(updates.busy()).toBe(true);
  });

  it('retains the offered version and allows retry after IPC failure', async () => {
    const updates = TestBed.inject(UpdatesStore);
    push({ state: 'available', version: '0.4.1', lastCheckedAt: '2026-10-28T10:00:00Z' });
    install.mockRejectedValueOnce(new Error("Error invoking remote method 'gw:install-update': Error: Download failed"));
    await updates.install();
    expect(updates.state()).toEqual({ state: 'error', message: 'Download failed', version: '0.4.1', lastCheckedAt: '2026-10-28T10:00:00Z' });
    expect(updates.label()).toBe('Retry update');
    expect(updates.actionVisible()).toBe(true);
    expect(updates.busy()).toBe(false);
    await updates.install();
    expect(install).toHaveBeenCalledTimes(2);
  });

  it('preserves a reported error and can retry an already downloaded update', async () => {
    const updates = TestBed.inject(UpdatesStore);
    push({ state: 'downloaded', version: '0.4.1' });
    expect(updates.label()).toBe('Restart to update');
    install.mockImplementation(async () => {
      expect(updates.label()).toBe('Restarting…');
      push({ state: 'error', version: '0.4.1', message: 'Installer signature could not be verified.' });
      throw new Error('IPC failure');
    });
    await updates.install();
    expect(updates.error()).toBe('Installer signature could not be verified.');
  });

  it('keeps network progress finite and bounded', () => {
    const updates = TestBed.inject(UpdatesStore);
    push({ state: 'downloading', percent: Number.NaN });
    expect(updates.label()).toBe('Downloading…');
    push({ state: 'downloading', percent: -10 });
    expect(updates.label()).toBe('Downloading 0%');
    push({ state: 'downloading', percent: 120 });
    expect(updates.label()).toBe('Downloading 100%');
  });

  it('does not subscribe or offer installation in a browser', async () => {
    TestBed.overrideProvider(DesktopBridge, { useValue: { available: false, onUpdateState: subscribe, installUpdate: install } });
    const updates = TestBed.inject(UpdatesStore);
    expect(subscribe).not.toHaveBeenCalled();
    expect(updates.actionVisible()).toBe(false);
    await updates.install();
    expect(install).not.toHaveBeenCalled();
  });
});
