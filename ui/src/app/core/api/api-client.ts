import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject, signal } from '@angular/core';
import { firstValueFrom } from 'rxjs';

/** Query parameters: undefined and null are left out, arrays repeat the key. */
export type Query = Readonly<Record<string, string | number | boolean | readonly string[] | null | undefined>>;

/**
 * The one way the UI talks to the core's admin API (ARCHITECTURE.md §6). Paths are relative (`/api/…`): Electron
 * serves the app from `app://gw/` and its main process proxies `app://gw/api/*` to the core with the launch
 * signature (the renderer never sees the secret); in development `ng serve` proxies `/api` the same way
 * (ui/proxy.conf.mjs). Errors arrive as {@link ApiError} (problemJsonInterceptor).
 *
 * Calls return promises: a page awaits a mutation and renders the outcome; reads that should follow signals go
 * through `resource()` in the stores.
 */
@Injectable({ providedIn: 'root' })
export class ApiClient {
  private readonly http = inject(HttpClient);

  /**
   * Difference between the core's clock and this machine's, from the `Date` header of the latest answer (ms).
   * Countdowns to auto-deny tick against the core's clock, not the renderer's (TimeService).
   */
  readonly clockSkewMs = signal(0);

  /**
   * The answering core's instance nonce (`X-Gateway-Instance`, §6), when the proxy passes it on: a different value
   * means the core restarted (CoreStore).
   */
  readonly instance = signal<string | null>(null);

  get<T>(path: string, query?: Query): Promise<T> {
    return this.send<T>('GET', path, undefined, query);
  }

  post<T>(path: string, body?: unknown, query?: Query): Promise<T> {
    return this.send<T>('POST', path, body ?? {}, query);
  }

  put<T>(path: string, body: unknown, query?: Query): Promise<T> {
    return this.send<T>('PUT', path, body, query);
  }

  delete<T>(path: string, body?: unknown, query?: Query): Promise<T> {
    return this.send<T>('DELETE', path, body, query);
  }

  /** `/api/endpoints/${slug}` with every dynamic segment encoded. */
  static path(strings: TemplateStringsArray, ...values: readonly (string | number)[]): string {
    return strings.reduce((acc, s, i) => acc + s + (i < values.length ? encodeURIComponent(String(values[i])) : ''), '');
  }

  private async send<T>(method: string, path: string, body: unknown, query?: Query): Promise<T> {
    // A FormData body (a plugin JAR: the one multipart route of §6) carries its own multipart type and boundary.
    const json = body !== undefined && !(typeof FormData !== 'undefined' && body instanceof FormData);
    const headers = json ? new HttpHeaders({ 'Content-Type': 'application/json' }) : undefined;
    const response: HttpResponse<T> = await firstValueFrom(
      this.http.request<T>(method, path, {
        body: body === undefined ? null : body,
        headers,
        params: toParams(query),
        observe: 'response',
        responseType: 'json',
      }),
    );
    this.noteClock(response.headers.get('Date'));
    const instance = response.headers.get('X-Gateway-Instance');
    if (instance) this.instance.set(instance);
    return response.body as T;
  }

  private noteClock(date: string | null): void {
    if (!date) return;
    const at = Date.parse(date);
    // The Date header has second resolution: only a skew beyond a second is real.
    if (!Number.isNaN(at)) {
      const skew = at - Date.now();
      this.clockSkewMs.set(Math.abs(skew) > 1000 ? skew : 0);
    }
  }
}

export function toParams(query?: Query): HttpParams | undefined {
  if (!query) return undefined;
  let params = new HttpParams();
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue;
    if (Array.isArray(value)) {
      for (const v of value) params = params.append(key, v);
    } else {
      params = params.set(key, String(value));
    }
  }
  return params;
}
