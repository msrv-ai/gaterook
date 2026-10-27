import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import {
  TitleStrategy,
  provideRouter,
  withComponentInputBinding,
  withInMemoryScrolling,
  withRouterConfig,
} from '@angular/router';
import { routes } from './app.routes';
import { problemJsonInterceptor } from './core/api/problem-json.interceptor';
import { provideKit } from './kit/kit';
import { GwTitleStrategy } from './shell/title.strategy';

/**
 * The app's providers (ARCHITECTURE.md §8.1): zoneless signals, the routes of the kit's pages with route
 * parameters and query parameters bound to page inputs, fetch-based HttpClient (Electron main proxies `/api/*` with
 * the launch secret; the renderer never sees it) with problem+json errors turned into `ApiError`, and the kit
 * (icons, document-wide behaviours).
 */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideRouter(
      routes,
      withComponentInputBinding(),
      // Fragments are tab and section anchors (`connectors#catalog`, `policies/dev#dev-limits`) that the tab
      // directives select. The window never scrolls as a whole (the shell's main does), so the router doesn't.
      withInMemoryScrolling({ scrollPositionRestoration: 'disabled', anchorScrolling: 'disabled' }),
      // The shell reads the section of the deepest route; children see their parents' params and data.
      withRouterConfig({ paramsInheritanceStrategy: 'always' }),
    ),
    { provide: TitleStrategy, useExisting: GwTitleStrategy },
    provideHttpClient(withFetch(), withInterceptors([problemJsonInterceptor])),
    provideKit(),
  ],
};
