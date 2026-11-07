import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { ConnectionPreview, EndpointsApi } from '../../core/api/endpoints.api';
import { AriaDisabledGuard } from '../../kit/aria-disabled';
import { provideKitIcons } from '../../kit/icons';
import { conn, endpoint } from '../endpoints/endpoints.fixtures';
import { GwOverviewSetup } from './overview-setup';

const connection = () => conn('github-mcp', 'dev', 'healthy', 15, []);
const access = (): ConnectionPreview => ({
  connection: 'github-mcp', env: 'dev', warnings: [], refusal: null,
  tools: [
    { toolKey: 'github:read', tool: 'Read issues', mode: 'READ_ONLY', locked: null, declaredBy: 'server' },
    { toolKey: 'github:write', tool: 'Create issue', mode: 'APPROVAL', locked: null, declaredBy: null },
  ],
});

let fixture: ComponentFixture<GwOverviewSetup>;
let preview: ReturnType<typeof vi.fn>;
let add: ReturnType<typeof vi.fn>;
async function settle(): Promise<void> {
  for (let i = 0; i < 3; i++) {
    fixture.detectChanges();
    await new Promise((resolve) => setTimeout(resolve, 0));
    await fixture.whenStable();
  }
}
const text = () => fixture.nativeElement.textContent.replace(/\s+/g, ' ');
const openDialog = () => fixture.nativeElement.querySelector('dialog[open]') as HTMLDialogElement;
const label = (element: Element | null) => (element?.textContent ?? '').replace(/\s+/g, ' ').trim();
const button = (root: ParentNode, name: string) => [...root.querySelectorAll<HTMLButtonElement>('button')].find((item) => label(item) === name)!;
const unavailableToggle = () => [...openDialog().querySelectorAll<HTMLButtonElement>('button[aria-controls]')].find((item) => label(item).startsWith('Show unavailable'))!;
const confirm = () => [...fixture.nativeElement.querySelectorAll('button')].find((button) => (button as HTMLButtonElement).textContent?.trim() === 'Connect service') as HTMLButtonElement;

beforeEach(async () => {
  preview = vi.fn().mockResolvedValue(access());
  add = vi.fn().mockResolvedValue({});
  TestBed.configureTestingModule({ providers: [provideRouter([]), provideKitIcons(), { provide: EndpointsApi, useValue: { previewConnection: preview, addConnection: add } }] });
  TestBed.inject(AriaDisabledGuard).install();
  fixture = TestBed.createComponent(GwOverviewSetup);
  document.body.appendChild(fixture.nativeElement);
  await settle();
});
afterEach(() => document.body.innerHTML = '');

it('shows core tool modes and grants only the chosen endpoint after explicit confirmation', async () => {
  const added = vi.fn();
  fixture.componentInstance.added.subscribe(added);
  fixture.componentInstance.showConnection(connection(), endpoint('selected'), null);
  await settle();
  expect(preview).toHaveBeenCalledWith('selected', 'github-mcp');
  expect(text()).toContain('1 Read only');
  expect(text()).toContain('1 Approval');
  expect(add).not.toHaveBeenCalled();
  confirm().click();
  await settle();
  expect(add).toHaveBeenCalledExactlyOnceWith('selected', 'github-mcp');
  expect(added).toHaveBeenCalledExactlyOnceWith('selected');
});

it('uses the core refusal for existing service eligibility rather than inferring from environment', async () => {
  preview.mockResolvedValue({ ...access(), refusal: 'This endpoint accepts read-only tools only.' });
  fixture.componentInstance.showPicker(endpoint('selected', { access: 'read-only' }), [connection()], null);
  await settle();
  const toggle = unavailableToggle();
  expect(label(toggle)).toBe('Show unavailable (1)');
  const region = document.getElementById(toggle.getAttribute('aria-controls')!)!;
  expect(region.hidden).toBe(true);
  expect(openDialog().querySelector('button.gw-card__link')).toBeNull();
  toggle.click();
  await settle();
  expect(region.hidden).toBe(false);
  expect(label(region)).toContain('This endpoint accepts read-only tools only.');
  expect(add).not.toHaveBeenCalled();
});

