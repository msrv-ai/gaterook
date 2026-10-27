import { Injectable, inject } from '@angular/core';
import { Title } from '@angular/platform-browser';
import { RouterStateSnapshot, TitleStrategy } from '@angular/router';

/** The product name every window title ends with (kit pages: `<title>Overview · MCP Gateway</title>`). */
export const PRODUCT = 'MCP Gateway';

/**
 * `<Section> · MCP Gateway` from the deepest route's `title`, `MCP Gateway` alone without one.
 *
 * A screen that covers the window without being a route (core-starting: `Core starting · MCP Gateway`, as the kit's
 * page) takes the title with {@link cover}; the route's title comes back when it is gone. Provided with
 * `useExisting` so the router and the shell share this one instance.
 */
@Injectable({ providedIn: 'root' })
export class GwTitleStrategy extends TitleStrategy {
  private readonly title = inject(Title);
  private routeTitle = PRODUCT;
  private covering: string | null = null;

  override updateTitle(snapshot: RouterStateSnapshot): void {
    const page = this.buildTitle(snapshot);
    this.routeTitle = page ? `${page} · ${PRODUCT}` : PRODUCT;
    this.apply();
  }

  /** `name` covers the route's title until `cover(null)`. */
  cover(name: string | null): void {
    this.covering = name ? `${name} · ${PRODUCT}` : null;
    this.apply();
  }

  private apply(): void {
    this.title.setTitle(this.covering ?? this.routeTitle);
  }
}
