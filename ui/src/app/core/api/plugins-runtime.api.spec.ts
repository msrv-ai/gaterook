import { TestBed } from '@angular/core/testing';
import { ApiClient } from './api-client';
import { PluginRuntimeStatus, PluginsApi } from './plugins.api';

const ready: PluginRuntimeStatus = { state: 'ready', version: '25', downloadBytes: 0, message: null };
const downloading: PluginRuntimeStatus = { ...ready, state: 'downloading', message: 'Downloading Java support…' };

function setup() {
  const http = { get: vi.fn().mockResolvedValue(ready), post: vi.fn().mockResolvedValue(ready) };
  TestBed.configureTestingModule({ providers: [{ provide: ApiClient, useValue: http }] });
  return { api: TestBed.inject(PluginsApi), http };
}

afterEach(() => vi.useRealTimers());

describe('optional plugin runtime preparation', () => {
  it('does no startup network work and reuses ready support without polling', async () => {
    const { api, http } = setup();
    expect(http.post).not.toHaveBeenCalled();
    await api.ensureRuntime();
    expect(http.post).toHaveBeenCalledExactlyOnceWith('/api/plugins/runtime/prepare');
    expect(http.get).not.toHaveBeenCalled();
  });

  it('shows progress and waits for verification before permitting an install', async () => {
    vi.useFakeTimers();
    const { api, http } = setup();
    http.post.mockResolvedValue(downloading);
    const progress = vi.fn();
    let installed = false;
    const preparing = api.ensureRuntime({ progress }).then(() => { installed = true; });
    await Promise.resolve();
    expect(installed).toBe(false);
    expect(progress).toHaveBeenCalledWith(downloading.message);
    await vi.advanceTimersByTimeAsync(1000);
    await preparing;
    expect(installed).toBe(true);
    expect(http.get).toHaveBeenCalledExactlyOnceWith('/api/plugins/runtime');
  });

  it('surfaces offline failure without silently retrying the download', async () => {
    const { api, http } = setup();
    http.post.mockResolvedValue({ ...ready, state: 'error', message: 'Connect to the internet and retry.' });
    await expect(api.ensureRuntime()).rejects.toThrow('Connect to the internet');
    expect(http.post).toHaveBeenCalledTimes(1);
    expect(http.get).not.toHaveBeenCalled();
  });

  it('cancels a deferred install when its dialog closes, including a ready response already in flight', async () => {
    const { api, http } = setup();
    let finish!: (value: PluginRuntimeStatus) => void;
    http.post.mockReturnValue(new Promise<PluginRuntimeStatus>((resolve) => { finish = resolve; }));
    let cancelled = false;
    const preparation = api.ensureRuntime({ cancelled: () => cancelled });
    cancelled = true;
    finish(ready);
    await expect(preparation).rejects.toMatchObject({ name: 'AbortError' });
    expect(http.get).not.toHaveBeenCalled();
  });
});
