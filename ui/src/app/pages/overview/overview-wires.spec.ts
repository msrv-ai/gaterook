import { Component, signal } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { GwOverviewWires, OverviewWireNode, OverviewWireTrace } from './overview-wires';

@Component({
  imports: [GwOverviewWires],
  template: `
    <div #map data-map-root style="--gw-motion-duration-slow: 280ms">
      <svg gwOverviewWires [mapRoot]="map" [endpoint]="endpoint()" [endpointEnabled]="enabled()"
        [services]="services()" [apps]="apps()" [activeNode]="active()" [trace]="trace()"></svg>
      @if (showService()) { <div data-map-service="warehouse"></div> }
      <div data-map-service="paused"></div>
      <div data-map-service="unshared"></div>
      <label data-map-endpoint="dev"></label>
      <label data-map-endpoint="prod"></label>
      <div data-map-app="claude"></div>
      <div data-map-app="cursor"></div>
      <div data-map-app="unconfigured"></div>
    </div>`,
})
class WireHost {
  readonly endpoint = signal<string | null>('dev');
  readonly enabled = signal(true);
  readonly services = signal<readonly OverviewWireNode[]>([
    { id: 'warehouse', enabled: true }, { id: 'paused', enabled: false }, { id: 'absent', enabled: true },
  ]);
  readonly apps = signal<readonly OverviewWireNode[]>([
    { id: 'claude', enabled: true }, { id: 'cursor', enabled: true, pending: true },
  ]);
  readonly active = signal<string | null>(null);
  readonly trace = signal<OverviewWireTrace | null>(null);
  readonly showService = signal(true);
}

function rect(x: number, y: number, width: number, height: number): DOMRect {
  return { x, y, width, height, top: y, right: x + width, bottom: y + height, left: x, toJSON: () => ({}) };
}

let desktop = true;
let reduced = false;
let frame = 0;
let frames: Map<number, FrameRequestCallback>;
let resizeCallback: ResizeObserverCallback;
let disconnect: ReturnType<typeof vi.fn>;
let observe: ReturnType<typeof vi.fn>;

beforeEach(() => {
  desktop = true;
  reduced = false;
  frame = 0;
  frames = new Map();
  disconnect = vi.fn();
  observe = vi.fn();
  vi.stubGlobal('ResizeObserver', class {
    constructor(callback: ResizeObserverCallback) { resizeCallback = callback; }
    observe = observe;
    disconnect = disconnect;
    unobserve = vi.fn();
  });
  vi.stubGlobal('matchMedia', vi.fn((query: string) => ({ matches: query.includes('reduced-motion') ? reduced : desktop })));
  vi.spyOn(window, 'requestAnimationFrame').mockImplementation((callback) => {
    frames.set(++frame, callback);
    return frame;
  });
  vi.spyOn(window, 'cancelAnimationFrame').mockImplementation((id) => { frames.delete(id); });
  vi.spyOn(Element.prototype, 'getBoundingClientRect').mockImplementation(function (this: Element) {
    if (this.hasAttribute('data-map-root')) return rect(100, 50, 1_000, 500);
    if (this.hasAttribute('data-map-service')) return rect(120, 150, 200, 60);
    if (this.getAttribute('data-map-endpoint') === 'dev') return rect(500, 190, 180, 84);
    if (this.getAttribute('data-map-endpoint') === 'prod') return rect(500, 300, 180, 84);
    if (this.hasAttribute('data-map-app')) return rect(800, 180, 200, 72);
    return rect(0, 0, 0, 0);
  });
});

