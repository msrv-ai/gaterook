import { Routes } from '@angular/router';
import { TimeService } from '../core/time/time.service';
import { DemoTimeService } from './demo-time';

/**
 * Dev only (angular.json replaces this file with dev-routes.prod.ts in production builds, so none of the gallery or
 * its fixtures ship): `/dev/elements` lists the kit's elements and `/dev/elements/<name>` renders one with the demo
 * world's data, frameless like design/elements/<name>.html.
 *
 * Elements of merged areas read the dev core's GW_DEMO world (dev/live.ts) on the core's clock, with fixtures rebased
 * to it for the states the world doesn't have now. Elements whose area isn't merged yet (clients, notifications,
 * the core's start in Electron) and those without data render fixtures on the demo world's own clock, Thursday 14:30.
 */
const FIXTURE_CLOCK = new Set(['how-it-works', 'approval-notification', 'client-card', 'settings-layout', 'status-screen', 'status-tile']);

const demos: Record<string, () => Promise<unknown>> = {
  'setup-map': () => import('./demos/setup-map.demo').then((m) => m.SetupMapDemo),
  'how-it-works': () => import('./demos/how-it-works.demo').then((m) => m.HowItWorksDemo),
  'getting-started': () => import('./demos/getting-started.demo').then((m) => m.GettingStartedDemo),
  'approval-request': () => import('./demos/approval-request.demo').then((m) => m.ApprovalRequestDemo),
  'approval-notification': () => import('./demos/approval-notification.demo').then((m) => m.ApprovalNotificationDemo),
  'connector-card': () => import('./demos/connector-card.demo').then((m) => m.ConnectorCardDemo),
  'tool-policy-list': () => import('./demos/tool-policy-list.demo').then((m) => m.ToolPolicyListDemo),
  'client-card': () => import('./demos/client-card.demo').then((m) => m.ClientCardDemo),
  'event-detail': () => import('./demos/event-detail.demo').then((m) => m.EventDetailDemo),
  'log-explorer': () => import('./demos/log-explorer.demo').then((m) => m.LogExplorerDemo),
  'risk-callout': () => import('./demos/risk-callout.demo').then((m) => m.RiskCalloutDemo),
  'token-issue': () => import('./demos/token-issue.demo').then((m) => m.TokenIssueDemo),
  'settings-layout': () => import('./demos/settings-layout.demo').then((m) => m.SettingsLayoutDemo),
  'status-screen': () => import('./demos/status-screen.demo').then((m) => m.StatusScreenDemo),
  'command-palette': () => import('./demos/command-palette.demo').then((m) => m.CommandPaletteDemo),
  'connection-form': () => import('./demos/connection-form.demo').then((m) => m.ConnectionFormDemo),
  'status-tile': () => import('./demos/status-tile.demo').then((m) => m.StatusTileDemo),
};

export const DEV_ROUTES: Routes = [
  {
    path: 'dev',
    data: { section: 'Elements', shell: 'none' },
    children: [
      { path: '', pathMatch: 'full', redirectTo: 'elements' },
      {
        path: 'elements',
        title: 'Elements',
        loadComponent: () => import('./gallery-index.page').then((m) => m.GalleryIndexPage),
      },
      ...Object.entries(demos).map(([name, load]) => ({
        path: `elements/${name}`,
        title: `${name} · Element`,
        providers: FIXTURE_CLOCK.has(name) ? [{ provide: TimeService, useClass: DemoTimeService }] : [],
        loadComponent: load as () => Promise<never>,
      })),
    ],
  },
];
