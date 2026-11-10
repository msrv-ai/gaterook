import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { PublishPreview } from '../../core/api/tunnel.api';
import { TimeService } from '../../core/time/time.service';
import { publishNotes } from '../../kit/elements/client-card/tunnel-view';
import { provideKitIcons } from '../../kit/icons';
import { SettingsData } from './settings.data';
import { NOW, kitEndpoints, kitTunnel } from './settings.fixtures';
import { GwTunnelSettings } from './tunnel.section';

/**
 * Settings › Tunnel's publish dialog shows the core's cautions before publishing (audit M-R5), as the Clients page's
 * dialog does. The section is not routed in this build (the tunnel UI is hidden); it is rendered on its own here.
 */

const CAUTIONS = [
  'Tools in Bypass run without approval, including changes; policy limits still apply.',
  'claude.ai can request changes; each one still waits for you here.',
  'Callers: any address can reach the public URL, not only Anthropic\'s.',
  'Dynamic client registration is on: any client that reaches the URL can register (it still needs your consent here).',
  'A quick tunnel gets a new address each time it starts; claude.ai must be pointed at the new URL. Use a named tunnel for a stable hostname.',
];

const preview = (warnings: readonly string[]): PublishPreview => ({
  endpoint: 'stage-readonly',
  publicPaths: ['/mcp/stage-readonly', '/oauth/*', '/.well-known/oauth-*'],
  local: ['The app and its admin API (127.0.0.1:7465)', '/mcp/dev'],
  connections: [{ name: 'stage-db', env: 'stage' }],
  risk: null,
  refusal: null,
  warnings,
});

const part = <T,>(value: T) => ({ value, error: null, unavailable: false });

let answer: PublishPreview;

@Component({
  imports: [GwTunnelSettings],
  template: '<section gwTunnelSettings></section>',
  providers: [
    {
      provide: SettingsData,
      useFactory: () => ({
        tunnel: signal(part(kitTunnel())),
        tunnelSettings: signal(part({ name: 'tunnel', values: { provider: 'cloudflare-quick', ngrokAuthtokenStored: false }, version: 4, updatedAt: null })),
        endpoints: signal(part(kitEndpoints())),
        grants: signal(part([])),
        published: signal('stage-readonly'),
        publishPreview: () => Promise.resolve(answer),
      }),
    },
  ],
})
class Host {}

const text = (el: Element | null | undefined) => (el?.textContent ?? '').replace(/\s+/g, ' ').trim();

async function openDialog(): Promise<HTMLDialogElement> {
  TestBed.configureTestingModule({
    providers: [
      provideRouter([]),
      provideKitIcons(),
      {
        provide: TimeService,
        useValue: { now: signal(NOW), minute: signal(NOW), clock: () => '12:10', iso: (at: string) => at, absolute: (at: string) => at, relative: () => 'a while ago' } satisfies Partial<TimeService>,
      },
    ],
  });
  const fixture = TestBed.createComponent(Host);
  document.body.appendChild(fixture.nativeElement);
  fixture.detectChanges();
  await fixture.whenStable();
  (fixture.nativeElement as HTMLElement).querySelector<HTMLButtonElement>('#st-tunnel-on-btn')!.click();
  for (let i = 0; i < 3; i++) {
    await new Promise((r) => setTimeout(r));
    fixture.detectChanges();
    await fixture.whenStable();
  }
  return document.querySelector<HTMLDialogElement>('#st-tunnel-dlg')!;
}

describe('Settings › Tunnel: the publish dialog', () => {
  afterEach(() => (document.body.innerHTML = ''));

  it('lists the core’s cautions that its rows don’t already say', async () => {
    answer = preview(CAUTIONS);
    const dialog = await openDialog();
    expect(dialog.hasAttribute('open')).toBe(true);
    const notes = Array.from(dialog.querySelectorAll('.gw-kv__row'))
      .filter((row) => text(row.querySelector('dt')) === 'Note')
      .map((row) => text(row.querySelector('dd')));
    expect(notes).toEqual([CAUTIONS[0], CAUTIONS[1], CAUTIONS[3]]);
    // Who can call and the quick tunnel's address are rows of their own.
    expect(text(dialog)).toContain('IP allowlist');
    expect(text(dialog)).toContain('A new address each time the tunnel starts');
  });

  it('shows no Note without cautions', async () => {
    answer = preview([]);
    const dialog = await openDialog();
    expect(Array.from(dialog.querySelectorAll('dt')).map(text)).not.toContain('Note');
  });

  it('publishNotes leaves out only what every publish dialog says in its rows', () => {
    expect(publishNotes(preview(CAUTIONS))).toEqual([CAUTIONS[0], CAUTIONS[1], CAUTIONS[3]]);
    expect(publishNotes(null)).toEqual([]);
    expect(publishNotes({ ...preview([]), warnings: undefined })).toEqual([]);
  });
});
