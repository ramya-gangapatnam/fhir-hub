/**
 * TypeScript mirrors of the Inspector backend DTOs.
 *
 * DEVIATION (T049): these interfaces are hand-written rather than produced by
 * `openapi-generator-cli` (typescript-angular). They are kept in sync by hand
 * with contracts/inspector.openapi.yaml.
 */

export type MessageStatus =
  | 'RECEIVED'
  | 'VALIDATING'
  | 'TRANSFORMED'
  | 'PERSISTED'
  | 'FAILED';

export const MESSAGE_STATUSES: readonly MessageStatus[] = [
  'RECEIVED',
  'VALIDATING',
  'TRANSFORMED',
  'PERSISTED',
  'FAILED',
];

export interface MessageListItem {
  messageId: string;
  msh10ControlId: string;
  sendingApplication: string;
  receivedAtUtc: string;
  status: MessageStatus;
  lastErrorCode?: string | null;
}

export interface PageInfo {
  limit: number;
  offset: number;
  total: number;
}

export interface MessageListPage {
  messages: MessageListItem[];
  page: PageInfo;
}

export interface ValidationError {
  errorCode: string;
  segmentField?: string | null;
  summarySafe: string;
}

export interface FhirResources {
  patient: Record<string, unknown>;
  encounter: Record<string, unknown>;
}

export interface MessageDetail {
  messageId: string;
  msh10ControlId: string;
  sendingApplication: string;
  receivedAtUtc: string;
  status: MessageStatus;
  rawHl7: string;
  validationErrors: ValidationError[];
  fhirResources?: FhirResources | null;
  correlationId: string;
}

export interface ReplayResult {
  messageId: string;
  previousStatus: MessageStatus;
  newStatus: MessageStatus;
  validationErrors?: ValidationError[];
  replayedAtUtc: string;
  correlationId: string;
}

export interface ErrorEnvelope {
  error: {
    code: string;
    message: string;
    location?: string | null;
    correlationId: string;
  };
}
