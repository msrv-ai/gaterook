import { TestBed } from '@angular/core/testing';
import { DesktopBridge } from '../desktop/desktop-bridge';
import { ApiClient } from './api-client';
import { ClientsApi } from './clients.api';

function setup(windowsStore: boolean) {
  const http = { get: vi.fn().mockResolvedValue({}), post: vi.fn().mockResolvedValue({}), delete: vi.fn().mockResolvedValue({}) };
  TestBed.configureTestingModule({ providers: [
    { provide: ApiClient, useValue: http },
    { provide: DesktopBridge, useValue: { windowsStore } },
  ] });
  return { api: TestBed.inject(ClientsApi), http };
}

describe('ClientsApi distribution behavior', () => {
  it('uses separate manual app routes for creation, token renewal and removal', async () => {
    const { api, http } = setup(false);
    const request = { name: 'Research assistant', endpoint: 'dev', ttlDays: 30 };
    await api.addManual(request);
    await api.renewManual('manual-example', 7);
    await api.removeManual('manual-example');
    expect(http.post).toHaveBeenCalledWith('/api/clients/manual', request);
    expect(http.post).toHaveBeenCalledWith('/api/clients/manual/manual-example/token', { ttlDays: 7 });
    expect(http.delete).toHaveBeenCalledWith('/api/clients/manual/manual-example');
  });

  it('surfaces core installation refusals instead of claiming the client is connected', async () => {
    const { api, http } = setup(true);
    const failure = new Error('Use the Claude Desktop extension in this distribution.');
    http.post.mockRejectedValueOnce(failure);
    await expect(api.connectPreview('claude-desktop', { endpoint: 'dev', method: 'config-bridge' })).rejects.toBe(failure);
    expect(http.post).toHaveBeenCalledWith('/api/clients/claude-desktop/connect/preview', { endpoint: 'dev', method: 'config-bridge' });
    expect(http.post).toHaveBeenCalledTimes(1);
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

  it.each([false, true])('sends client setup operations to the core in Store mode: %s', async (windowsStore) => {
    const { api, http } = setup(windowsStore);
    const request = { endpoint: 'dev', method: 'token' as const };
    await api.connectPreview('claude-desktop', { endpoint: 'dev', method: 'mcpb' });
    await api.connectPreview('codex', { endpoint: 'dev', method: 'helper' });
    await api.connectPreview('cursor', request);
    await api.connect('cursor', 'preview');
    await api.disconnectPreview('link');
    await api.disconnect('link', 'preview');
    await api.restorePreview('codex', 'backup');
    await api.restore('codex', 'preview');
    await api.removeClaudeBridge();
    expect(http.post.mock.calls.map(([path]) => path)).toEqual([
      '/api/clients/claude-desktop/connect/preview', '/api/clients/codex/connect/preview',
      '/api/clients/cursor/connect/preview', '/api/clients/cursor/connect',
      '/api/clients/links/link/disconnect/preview', '/api/clients/links/link/disconnect',
      '/api/clients/codex/restore/preview', '/api/clients/codex/restore',
      '/api/clients/claude-desktop/remove-bridge',
    ]);
    expect(http.post).toHaveBeenCalledWith('/api/clients/cursor/connect', { previewId: 'preview' });
  });
});
