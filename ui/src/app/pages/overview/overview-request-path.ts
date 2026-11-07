import { ChangeDetectionStrategy, Component, input, output } from '@angular/core';
import { EndpointSummary } from '../../core/api/endpoints.api';
import { GwIcon } from '../../kit/icons';
import { MapApp, MapConnection } from './overview-map-view';

/** An illustrated configured route. Animation never sends a request or implies a successful call. */
@Component({
  selector: 'gw-overview-request-path',
  imports: [GwIcon],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './overview-request-path.html',
  styleUrl: './overview-request-path.scss',
})
export class OverviewRequestPath {
  readonly app = input.required<MapApp>();
  readonly service = input.required<MapConnection>();
  readonly endpoint = input.required<EndpointSummary>();
  readonly sequence = input.required<number>();
  readonly serviceMark = input<string | null>(null);
  readonly cleared = output<void>();
}
