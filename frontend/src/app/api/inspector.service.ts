import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';
import { RUNTIME_CONFIG } from '../core/runtime-config';
import {
  MessageDetail,
  MessageListPage,
  MessageStatus,
  ReplayResult,
} from './inspector.models';

export interface ListMessagesQuery {
  status?: MessageStatus[];
  msh10?: string;
  limit?: number;
  offset?: number;
}

/**
 * Typed client for the three Inspector endpoints. Hand-written (T049 deviation)
 * in place of a generated typescript-angular client. Auth and correlation-id
 * headers are added by the HTTP interceptors, not here.
 */
@Injectable({ providedIn: 'root' })
export class InspectorService {
  private readonly http = inject(HttpClient);
  private readonly baseUrl = inject(RUNTIME_CONFIG).apiBaseUrl;

  listMessages(query: ListMessagesQuery = {}): Observable<MessageListPage> {
    let params = new HttpParams();
    for (const status of query.status ?? []) {
      params = params.append('status', status);
    }
    if (query.msh10) {
      params = params.set('msh10', query.msh10);
    }
    if (query.limit != null) {
      params = params.set('limit', query.limit);
    }
    if (query.offset != null) {
      params = params.set('offset', query.offset);
    }
    return this.http.get<MessageListPage>(`${this.baseUrl}/inspector/messages`, {
      params,
    });
  }

  getMessageDetail(messageId: string): Observable<MessageDetail> {
    return this.http.get<MessageDetail>(
      `${this.baseUrl}/inspector/messages/${encodeURIComponent(messageId)}`,
    );
  }

  replayMessage(messageId: string): Observable<ReplayResult> {
    return this.http.post<ReplayResult>(
      `${this.baseUrl}/inspector/messages/${encodeURIComponent(messageId)}/replay`,
      null,
    );
  }
}
