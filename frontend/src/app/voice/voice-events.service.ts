import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';
import { VoiceRealtimeEvent } from './voice.models';

@Injectable({ providedIn: 'root' })
export class VoiceEventsService {
  private readonly subject = new Subject<VoiceRealtimeEvent>();
  readonly events$ = this.subject.asObservable();
  private readonly transcriptParts = new Map<string, string>();
  private readonly seenEventIds = new Set<string>();
  private readonly emittedTranscriptItems = new Set<string>();
  private readonly assistantTranscripts = new Map<string, Array<{ itemId: string; text: string }>>();
  private readonly responseStatuses = new Map<string, 'completed' | 'cancelled' | 'failed' | 'incomplete' | 'unknown'>();

  handleMessage(data: string): void {
    try {
      const event = JSON.parse(data) as Record<string, unknown>;
      const eventId = typeof event['event_id'] === 'string' ? event['event_id'] : undefined;
      if (eventId && this.seenEventIds.has(eventId)) return;
      if (eventId) this.remember(this.seenEventIds, eventId);

      switch (event['type']) {
        case 'session.created':
        case 'session.updated':
        case 'input_audio_buffer.speech_started':
          this.subject.next({ kind: 'state', state: 'LISTENING' });
          break;
        case 'input_audio_buffer.speech_stopped':
          this.subject.next({ kind: 'state', state: 'THINKING' });
          this.emitCommit(event['item_id'], event['audio_end_ms']);
          break;
        case 'input_audio_buffer.committed':
          this.emitCommit(event['item_id']);
          break;
        case 'conversation.item.created':
        case 'conversation.item.added':
        case 'conversation.item.done': {
          const item = event['item'] as Record<string, unknown> | undefined;
          const itemId = typeof item?.['id'] === 'string' ? item['id'] : undefined;
          if (itemId?.startsWith('ctx_') && item?.['type'] === 'message'
              && (item?.['role'] === 'user' || item?.['role'] === 'system')) {
            const previous = event['previous_item_id'];
            this.subject.next({ kind: 'context-item-added', itemId,
              previousItemId: typeof previous === 'string' ? previous : previous == null ? null : '__invalid__' });
          } else if (itemId && item?.['role'] === 'user') {
            this.emitCommit(itemId);
          }
          break;
        }
        case 'conversation.item.retrieved': {
          const item = event['item'] as Record<string, unknown> | undefined;
          const itemId = typeof item?.['id'] === 'string' ? item['id'] : undefined;
          if (itemId?.startsWith('ctx_') && item?.['type'] === 'message'
              && (item?.['role'] === 'user' || item?.['role'] === 'system')) {
            this.subject.next({ kind: 'context-item-retrieved', itemId });
          }
          break;
        }
        case 'response.created': {
          const response = event['response'] as Record<string, unknown> | undefined;
          this.subject.next({ kind: 'state', state: 'THINKING' });
          if (typeof response?.['id'] === 'string' && !this.responseStatuses.has(response['id'])) {
            this.subject.next({ kind: 'response-started', responseId: response['id'] });
          }
          break;
        }
        case 'response.output_audio.delta':
        case 'response.output_audio_transcript.delta':
          this.subject.next({ kind: 'state', state: 'SPEAKING' });
          if (event['type'] === 'response.output_audio_transcript.delta' && typeof event['delta'] === 'string') {
            const key = `assistant:${String(event['item_id'] ?? 'current')}`;
            this.transcriptParts.set(key, (this.transcriptParts.get(key) ?? '') + event['delta']);
          }
          break;
        case 'response.output_audio_transcript.done': {
          const itemId = typeof event['item_id'] === 'string' ? event['item_id'] : undefined;
          const key = `assistant:${String(itemId ?? 'current')}`;
          const text = typeof event['transcript'] === 'string' ? event['transcript'] : this.transcriptParts.get(key);
          const responseId = typeof event['response_id'] === 'string' ? event['response_id'] : undefined;
          if (text?.trim() && itemId && responseId && !this.emittedTranscriptItems.has(`assistant:${itemId}`)) {
            const status = this.responseStatuses.get(responseId);
            if (status === 'completed') this.emitAssistantTranscript(itemId, text);
            else if (!status) {
              const finals = this.assistantTranscripts.get(responseId) ?? [];
              finals.push({ itemId, text });
              this.assistantTranscripts.set(responseId, finals);
            }
          }
          this.transcriptParts.delete(key);
          break;
        }
        case 'conversation.item.input_audio_transcription.delta': {
          const key = `user:${String(event['item_id'] ?? 'current')}`;
          if (typeof event['delta'] === 'string') this.transcriptParts.set(key, (this.transcriptParts.get(key) ?? '') + event['delta']);
          break;
        }
        case 'conversation.item.input_audio_transcription.completed': {
          const itemId = typeof event['item_id'] === 'string' ? event['item_id'] : undefined;
          const text = typeof event['transcript'] === 'string' ? event['transcript'] : undefined;
          const key = `user:${String(itemId ?? 'current')}`;
          this.transcriptParts.delete(key);
          if (eventId && itemId && text !== undefined
              && !this.emittedTranscriptItems.has(`user:${itemId}`)) {
            this.remember(this.emittedTranscriptItems, `user:${itemId}`);
            this.subject.next({ kind: 'user-turn-final', eventId, itemId, text });
          } else if (!eventId || !itemId || text === undefined) {
            this.subject.next({ kind: 'error', message: 'A transcriÃ§Ã£o final nÃ£o trouxe os identificadores esperados.' });
          }
          break;
        }
        case 'conversation.item.input_audio_transcription.failed': {
          if (typeof event['item_id'] === 'string') {
            this.subject.next({ kind: 'user-transcription-failed', itemId: event['item_id'],
              message: 'NÃ£o foi possÃ­vel transcrever o Ã¡udio do turno.' });
          }
          break;
        }
        case 'response.done': {
          const response = event['response'] as Record<string, unknown> | undefined;
          const rawStatus = response?.['status'];
          const status = rawStatus === 'completed' || rawStatus === 'cancelled'
            || rawStatus === 'failed' || rawStatus === 'incomplete' ? rawStatus : 'unknown';
          const responseId = typeof response?.['id'] === 'string' ? response['id'] : undefined;
          if (responseId) {
            if (this.responseStatuses.has(responseId)) break;
            this.responseStatuses.set(responseId, status);
            const finals = this.assistantTranscripts.get(responseId) ?? [];
            if (status === 'completed') finals.forEach(final => this.emitAssistantTranscript(final.itemId, final.text));
            else this.subject.next({ kind: 'turn-notice', message: 'A resposta nÃ£o foi concluÃ­da e nÃ£o serÃ¡ exibida como final.' });
            this.assistantTranscripts.delete(responseId);
          }
          this.subject.next({ kind: 'response-finished',
            responseId, status });
          break;
        }
        case 'error': {
          const error = event['error'] as Record<string, unknown> | undefined;
          this.subject.next({ kind: 'error', message: typeof error?.['message'] === 'string'
            ? error['message'] : 'O serviÃ§o de voz encontrou um erro.' });
          break;
        }
      }
    } catch {
      this.subject.next({ kind: 'error', message: 'NÃ£o foi possÃ­vel interpretar um evento de voz.' });
    }
  }

