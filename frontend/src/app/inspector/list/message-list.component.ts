import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { Router } from '@angular/router';
import { InspectorService } from '../../api/inspector.service';
import {
  MESSAGE_STATUSES,
  MessageListItem,
  MessageStatus,
} from '../../api/inspector.models';

@Component({
  selector: 'app-message-list',
  standalone: true,
  imports: [FormsModule, DatePipe],
  templateUrl: './message-list.component.html',
  styleUrl: './message-list.component.scss',
})
export class MessageListComponent implements OnInit {
  private readonly inspector = inject(InspectorService);
  private readonly router = inject(Router);

  readonly statuses = MESSAGE_STATUSES;
  readonly messages = signal<MessageListItem[]>([]);
  readonly total = signal(0);
  readonly loading = signal(false);
  readonly error = signal<string | null>(null);

  selectedStatuses: MessageStatus[] = [];
  msh10 = '';

  ngOnInit(): void {
    this.search();
  }

  search(): void {
    this.loading.set(true);
    this.error.set(null);
    this.inspector
      .listMessages({
        status: this.selectedStatuses,
        msh10: this.msh10.trim() || undefined,
      })
      .subscribe({
        next: (page) => {
          this.messages.set(page.messages);
          this.total.set(page.page.total);
          this.loading.set(false);
        },
        error: () => {
          this.error.set('Failed to load messages.');
          this.loading.set(false);
        },
      });
  }

  toggleStatus(status: MessageStatus, checked: boolean): void {
    this.selectedStatuses = checked
      ? [...this.selectedStatuses, status]
      : this.selectedStatuses.filter((s) => s !== status);
  }

  openMessage(messageId: string): void {
    void this.router.navigate(['/messages', messageId]);
  }
}
