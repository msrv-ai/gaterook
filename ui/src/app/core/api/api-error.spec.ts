import { HttpErrorResponse, HttpHeaders } from '@angular/common/http';
import { ApiError, isApiError, isNotAvailable } from './api-error';
import { isApiRequest } from './problem-json.interceptor';

const http = (status: number, error: unknown = null) =>
  new HttpErrorResponse({ status, error, url: '/api/x', headers: new HttpHeaders({ 'content-type': 'application/problem+json' }) });

describe('ApiError (problem+json, ARCHITECTURE.md §6)', () => {
  it('takes title, detail, field and confirm from the problem body', () => {
    const e = ApiError.fromHttp(
      http(422, { type: 'about:blank', title: 'Invalid port', detail: '1 to 65535.', field: '/port', confirm: 'danger' }),
    );
    expect(e.kind).toBe('unprocessable');
    expect(e.title).toBe('Invalid port');
    expect(e.detail).toBe('1 to 65535.');
    expect(e.field).toBe('/port');
    expect(e.confirm).toBe('danger');
    expect(e.messageFor('/port')).toBe('1 to 65535.');
    expect(e.messageFor('/host')).toBeNull();
  });

  it('keeps every member of the problem body, beyond the ones it names', () => {
    const e = ApiError.fromHttp(http(422, { title: "Won't edit", detail: 'JSONC', reason: 'jsonc', line: 4, column: 3, fragment: '"a": 1' }));
    expect(e.problem).toEqual({ title: "Won't edit", detail: 'JSONC', reason: 'jsonc', line: 4, column: 3, fragment: '"a": 1' });
    expect(ApiError.fromHttp(http(404)).problem).toBeNull();
  });

  it('keeps the per-field errors of a form', () => {
    const e = ApiError.fromHttp(
      http(422, { title: 'Check the form', detail: '2 fields', errors: [{ field: '/host', message: 'Required' }, { field: '/port', message: 'Too big' }] }),
    );
    expect(e.messageFor('/host')).toBe('Required');
    expect(e.messageFor('/port')).toBe('Too big');
  });

  it('parses a problem sent as text', () => {
    const e = ApiError.fromHttp(http(409, '{"title":"Changed meanwhile","detail":"version 3 is newer"}'));
    expect(e.kind).toBe('conflict');
    expect(e.detail).toBe('version 3 is newer');
  });

  it.each([
    [0, null, 'network'],
    [401, null, 'unauthorized'],
    [503, null, 'locked'],
    [503, { title: 'Locked', locked: 'MASTER_KEY_MISSING' }, 'locked'],
    [503, { title: 'Busy', detail: 'Try again' }, 'server'],
    [404, null, 'not-available'],
    [404, { title: 'No such endpoint', detail: 'dev2' }, 'not-found'],
    [400, { title: 'Bad', detail: 'x' }, 'bad-request'],
    [500, null, 'server'],
  ] as const)('status %i (%j) is %s', (status, body, kind) => {
    expect(ApiError.fromHttp(http(status, body)).kind).toBe(kind);
  });

  it('has words for every kind when the core sent none', () => {
    const e = ApiError.fromHttp(http(404));
    expect(e.title).toBe('Not available yet');
    expect(isNotAvailable(e)).toBe(true);
    expect(isApiError(e)).toBe(true);
    expect(isNotAvailable(new Error('x'))).toBe(false);
  });

  it('only admin API requests are mapped', () => {
    expect(isApiRequest('/api/health')).toBe(true);
    expect(isApiRequest('http://127.0.0.1:7465/api/events')).toBe(true);
    expect(isApiRequest('/assets/fonts.css')).toBe(false);
    expect(isApiRequest('/mcp/dev')).toBe(false);
  });
});
