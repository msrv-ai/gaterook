import { ChangeDetectionStrategy, Component, output, viewChild } from '@angular/core';
import { GwMcpAddDialog, McpReviewed } from './dialogs/mcp-add.dialog';
import { GwMcpEndpointDialog, McpServerConnected } from './dialogs/mcp-endpoint.dialog';
import { GwMcpReviewDialog } from './dialogs/mcp-review.dialog';
import { RegistryResult } from './mcp-registry-results';

export type McpSetupSource = 'manual' | 'registry' | 'url';

/** The same reviewed MCP setup stays on whichever page opened it. */
@Component({
  selector: 'gw-mcp-setup-flow',
  imports: [GwMcpAddDialog, GwMcpReviewDialog, GwMcpEndpointDialog],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <dialog gwMcpAddDialog gwDialogStatic (reviewed)="showReviewed($event, true)"></dialog>
    <dialog gwMcpReviewDialog gwDialogStatic (back)="resume($event)" (connected)="connected($event)"></dialog>
    <dialog gwMcpEndpointDialog gwDialogStatic (closed)="changed.emit()"></dialog>
  `,
})
export class GwMcpSetupFlow {
  private readonly add = viewChild.required(GwMcpAddDialog);
  private readonly review = viewChild.required(GwMcpReviewDialog);
  private readonly endpoint = viewChild.required(GwMcpEndpointDialog);
  /** A server was created, or its endpoint assignment step finished. */
  readonly changed = output<void>();

  open(opener: HTMLElement | null, source: McpSetupSource = 'manual'): void {
    this.add().open(opener, source);
  }

  openRegistry(opener: HTMLElement | null, registryName: string): void {
    this.add().open(opener, 'registry', registryName);
  }

  openRegistryResult(opener: HTMLElement | null, result: RegistryResult): void {
    this.add().openResult(opener, result);
  }

  /** Catalog entries begin at the same review without a manual-entry draft to return to. */
  showReviewed(review: McpReviewed, canGoBack = false): void {
    this.review().show(review, canGoBack);
  }

  protected resume(opener: HTMLElement | null): void {
    this.add().resume(opener);
  }

  protected connected(server: McpServerConnected): void {
    this.changed.emit();
    void this.endpoint().show(server);
  }
}
