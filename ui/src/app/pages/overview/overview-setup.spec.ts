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

it('uses the core refusal when reviewing a service instead of inferring access from environment', async () => {
  preview.mockResolvedValue({ ...access(), refusal: 'This endpoint accepts read-only tools only.' });
  fixture.componentInstance.showConnection(connection(), endpoint('selected', { access: 'read-only' }), null);
  await settle();
  expect(label(openDialog())).toContain('This endpoint accepts read-only tools only.');
  expect(confirm().getAttribute('aria-disabled')).toBe('true');
  confirm().click();
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
  const sources = [...openDialog().querySelectorAll<HTMLElement>('.gw-card__link')];
  expect(sources.map((link) => link.getAttribute('href'))).toEqual([
    null,
    '/connections/new',
  ]);
  expect(sources.map(label)).toEqual(['Connect an MCP service by URL', 'Connect a database or API']);
  expect(document.activeElement).toBe(sources[0]);
  expect(openDialog().querySelector('button.gw-card__link')).toBe(sources[0]);
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


it('offers unconnected services last with the selected endpoint filter, without previewing access', async () => {
  const rows = [
    { ...connection(), name: 'already-here', usedBy: ['selected'] },
    { ...connection(), name: 'elsewhere', usedBy: ['another-endpoint'] },
    { ...connection(), name: 'draft-db', setupRequired: true, usedBy: [] },
  ];
  fixture.componentInstance.showPicker(endpoint('selected'), rows, null);
  await settle();
  const dialog = openDialog();
  const sources = [...dialog.querySelectorAll<HTMLElement>('.gw-card__link')];
  expect(sources.map(label)).toEqual(['Connect an MCP service by URL', 'Connect a database or API', 'Connect existing services']);
  expect(sources.at(-1)!.getAttribute('href')).toBe('/connections?notConnectedTo=selected');
  expect(label(sources.at(-1)!.closest('.gw-card'))).toContain('2 services are not connected to selected.');
  expect(label(dialog)).not.toContain('Already in GateRook');
  expect(label(dialog)).not.toContain('Browse services');
  expect(dialog.querySelector('button.gw-card__link')).toBe(sources[0]);
  expect(document.activeElement).toBe(sources[0]);
  expect(preview).not.toHaveBeenCalled();
  expect(add).not.toHaveBeenCalled();
});

it('hides the existing-services card when every connection belongs to the endpoint', async () => {
  fixture.componentInstance.showPicker(endpoint('selected'), [{ ...connection(), usedBy: ['selected'] }], null);
  await settle();
  expect(openDialog().querySelectorAll('.gw-card__link')).toHaveLength(2);
  expect(label(openDialog())).not.toContain('Connect existing services');
  expect(preview).not.toHaveBeenCalled();
});

it('recounts endpoint membership whenever the chooser opens', async () => {
  const rows = [{ ...connection(), usedBy: ['dev'] }];
  fixture.componentInstance.showPicker(endpoint('stage'), rows, null);
  await settle();
  expect(label(openDialog())).toContain('1 service is not connected to stage.');
  button(openDialog(), 'Cancel').click();
  await settle();
  fixture.componentInstance.showPicker(endpoint('dev'), rows, null);
  await settle();
  expect(label(openDialog())).not.toContain('Connect existing services');
  button(openDialog(), 'Cancel').click();
  await settle();
  fixture.componentInstance.showPicker(null, rows, null);
  await settle();
  expect(label(openDialog())).not.toContain('Connect existing services');
  expect(preview).not.toHaveBeenCalled();
  expect(add).not.toHaveBeenCalled();
});

it('hands MCP URL setup its original page opener instead of the hidden source card', async () => {
  const opener = document.createElement('button');
  document.body.appendChild(opener);
  const requested = vi.fn();
  fixture.componentInstance.mcpRequested.subscribe(requested);
  fixture.componentInstance.showPicker(endpoint('selected'), [], opener);
  await settle();
  button(openDialog(), 'Connect an MCP service by URL').click();
  await settle();
  expect(openDialog()).toBeNull();
  expect(requested).toHaveBeenCalledExactlyOnceWith({ source: 'url', opener });
  expect(preview).not.toHaveBeenCalled();
  expect(add).not.toHaveBeenCalled();
});
