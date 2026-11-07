import { DestroyRef, Directive, ElementRef, afterRenderEffect, inject, input } from '@angular/core';

/** A configured node on the selected endpoint, not an inferred connection. */
export interface OverviewWireNode {
  readonly id: string;
  readonly enabled: boolean;
  readonly pending?: boolean;
}

/** Increment sequence when the user explicitly requests the same example again. */
export interface OverviewWireTrace {
  readonly service: string;
  readonly app: string;
  readonly sequence: number;
}

interface WireState {
  readonly root: HTMLElement | null;
  readonly endpoint: string | null;
  readonly endpointEnabled: boolean;
  readonly services: readonly OverviewWireNode[];
  readonly apps: readonly OverviewWireNode[];
  readonly activeNode: string | null;
  readonly trace: OverviewWireTrace | null;
}

interface DrawnWire {
  readonly key: string;
  readonly node: OverviewWireNode;
  readonly path: SVGPathElement;
  readonly request: string;
}

const SVG_NS = 'http://www.w3.org/2000/svg';

/**
 * Decorative paths for the canonical setup-map network canvas. The real buttons,
 * radios and status text remain the accessible representation of every connection.
 *
 * Put this directive on an SVG directly inside the map and pass the map element.
 * Mark the service/app wrappers with data-map-service / data-map-app and endpoint
 * labels with data-map-endpoint. Only configured input nodes that are currently
 * rendered receive paths; filtering a node never leaves a phantom connection.
 */
@Directive({
  selector: 'svg[gwOverviewWires]',
  exportAs: 'gwOverviewWires',
  host: { class: 'gw-setup-map__network-wires', 'aria-hidden': 'true', focusable: 'false' },
})
export class GwOverviewWires {
  private readonly svg: SVGSVGElement = inject(ElementRef<SVGSVGElement>).nativeElement;
  private readonly destroyRef = inject(DestroyRef);
  private readonly win = this.svg.ownerDocument.defaultView;

  readonly mapRoot = input<HTMLElement | null>(null);
  readonly endpoint = input<string | null>(null);
  readonly endpointEnabled = input(true);
  readonly services = input<readonly OverviewWireNode[]>([]);
  readonly apps = input<readonly OverviewWireNode[]>([]);
  readonly activeNode = input<string | null>(null);
  readonly trace = input<OverviewWireTrace | null>(null);

  private state: WireState | null = null;
  private frame: number | null = null;
  private pulseTimer: ReturnType<typeof setTimeout> | null = null;
  private observer: ResizeObserver | null = null;
  private observed: readonly Element[] = [];
  private destroyed = false;
  private lastAnimatedTrace: string | null = null;

  constructor() {
    const resize = () => this.schedule();
    const motionPreference = this.win?.matchMedia?.('(prefers-reduced-motion: reduce)');
    this.win?.addEventListener('resize', resize);
    motionPreference?.addEventListener?.('change', resize);
    if (typeof ResizeObserver !== 'undefined') this.observer = new ResizeObserver(resize);
    void this.svg.ownerDocument.fonts?.ready.then(() => this.schedule());
    afterRenderEffect(() => {
      this.state = {
        root: this.mapRoot(), endpoint: this.endpoint(), endpointEnabled: this.endpointEnabled(),
        services: this.services(), apps: this.apps(), activeNode: this.activeNode(), trace: this.trace(),
      };
      if (!this.state.trace) this.lastAnimatedTrace = null;
      this.schedule();
    });
    this.destroyRef.onDestroy(() => {
      this.destroyed = true;
      if (this.frame !== null) this.win?.cancelAnimationFrame(this.frame);
      this.stopPulse();
      this.observer?.disconnect();
      this.win?.removeEventListener('resize', resize);
      motionPreference?.removeEventListener?.('change', resize);
    });
  }

  private schedule(): void {
    if (this.destroyed || this.frame !== null || !this.win) return;
    this.frame = this.win.requestAnimationFrame(() => {
      this.frame = null;
      this.draw();
    });
  }

