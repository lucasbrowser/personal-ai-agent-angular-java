import { Injectable } from '@angular/core';
import { Subject } from 'rxjs';
import { VoiceRealtimeEvent } from './voice.models';

@Injectable({ providedIn: 'root' })
export class VoiceEventsService {
  private readonly subject = new Subject<VoiceRealtimeEvent>();
  readonly events$ = this.subject.asObservable();
  private readonly transcriptParts = new Map<string, string>();

  handleMessage(data: string): void {
    try {
      const event = JSON.parse(data) as Record<string, unknown>;
      switch (event['type']) {
        case 'session.created':
        case 'session.updated':
        case 'input_audio_buffer.speech_started':
          this.subject.next({ kind: 'state', state: 'LISTENING' });
          break;
        case 'input_audio_buffer.speech_stopped':
        case 'response.created':
          this.subject.next({ kind: 'state', state: 'THINKING' });
          break;
        case 'response.output_audio.delta':
        case 'response.output_audio_transcript.delta':
          this.subject.next({ kind: 'state', state: 'SPEAKING' });
          if (event['type'] === 'response.output_audio_transcript.delta' && typeof event['delta'] === 'string') {
            const key = `assistant:${String(event['item_id'] ?? 'current')}`;
            this.transcriptParts.set(key, (this.transcriptParts.get(key) ?? '') + event['delta']);
          }
          break;
        case 'response.output_audio_transcript.done': {
          const key = `assistant:${String(event['item_id'] ?? 'current')}`;
          const text = typeof event['transcript'] === 'string' ? event['transcript'] : this.transcriptParts.get(key);
          if (text) this.subject.next({ kind: 'transcript', role: 'assistant', text });
          this.transcriptParts.delete(key);
          break;
        }
        case 'conversation.item.input_audio_transcription.delta': {
          const key = `user:${String(event['item_id'] ?? 'current')}`;
          if (typeof event['delta'] === 'string') this.transcriptParts.set(key, (this.transcriptParts.get(key) ?? '') + event['delta']);
          break;
        }
        case 'conversation.item.input_audio_transcription.completed': {
          const key = `user:${String(event['item_id'] ?? 'current')}`;
          const text = typeof event['transcript'] === 'string' ? event['transcript'] : this.transcriptParts.get(key);
          if (text) this.subject.next({ kind: 'transcript', role: 'user', text });
          this.transcriptParts.delete(key);
          break;
        }
        case 'response.done':
          this.subject.next({ kind: 'state', state: 'LISTENING' });
          break;
        case 'error': {
          const error = event['error'] as Record<string, unknown> | undefined;
          this.subject.next({ kind: 'error', message: typeof error?.['message'] === 'string' ? error['message'] : 'O serviço de voz encontrou um erro.' });
          break;
        }
      }
    } catch {
      this.subject.next({ kind: 'error', message: 'Não foi possível interpretar um evento de voz.' });
    }
  }

  clear(): void { this.transcriptParts.clear(); }
}
