import { ActivatedRouteSnapshot, Routes, UrlMatchResult, UrlSegment } from '@angular/router';
import { DEV_ROUTES } from './dev/dev-routes';
import { onboardingGuard } from './shell/onboarding.guard';

/**
 * What the shell needs from a route: the titlebar's section name and the frame around the page.
 * - `app` (default): titlebar, sidebar and main (pages/_shell.html);
 * - `welcome`: the no-sidebar variant of Onboarding (titlebar with Appearance only);
 * - `none`: no frame at all, the page is the whole document (the dev-only element gallery, like design/elements).
 */
export interface GwRouteData {
  readonly section: string;
  readonly shell?: 'app' | 'welcome' | 'none';
}

const data = (section: string, shell: GwRouteData['shell'] = 'app'): GwRouteData => ({ section, shell });

const param = (name: string, prefix: string) => (route: ActivatedRouteSnapshot) =>
  `${prefix} ${route.paramMap.get(name) ?? ''}`.trim();

/** `approvals` and `approvals/<id>`: one route with an optional `id` (the router reuses a page only within a route). */
export function approvalsMatcher(segments: UrlSegment[]): UrlMatchResult | null {
  if (segments[0]?.path !== 'approvals' || segments.length > 2) return null;
  return { consumed: segments, posParams: segments[1] ? { id: segments[1] } : {} };
}

/**
 * The kit's pages (ARCHITECTURE.md §8.1), one lazy component each in `pages/<page>/` (U1–U16 replace the stubs).
 * Query parameters (`?type=`, `?tab=`, `?q=`) and route parameters reach the page as inputs
 * (`withComponentInputBinding`); `#fragment`s select tabs and sections. `core-starting` is not a route: the app
 * shows the status screen over the whole window while the core isn't running.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'overview' },
  {
    path: '',
    canActivate: [onboardingGuard],
    children: [
      {
        path: 'overview',
        title: 'Overview',
        data: data('Overview'),
        loadComponent: () => import('./pages/overview/overview.page').then((m) => m.OverviewPage),
      },
      {
        // `approvals` and `approvals/:id` as ONE route: opening another request (Review, a deep link, the next one
        // after a decision) keeps the page and its queue, timers and announcements instead of building a new one.
        matcher: approvalsMatcher,
        title: 'Approvals',
        data: data('Approvals'),
        loadComponent: () => import('./pages/approvals/approvals.page').then((m) => m.ApprovalsPage),
      },
      {
        path: 'connections',
        title: 'Connections',
        data: data('Connections'),
        loadComponent: () => import('./pages/connections/connections.page').then((m) => m.ConnectionsPage),
      },
      {
        // ?duplicate=<name> is the same wizard from Configure: "Duplicate dev-db · GateRook" (kit page title).
        path: 'connections/new',
        title: (route: ActivatedRouteSnapshot) => {
          const copy = route.queryParamMap.get('duplicate');
          return copy ? `Duplicate ${copy}` : 'Add connection';
        },
        runGuardsAndResolvers: 'paramsOrQueryParamsChange',
        data: data('Connections'),
        loadComponent: () => import('./pages/connection-new/connection-new.page').then((m) => m.ConnectionNewPage),
      },
      {
        // The same page in edit mode (kit: connection-new.html?edit=<name>, titled "dev-db · Connections").
        path: 'connections/:name',
        title: (route: ActivatedRouteSnapshot) => `${route.paramMap.get('name') ?? ''} · Connections`,
        data: data('Connections'),
        loadComponent: () => import('./pages/connection-new/connection-new.page').then((m) => m.ConnectionNewPage),
      },
      {
        path: 'connectors',
        title: 'Connectors',
        data: data('Connectors'),
        loadComponent: () => import('./pages/connectors/connectors.page').then((m) => m.ConnectorsPage),
      },
      {
        path: 'secrets',
        title: 'Secrets',
        data: data('Secrets'),
        loadComponent: () => import('./pages/secrets/secrets.page').then((m) => m.SecretsPage),
      },
      {
        path: 'endpoints',
        title: 'Endpoints',
        data: data('Endpoints'),
        loadComponent: () => import('./pages/endpoints/endpoints.page').then((m) => m.EndpointsPage),
      },
      {
        path: 'endpoints/:slug',
        title: param('slug', 'Endpoint'),
        data: data('Endpoints'),
        loadComponent: () => import('./pages/endpoint-detail/endpoint-detail.page').then((m) => m.EndpointDetailPage),
      },
      {
        path: 'clients',
        // The kit's AI apps (the page file is still clients.html, the route stays /clients).
        title: 'AI apps',
        data: data('AI apps'),
        loadComponent: () => import('./pages/clients/clients.page').then((m) => m.ClientsPage),
      },
      { path: 'policies', pathMatch: 'full', redirectTo: 'policies/dev' },
      {
        path: 'policies/:env',
        title: 'Policies',
        data: data('Policies'),
        loadComponent: () => import('./pages/policies/policies.page').then((m) => m.PoliciesPage),
      },
      {
        path: 'playground',
        title: 'Playground',
        data: data('Playground'),
        loadComponent: () => import('./pages/playground/playground.page').then((m) => m.PlaygroundPage),
      },
      {
        path: 'activity',
        title: 'Activity',
        data: data('Activity'),
        loadComponent: () => import('./pages/activity/activity.page').then((m) => m.ActivityPage),
      },
      {
        path: 'insights',
        title: 'Insights',
        data: data('Insights'),
        loadComponent: () => import('./pages/insights/insights.page').then((m) => m.InsightsPage),
      },
      { path: 'settings', pathMatch: 'full', redirectTo: 'settings/general' },
      {
        path: 'settings/:section',
        title: 'Settings',
        data: data('Settings'),
        loadComponent: () => import('./pages/settings/settings.page').then((m) => m.SettingsPage),
      },
      {
        path: 'onboarding',
        pathMatch: 'full',
        redirectTo: 'overview',
      },
      ...DEV_ROUTES,
    ],
  },
  { path: '**', redirectTo: 'overview' },
];