  private draw(): void {
    this.stopPulse();
    this.svg.replaceChildren();
    const state = this.state;
    if (!state?.root || !this.win) return;
    const root = state.root;
    const endpoints = Array.from(root.querySelectorAll<HTMLElement>('[data-map-endpoint]'));
    const port = endpoints.find((el) => el.getAttribute('data-map-endpoint') === state.endpoint);
    const services = this.elements(root, 'data-map-service');
    const apps = this.elements(root, 'data-map-app');
    this.observe([root, ...(port ? [port] : []), ...services.values(), ...apps.values()]);
    const desktop = this.win.matchMedia?.('(min-width: 1280px)').matches ?? this.win.innerWidth >= 1280;
    if (!desktop || !port) return;
    const origin = root.getBoundingClientRect();
    const endpoint = port.getBoundingClientRect();
    if (!origin.width || !origin.height || !endpoint.width || !endpoint.height) return;
    this.svg.setAttribute('viewBox', `0 0 ${origin.width} ${origin.height}`);
    const wires: DrawnWire[] = [];
    const add = (node: OverviewWireNode, kind: 'service' | 'app', element: HTMLElement | undefined) => {
      if (!element) return;
      const bounds = element.getBoundingClientRect();
      if (!bounds.width || !bounds.height) return;
      const left = kind === 'service';
      const x1 = (left ? bounds.right : endpoint.right) - origin.left;
      const y1 = (left ? bounds.top + bounds.bottom : endpoint.top + endpoint.bottom) / 2 - origin.top;
      const x2 = (left ? endpoint.left : bounds.left) - origin.left;
      const y2 = (left ? endpoint.top + endpoint.bottom : bounds.top + bounds.bottom) / 2 - origin.top;
      const bend = Math.max(0, x2 - x1) * 0.55;
      const path = this.svg.ownerDocument.createElementNS(SVG_NS, 'path');
      path.setAttribute('class', 'gw-setup-map__network-path');
      path.setAttribute('d', `M ${x1} ${y1} C ${x1 + bend} ${y1}, ${x2 - bend} ${y2}, ${x2} ${y2}`);
      const muted = !state.endpointEnabled || !node.enabled;
      path.dataset['node'] = `${kind}:${node.id}`;
      path.dataset['muted'] = String(muted);
      path.dataset['pending'] = String(muted || !!node.pending);
      this.svg.append(path);
      wires.push({ key: `${kind}:${node.id}`, node, path,
        request: `M ${x2} ${y2} C ${x2 - bend} ${y2}, ${x1 + bend} ${y1}, ${x1} ${y1}` });
    };
    state.services.forEach((node) => add(node, 'service', services.get(node.id)));
    state.apps.forEach((node) => add(node, 'app', apps.get(node.id)));
    const trace = state.trace;
    const traceService = trace ? wires.find((wire) => wire.key === `service:${trace.service}` && wire.node.enabled) : undefined;
    const traceApp = trace ? wires.find((wire) => wire.key === `app:${trace.app}` && wire.node.enabled && !wire.node.pending) : undefined;
    const validTrace = state.endpointEnabled && traceService && traceApp;
    const selected = wires.find((wire) => wire.key === state.activeNode && wire.node.enabled && !wire.node.pending);
    wires.forEach((wire) => {
      const related = selected?.key.startsWith('service:')
        ? wire.key.startsWith('app:') && !wire.node.pending
        : selected?.key.startsWith('app:') && wire.key.startsWith('service:');
      const active = validTrace ? wire === traceService || wire === traceApp : !!selected && (wire === selected || related);
      wire.path.dataset['active'] = String(state.endpointEnabled && wire.node.enabled && active);
    });
    if (trace && validTrace) this.animate(trace, traceApp, traceService, root);
  }

  private elements(root: HTMLElement, attribute: string): Map<string, HTMLElement> {
    return new Map(Array.from(root.querySelectorAll<HTMLElement>(`[${attribute}]`))
      .map((element) => [element.getAttribute(attribute) ?? '', element]));
  }

  private observe(elements: readonly Element[]): void {
    if (!this.observer || elements.length === this.observed.length && elements.every((el, i) => el === this.observed[i])) return;
    this.observer.disconnect();
    elements.forEach((el) => this.observer?.observe(el));
    this.observed = elements;
  }

  private animate(trace: OverviewWireTrace, app: DrawnWire, service: DrawnWire, root: HTMLElement): void {
    const key = JSON.stringify(trace);
    if (key === this.lastAnimatedTrace) return;
    this.lastAnimatedTrace = key;
    if (this.win?.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
    const circle = this.svg.ownerDocument.createElementNS(SVG_NS, 'circle');
    const motion = this.svg.ownerDocument.createElementNS(SVG_NS, 'animateMotion');
    // Older renderers retain the static highlighted path if SVG motion is unavailable.
    if (typeof motion.beginElement !== 'function') return;
    const token = this.win?.getComputedStyle(root).getPropertyValue('--gw-motion-duration-slow').trim() ?? '';
    const duration = parseFloat(token) * (token.endsWith('ms') ? 1 : 1_000) * 6;
    if (!Number.isFinite(duration) || duration <= 0) return;
    circle.setAttribute('class', 'gw-setup-map__network-pulse');
    motion.setAttribute('path', `${app.request} ${service.request.replace(/^M /, 'L ')}`);
    motion.setAttribute('dur', `${duration}ms`);
    motion.setAttribute('begin', 'indefinite');
    motion.setAttribute('repeatCount', '1');
    circle.append(motion);
    this.svg.append(circle);
    motion.addEventListener('endEvent', () => circle.remove(), { once: true });
    motion.beginElement();
    this.pulseTimer = setTimeout(() => circle.remove(), duration);
  }

  private stopPulse(): void {
    if (this.pulseTimer !== null) clearTimeout(this.pulseTimer);
    this.pulseTimer = null;
    this.svg.querySelectorAll('.gw-setup-map__network-pulse').forEach((pulse) => pulse.remove());
  }
}
