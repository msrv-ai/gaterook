import { TestBed } from '@angular/core/testing';
import { DesktopBridge } from '../desktop/desktop-bridge';
import { ApiClient } from './api-client';
import { ClientsApi } from './clients.api';

function setup(windowsStore: boolean) {
  const http = { get: vi.fn().mockResolvedValue({}), post: vi.fn().mockResolvedValue({}) };
  TestBed.configureTestingModule({ providers: [
    { provide: ApiClient, useValue: http },
    { provide: DesktopBridge, useValue: { windowsStore } },
  ] });
  return { api: TestBed.inject(ClientsApi), http };
}

describe('ClientsApi distribution behavior', () => {
  it('rejects Store config previews and mutations without sending a request', async () => {
    const { api, http } = setup(true);
    const requests = [
      () => api.connectPreview('cursor', { endpoint: 'dev', method: 'token' }),
      () => api.connect('cursor', 'preview'),
      () => api.disconnectPreview('link'), () => api.disconnect('link', 'preview'),
      () => api.restorePreview('codex', 'backup'), () => api.restore('codex', 'preview'),
      () => api.removeClaudeBridge(),
    ];
    expect(api.manualSetupOnly).toBe(true);
    for (const request of requests) await expect(request()).rejects.toThrow(/Manual setup/);
    expect(http.post).not.toHaveBeenCalled();
    expect(http.get).not.toHaveBeenCalled();
  });

  it('keeps Store manual setup, read-only client discovery and checks available', async () => {
    const { api, http } = setup(true);
    await api.manual('dev');
    await api.list();
    await api.scan();
    await api.check('link');
    await api.backups('codex');
    expect(http.get).toHaveBeenCalledWith('/api/clients/manual', { endpoint: 'dev' });
    expect(http.post).toHaveBeenCalledWith('/api/clients/links/link/check');
    expect(http.post).toHaveBeenCalledWith('/api/clients/scan');
  });

  it('preserves configuration changes in other desktop distributions', async () => {
    const { api, http } = setup(false);
    const request = { endpoint: 'dev', method: 'token' as const };
    await api.connectPreview('cursor', request);
    await api.connect('cursor', 'preview');
    await api.disconnectPreview('link');
    await api.disconnect('link', 'preview');
    await api.restorePreview('codex', 'backup');
    await api.restore('codex', 'preview');
    await api.removeClaudeBridge();
    expect(http.post.mock.calls.map(([path]) => path)).toEqual([
      '/api/clients/cursor/connect/preview', '/api/clients/cursor/connect',
      '/api/clients/links/link/disconnect/preview', '/api/clients/links/link/disconnect',
      '/api/clients/codex/restore/preview', '/api/clients/codex/restore',
      '/api/clients/claude-desktop/remove-bridge',
    ]);
    expect(http.post).toHaveBeenCalledWith('/api/clients/cursor/connect', { previewId: 'preview' });
  });
});