it('does not write a changed preview until its updated tools are reviewed again', async () => {
  fixture.componentInstance.showConnection(connection(), endpoint('selected'), null);
  await settle();
  preview.mockResolvedValue({ ...access(), tools: [...access().tools, { toolKey: 'github:delete', tool: 'Delete issue', mode: 'APPROVAL', locked: null, declaredBy: null }] });
  confirm().click();
  await settle();
  expect(add).not.toHaveBeenCalled();
  expect(text()).toContain('Available tools changed.');
  expect(text()).toContain('Delete issue');
  confirm().click();
  await settle();
  expect(add).toHaveBeenCalledExactlyOnceWith('selected', 'github-mcp');
});

it('keeps first-run source choices usable without inventing an endpoint', async () => {
  fixture.componentInstance.showPicker(null, [connection()], null);
  await settle();
  const sources = [...openDialog().querySelectorAll<HTMLAnchorElement>('a.gw-card__link')];
  expect(sources.map((link) => link.getAttribute('href'))).toEqual([
    '/connectors#catalog',
    '/connectors?add=mcp-server&source=url#mcp-servers',
    '/connections/new',
  ]);
  expect(sources.map(label)).toEqual(['Browse services', 'Connect an MCP service by URL', 'Connect a database or API']);
  expect(document.activeElement).toBe(sources[0]);
  expect(openDialog().querySelector('button.gw-card__link')).toBeNull();
  expect(preview).not.toHaveBeenCalled();
  expect(add).not.toHaveBeenCalled();
});

it('allows closing a slow access check without granting access', async () => {
  preview.mockImplementation(() => new Promise(() => undefined));
  fixture.componentInstance.showConnection(connection(), endpoint('selected'), null);
  await settle();
  const dialog = fixture.nativeElement.querySelector('dialog[open]') as HTMLDialogElement;
  const cancel = [...dialog.querySelectorAll('button')].find((button) => button.textContent?.trim() === 'Cancel')!;
  expect(cancel.getAttribute('aria-disabled')).toBeNull();
  cancel.click();
  await settle();
  expect(dialog.open).toBe(false);
  expect(add).not.toHaveBeenCalled();
});

it('keeps a confirmed access write in view until the core answers', async () => {
  let finish!: () => void;
  add.mockImplementation(() => new Promise<void>((resolve) => finish = resolve));
  fixture.componentInstance.showConnection(connection(), endpoint('selected'), null);
  await settle();
  confirm().click();
  await settle();
  const dialog = fixture.nativeElement.querySelector('dialog[open]') as HTMLDialogElement;
  const cancel = new Event('cancel', { cancelable: true });
  dialog.dispatchEvent(cancel);
  expect(cancel.defaultPrevented).toBe(true);
  expect(dialog.open).toBe(true);
  finish();
  await settle();
  expect(dialog.open).toBe(false);
});

it.each([{ status: 'disabled' as const }, { blockedReason: 'Endpoint configuration needs review.' }])('does not preview or grant access to an unavailable endpoint: %o', async (overrides) => {
  fixture.componentInstance.showConnection(connection(), endpoint('selected', overrides), null);
  await settle();
  expect(confirm().getAttribute('aria-disabled')).toBe('true');
  confirm().click();
  expect(preview).not.toHaveBeenCalled();
  expect(add).not.toHaveBeenCalled();
});