afterEach(() => {
  TestBed.resetTestingModule();
  document.body.replaceChildren();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

async function settle(fixture: ComponentFixture<WireHost>): Promise<void> {
  fixture.detectChanges();
  await fixture.whenStable();
  const callbacks = [...frames.values()];
  frames.clear();
  callbacks.forEach((callback) => callback(0));
}

async function render() {
  const fixture = TestBed.createComponent(WireHost);
  document.body.append(fixture.nativeElement);
  await settle(fixture);
  const svg = fixture.nativeElement.querySelector('svg') as SVGSVGElement;
  const path = (key: string) => Array.from(svg.querySelectorAll('path')).find((el) => el.dataset['node'] === key)!;
  return { fixture, host: fixture.componentInstance, svg, path };
}

describe('Overview network wires', () => {
  it('draws only configured visible nodes with geometry relative to the selected endpoint', async () => {
    const { fixture, host, svg, path } = await render();
    expect(svg.getAttribute('aria-hidden')).toBe('true');
    expect(svg.getAttribute('viewBox')).toBe('0 0 1000 500');
    expect(Array.from(svg.querySelectorAll('path')).map((el) => el.dataset['node'])).toEqual([
      'service:warehouse', 'service:paused', 'app:claude', 'app:cursor',
    ]);
    expect(path('service:warehouse').getAttribute('d')).toBe('M 220 130 C 319 130, 301 182, 400 182');
    expect(path('service:paused').dataset['muted']).toBe('true');
    expect(path('service:paused').dataset['pending']).toBe('true');
    expect(path('app:cursor').dataset['pending']).toBe('true');
    host.endpoint.set('prod');
    await settle(fixture);
    expect(path('service:warehouse').getAttribute('d')).toContain('400 292');
  });

  it('highlights an enabled service and connected app, without implying pending or paused access', async () => {
    const { fixture, host, svg, path } = await render();
    host.active.set('service:warehouse');
    await settle(fixture);
    expect(Array.from(svg.querySelectorAll('[data-active="true"]')).map((el) => el.getAttribute('data-node')))
      .toEqual(['service:warehouse', 'app:claude']);
    host.active.set('app:claude');
    await settle(fixture);
    expect(path('service:paused').dataset['active']).toBe('false');
    host.enabled.set(false);
    await settle(fixture);
    expect(svg.querySelector('[data-active="true"]')).toBeNull();
    expect(svg.querySelectorAll('[data-muted="true"]')).toHaveLength(4);
  });

  it('removes filtered paths and clears decorative geometry below the desktop breakpoint', async () => {
    const { fixture, host, svg } = await render();
    host.showService.set(false);
    host.services.set([...host.services()]);
    await settle(fixture);
    expect(svg.querySelector('[data-node="service:warehouse"]')).toBeNull();
    desktop = false;
    window.dispatchEvent(new Event('resize'));
    await settle(fixture);
    expect(svg.childElementCount).toBe(0);
    desktop = true;
    resizeCallback([], {} as ResizeObserver);
    await settle(fixture);
    expect(svg.querySelectorAll('path')).toHaveLength(3);
  });

  it('keeps the requested route static for reduced motion and refuses a paused trace', async () => {
    const { fixture, host, svg } = await render();
    reduced = true;
    host.trace.set({ service: 'warehouse', app: 'claude', sequence: 1 });
    await settle(fixture);
    expect(svg.querySelectorAll('[data-active="true"]')).toHaveLength(2);
    expect(svg.querySelector('animateMotion')).toBeNull();
    host.trace.set({ service: 'paused', app: 'claude', sequence: 2 });
    await settle(fixture);
    expect(svg.querySelector('[data-active="true"]')).toBeNull();
  });

  it('plays a requested example once and only replays after another explicit sequence', async () => {
    const begin = vi.fn();
    Object.defineProperty(SVGElement.prototype, 'beginElement', { value: begin, configurable: true });
    try {
      const { fixture, host, svg } = await render();
      host.trace.set({ service: 'warehouse', app: 'claude', sequence: 1 });
      await settle(fixture);
      const motion = svg.querySelector('animateMotion')!;
      expect(motion.getAttribute('repeatCount')).toBe('1');
      expect(motion.getAttribute('dur')).toBe('1680ms');
      expect(motion.getAttribute('path')).toContain('L 400 182');
      expect(begin).toHaveBeenCalledOnce();
      motion.dispatchEvent(new Event('endEvent'));
      expect(svg.querySelector('circle')).toBeNull();
      host.active.set('app:claude');
      await settle(fixture);
      expect(begin).toHaveBeenCalledOnce();
      host.trace.set({ service: 'warehouse', app: 'claude', sequence: 2 });
      await settle(fixture);
      expect(begin).toHaveBeenCalledTimes(2);
    } finally {
      Reflect.deleteProperty(SVGElement.prototype, 'beginElement');
    }
  });

  it('disconnects observers and cancels a queued frame when the page is destroyed', async () => {
    const { fixture } = await render();
    expect(observe).toHaveBeenCalled();
    window.dispatchEvent(new Event('resize'));
    expect(frames.size).toBe(1);
    fixture.destroy();
    expect(disconnect).toHaveBeenCalled();
    expect(frames.size).toBe(0);
    window.dispatchEvent(new Event('resize'));
    expect(frames.size).toBe(0);
  });
});
