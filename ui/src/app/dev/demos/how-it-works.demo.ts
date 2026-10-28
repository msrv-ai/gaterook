import { ChangeDetectionStrategy, Component } from '@angular/core';
import { RouterLink } from '@angular/router';
import { GwHowItWorks, GwHowItWorksStep } from '../../kit/elements/how-it-works/how-it-works';
import { GwDemoHeader } from '../demo-header';
import { kitElement } from '../elements';

/** Dev only: design/elements/how-it-works.html. */
@Component({
  selector: 'gw-how-it-works-demo',
  imports: [GwDemoHeader, GwHowItWorks, GwHowItWorksStep, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  host: { class: 'gw-page', id: 'main', tabindex: '-1', role: 'main' },
  template: `
    <header gwDemoHeader [title]="el.title" [description]="el.description"></header>

    <section class="gw-section" aria-labelledby="s-secrets">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-secrets">Secrets</h2>
        <p class="gw-section__description">Top of Secrets, expanded. From lg the steps run across with a chevron between them; narrower windows list them.</p>
      </header>
      <section gwHowItWorks summary="The agent never sees a secret value, only the result of the call." stepsLabel="What happens to a secret">
        <li gwHowItWorksStep icon="key-round" heading="Store or reference">Paste the value here, or point to Vault, GCP Secret Manager or AWS Secrets Manager.</li>
        <li gwHowItWorksStep icon="lock-keyhole" heading="Encrypted with your master key">AES-256-GCM, with the key in <code class="gw-code">~/.gaterook/master.keyset</code>, outside the database.</li>
        <li gwHowItWorksStep icon="lock-keyhole-open" heading="Decrypted only inside the connector, at call time">The value lives in memory for one call and never leaves the connector.</li>
        <li gwHowItWorksStep icon="eye-off" heading="Agent gets the result, masked">Secrets and PII in the output become <code class="gw-code gw-code--secret">‹masked›</code> before the agent sees it.</li>
        <p class="gw-how-it-works__more" gwHowItWorksMore><a class="gw-link gw-text-small" routerLink="/secrets" fragment="master-key">Learn more about the master key and rotation</a></p>
      </section>
    </section>

    <section class="gw-section" aria-labelledby="s-connectors">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-connectors">Connectors, three steps</h2>
        <p class="gw-section__description">Top of Connectors: three ways to add a resource type. Three steps share the row in equal columns; the Learn more link is optional and left out here.</p>
      </header>
      <section gwHowItWorks summary="Add a resource type without rebuilding the gateway. Every new tool still goes through tokens, policies and approvals." stepsLabel="Ways to add a resource type">
        <li gwHowItWorksStep icon="file-code" heading="Write a template">SQL or an HTTP call with typed parameters becomes a tool. No code, no restart.</li>
        <li gwHowItWorksStep icon="puzzle" heading="Install a plugin">A Java plugin adds a new resource type with its own tools. Its card says whether it is signed.</li>
        <li gwHowItWorksStep icon="plug" heading="Add an MCP server">Put a server you already run behind the gateway. Its tools get modes, approvals and audit like any other.</li>
      </section>
    </section>

    <section class="gw-section" aria-labelledby="s-auth">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-auth">Authorization</h2>
        <p class="gw-section__description">Settings, Authorization: <code class="gw-code">gw-how-it-works--vertical</code> keeps the list inside the narrow settings column at any width.</p>
      </header>
      <div class="gw-grid gw-grid--2">
        <section gwHowItWorks vertical summary="Every client is tied to one endpoint and can be cut off at once." stepsLabel="How a client is authorized">
          <li gwHowItWorksStep icon="log-in" heading="Client gets a token or signs in with OAuth">Desktop clients use a bearer token; claude.ai signs in with OAuth 2.1 through the tunnel.</li>
          <li gwHowItWorksStep icon="waypoints" heading="Token is bound to one endpoint">A token for <code class="gw-code">dev</code> cannot call <code class="gw-code">stage-readonly</code>. Named tokens last 184 days by default; Claude Desktop and Codex get 15-minute ones on the fly.</li>
          <li gwHowItWorksStep icon="shield" heading="Policy decides allow / approval / deny">Each call is checked against the tool's mode and the environment's limits.</li>
          <li gwHowItWorksStep icon="ban" heading="Revoke any time">The next call with a revoked token is refused. Nothing to restart.</li>
          <p class="gw-how-it-works__more" gwHowItWorksMore><a class="gw-link gw-text-small" routerLink="/settings/authorization">Learn more about tokens and OAuth</a></p>
        </section>
      </div>
    </section>

    <section class="gw-section" aria-labelledby="s-clients">
      <header class="gw-section__header">
        <h2 class="gw-section__title" id="s-clients">Clients, collapsed</h2>
        <p class="gw-section__description">Top of Clients after the user folded it: the title and the one-line summary stay. Open it with the chevron button.</p>
      </header>
      <section gwHowItWorks [expanded]="false" summary="Connect writes one entry into the client's config, with a backup first." stepsLabel="What one-click Connect does">
        <li gwHowItWorksStep icon="scan-search" heading="We find the client">Claude Desktop, Codex, Cursor and Antigravity are found where they keep their config.</li>
        <li gwHowItWorksStep icon="file-diff" heading="Show what changes in its config">You see the exact diff, for example in <code class="gw-code">~/.codex/config.toml</code>, before anything is written.</li>
        <li gwHowItWorksStep icon="save" heading="Back up, then write">The current file is copied next to it, then one entry is added. Other MCP servers stay untouched.</li>
        <li gwHowItWorksStep icon="unplug" heading="Disconnect removes the entry and revokes the token">The config goes back to how it was, and the client's token stops working.</li>
      </section>
    </section>
  `,
})
export class HowItWorksDemo {
  protected readonly el = kitElement('how-it-works');
}
