import { Component, inject } from '@angular/core';
import { ActivatedRoute, RouterLink } from '@angular/router';

/**
 * Temporary stub for the detail route. The real two-pane detail view lands in
 * T052; until then row clicks resolve here so the list-view navigation works.
 */
@Component({
  selector: 'app-message-detail-placeholder',
  standalone: true,
  imports: [RouterLink],
  template: `
    <section class="detail-placeholder">
      <a routerLink="/messages">← Back to messages</a>
      <h1>Message detail</h1>
      <p>Detail coming soon (T052).</p>
      <p class="mono">Message ID: {{ messageId }}</p>
    </section>
  `,
  styles: [
    `
      .detail-placeholder {
        padding: 1.5rem;
      }
      .mono {
        font-family: ui-monospace, 'Consolas', monospace;
        font-size: 0.85em;
      }
    `,
  ],
})
export class MessageDetailPlaceholderComponent {
  private readonly route = inject(ActivatedRoute);
  readonly messageId = this.route.snapshot.paramMap.get('id');
}
