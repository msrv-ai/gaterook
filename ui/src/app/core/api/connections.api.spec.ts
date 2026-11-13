import { TestBed } from '@angular/core/testing';
import { ApiClient } from './api-client';
import { ConnectionsApi, SuggestedWire, suggestedFromWire } from './connections.api';

describe('ConnectionsApi setup suggestions', () => {
  const wire: SuggestedWire = {
    fields: {
      '/context': { options: [{ option: 'kind-dev', label: 'kind-dev (current)', detail: '127.0.0.1:6443' }],
        fill: 'kind-dev', source: '~/.kube/config', complete: true },
      '/namespace': { options: [], fill: null, source: null, complete: false },
    },
    notes: ['Context gke-prod signs in with gke-gcloud-auth-plugin.'],
  };

  it('reads the core’s option and fill as the form’s value', () => {
    expect(suggestedFromWire(wire)).toEqual({
      fields: {
        '/context': { options: [{ value: 'kind-dev', label: 'kind-dev (current)', detail: '127.0.0.1:6443' }],
          value: 'kind-dev', source: '~/.kube/config', complete: true },
        '/namespace': { options: [], value: null, source: null, complete: false },
      },
      notes: ['Context gke-prod signs in with gke-gcloud-auth-plugin.'],
    });
  });

  it('posts the draft and answers in the form’s terms', async () => {
    const http = { post: vi.fn().mockResolvedValue(wire) };
    TestBed.configureTestingModule({ providers: [{ provide: ApiClient, useValue: http }] });
    const request = { type: 'kubernetes', env: 'dev' as const, config: { auth: 'kubeconfig-file' }, scope: 'local' as const };

    const answer = await TestBed.inject(ConnectionsApi).suggest(request);

    expect(http.post).toHaveBeenCalledWith('/api/connections/suggest', request);
    expect(answer.fields['/context'].value).toBe('kind-dev');
    expect(answer.fields['/context'].options[0].value).toBe('kind-dev');
  });
});
