import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { Injector, inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { CoreStore } from '../stores/core.store';
import { ApiError } from './api-error';

/**
 * Turns every failed `/api/**` response into an {@link ApiError} (problem+json → title, detail, JSON Pointer), so
 * pages and forms never parse HTTP errors themselves. Other requests (assets) pass untouched.
 *
 * A `network` or `locked` failure also goes to {@link CoreStore.noteApiError}: the core may have gone away or locked
 * after start, and in a browser (the dev loop) nothing else would tell the shell. CoreStore is looked up only on
 * such a failure, so the interceptor doesn't depend on it (CoreStore → CoreApi → HttpClient → interceptors).
 */
export const problemJsonInterceptor: HttpInterceptorFn = (req, next) => {
  if (!isApiRequest(req.url)) return next(req);
  const injector = inject(Injector);
  return next(req).pipe(
    catchError((e: unknown) => {
      const error = e instanceof HttpErrorResponse ? ApiError.fromHttp(e) : e;
      if (error instanceof ApiError && (error.kind === 'network' || error.kind === 'locked')) {
        injector.get(CoreStore).noteApiError(error);
      }
      return throwError(() => error);
    }),
  );
};

export function isApiRequest(url: string): boolean {
  return url.startsWith('/api/') || url === '/api' || /^[a-z]+:\/\/[^/]+\/api\//i.test(url);
}
