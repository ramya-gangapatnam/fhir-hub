import { Routes } from '@angular/router';
import { MessageListComponent } from './inspector/list/message-list.component';
import { MessageDetailPlaceholderComponent } from './inspector/detail/message-detail-placeholder.component';

export const routes: Routes = [
  { path: '', redirectTo: 'messages', pathMatch: 'full' },
  { path: 'messages', component: MessageListComponent },
  { path: 'messages/:id', component: MessageDetailPlaceholderComponent },
  { path: '**', redirectTo: 'messages' },
];