  publishTranscript(role: 'user' | 'assistant', text: string): void {
    this.subject.next({ kind: 'transcript', role, text });
  }

  publishState(state: 'LISTENING' | 'THINKING' | 'SPEAKING'): void {
    this.subject.next({ kind: 'state', state });
  }

  publishNotice(message: string): void { this.subject.next({ kind: 'turn-notice', message }); }
  publishError(message: string): void { this.subject.next({ kind: 'error', message }); }

  clear(): void {
    this.transcriptParts.clear();
    this.seenEventIds.clear();
    this.emittedTranscriptItems.clear();
    this.assistantTranscripts.clear();
    this.responseStatuses.clear();
  }

  private emitCommit(itemId: unknown, audioEndMs?: unknown): void {
    if (typeof itemId !== 'string' || !itemId) return;
    this.subject.next({ kind: 'user-turn-committed', itemId,
      ...(typeof audioEndMs === 'number' && Number.isFinite(audioEndMs) ? { audioEndMs } : {}) });
  }

  private remember(set: Set<string>, value: string): void {
    set.add(value);
    if (set.size > 2_000) set.delete(set.values().next().value!);
  }

  private emitAssistantTranscript(itemId: string, text: string): void {
    if (this.emittedTranscriptItems.has(`assistant:${itemId}`)) return;
    this.remember(this.emittedTranscriptItems, `assistant:${itemId}`);
    this.subject.next({ kind: 'transcript', role: 'assistant', text });
  }
}