it('keeps many unavailable services collapsed and resets the disclosure each time the chooser opens', async () => {
  const rows = Array.from({ length: 12 }, (_, index) => ({ ...connection(), name: `incomplete-service-${index}`, setupRequired: true }));
  const selected = endpoint('selected');
  fixture.componentInstance.showPicker(selected, rows, null);
  await settle();
  const toggle = unavailableToggle();
  expect(label(toggle)).toBe('Show unavailable (12)');
  expect(toggle.getAttribute('aria-expanded')).toBe('false');
  const region = document.getElementById(toggle.getAttribute('aria-controls')!)!;
  expect(region.hidden).toBe(true);
  expect(openDialog().querySelectorAll('a.gw-card__link')).toHaveLength(3);
  expect(preview).not.toHaveBeenCalled();
  expect(add).not.toHaveBeenCalled();
  toggle.click();
  await settle();
  expect(region.hidden).toBe(false);
  expect(label(region)).toContain('incomplete-service-11');
  expect(label(region)).toContain('Finish setting up this service in Connections first.');
  button(openDialog(), 'Cancel').click();
  await settle();
  fixture.componentInstance.showPicker(selected, rows, null);
  await settle();
  expect(unavailableToggle().getAttribute('aria-expanded')).toBe('false');
  expect(document.getElementById(unavailableToggle().getAttribute('aria-controls')!)!.hidden).toBe(true);
  expect(add).not.toHaveBeenCalled();
});

it('shows one checking state and waits for eligibility before offering an existing service', async () => {
  let finish!: (value: ConnectionPreview) => void;
  preview.mockImplementation(() => new Promise<ConnectionPreview>((resolve) => finish = resolve));
  fixture.componentInstance.showPicker(endpoint('selected'), [connection()], null);
  await settle();
  const dialog = openDialog();
  expect(dialog.querySelectorAll('[role="status"]')).toHaveLength(1);
  expect(label(dialog.querySelector('[role="status"]'))).toMatch(/Checking/i);
  expect(dialog.querySelector('button.gw-card__link')).toBeNull();
  expect(unavailableToggle()).toBeUndefined();
  expect(add).not.toHaveBeenCalled();
  finish(access());
  await settle();
  expect(dialog.querySelector('[role="status"]')).toBeNull();
  const use = button(dialog, 'Use github-mcp');
  expect(use).toBeTruthy();
  expect(use.classList).toContain('gw-card__link');
  expect(label(use.closest('.gw-card'))).toContain('2 tools');
  use.click();
  await settle();
  expect(label(openDialog())).toContain('Review service access');
  expect(preview).toHaveBeenLastCalledWith('selected', 'github-mcp');
  expect(add).not.toHaveBeenCalled();
});

it('finds an existing service beyond the compact list by its full name or type', async () => {
  const longName = 'analytics-database-for-europe-reporting-replica';
  const rows = [
    ...Array.from({ length: 5 }, (_, index) => ({ ...connection(), name: `existing-service-${index}` })),
    { ...connection(), name: longName, typeName: 'Reporting database' },
  ];
  fixture.componentInstance.showPicker(endpoint('selected'), rows, null);
  await settle();
  const dialog = openDialog();
  expect(dialog.querySelectorAll('button.gw-card__link')).toHaveLength(4);
  expect(button(dialog, `Use ${longName}`)).toBeUndefined();
  button(dialog, 'Show all 6 services').click();
  await settle();
  expect(dialog.querySelectorAll('button.gw-card__link')).toHaveLength(6);
  expect(button(dialog, `Use ${longName}`)).toBeTruthy();
  button(dialog, 'Show fewer services').click();
  await settle();
  expect(dialog.querySelectorAll('button.gw-card__link')).toHaveLength(4);
  const search = dialog.querySelector<HTMLInputElement>('input[type="search"]')!;
  search.value = 'europe-reporting';
  search.dispatchEvent(new Event('input', { bubbles: true }));
  await settle();
  expect(dialog.querySelectorAll('button.gw-card__link')).toHaveLength(1);
  expect(button(dialog, `Use ${longName}`)).toBeTruthy();
  search.value = 'Reporting database';
  search.dispatchEvent(new Event('input', { bubbles: true }));
  await settle();
  expect(dialog.querySelectorAll('button.gw-card__link')).toHaveLength(1);
  expect(button(dialog, `Use ${longName}`)).toBeTruthy();
  dialog.querySelector<HTMLButtonElement>('button.gw-search__clear')!.click();
  await settle();
  expect(search.value).toBe('');
  expect(document.activeElement).toBe(search);
  expect(dialog.querySelectorAll('button.gw-card__link')).toHaveLength(4);
  expect(add).not.toHaveBeenCalled();
});
