import { TestBed } from '@angular/core/testing';
import { VoiceEventsService } from './voice-events.service';
import { VoiceRealtimeEvent } from './voice.models';

describe('VoiceEventsService', () => {
  let service: VoiceEventsService;
  let events: VoiceRealtimeEvent[];

  beforeEach(() => {
    service = TestBed.inject(VoiceEventsService);
    events = [];
    service.events$.subscribe(event => events.push(event));
  });

  it('maps committed turns, final transcriptions, context acknowledgements and response status', () => {
    service.handleMessage(JSON.stringify({ event_id: 'e1', type: 'session.created' }));
    service.handleMessage(JSON.stringify({ event_id: 'e2', type: 'input_audio_buffer.speech_stopped', item_id: 'u1', audio_end_ms: 900 }));
    service.handleMessage(JSON.stringify({ event_id: 'e3', type: 'conversation.item.input_audio_transcription.completed', item_id: 'u1', transcript: 'Oi Jarvis' }));
    service.handleMessage(JSON.stringify({ event_id: 'e4', type: 'conversation.item.added', item: { id: 'ctx_123', role: 'system' } }));
    service.handleMessage(JSON.stringify({ event_id: 'e5', type: 'response.created', response: { id: 'r1' } }));
    service.handleMessage(JSON.stringify({ event_id: 'e6', type: 'response.output_audio.delta', delta: 'audio' }));
    service.handleMessage(JSON.stringify({ event_id: 'e7', type: 'response.output_audio_transcript.delta', item_id: 'a1', delta: 'OlÃ¡!' }));
    service.handleMessage(JSON.stringify({ event_id: 'e8', type: 'response.output_audio_transcript.done', response_id: 'r1', item_id: 'a1', transcript: 'OlÃ¡!' }));
    service.handleMessage(JSON.stringify({ event_id: 'e9', type: 'response.done', response: { id: 'r1', status: 'completed' } }));

    expect(events).toEqual([
      { kind: 'state', state: 'LISTENING' },
      { kind: 'state', state: 'THINKING' },
      { kind: 'user-turn-committed', itemId: 'u1', audioEndMs: 900 },
      { kind: 'user-turn-final', eventId: 'e3', itemId: 'u1', text: 'Oi Jarvis' },
      { kind: 'context-item-added', itemId: 'ctx_123' },
      { kind: 'state', state: 'THINKING' },
      { kind: 'response-started', responseId: 'r1' },
      { kind: 'state', state: 'SPEAKING' },
      { kind: 'state', state: 'SPEAKING' },
      { kind: 'transcript', role: 'assistant', text: 'OlÃ¡!' },
      { kind: 'response-finished', responseId: 'r1', status: 'completed' },
    ]);
  });

  it('deduplicates provider events and transcript items, and reports non-success response status', () => {
    const completed = JSON.stringify({ event_id: 'evt-final', type: 'conversation.item.input_audio_transcription.completed', item_id: 'u1', transcript: 'Oi' });
    service.handleMessage(completed);
    service.handleMessage(completed);
    service.handleMessage(JSON.stringify({ event_id: 'evt-final-duplicate', type: 'conversation.item.input_audio_transcription.completed', item_id: 'u1', transcript: 'Oi' }));
    service.handleMessage(JSON.stringify({ event_id: 'evt-done', type: 'response.done', response: { id: 'r1', status: 'incomplete' } }));

    expect(events).toEqual([
      { kind: 'user-turn-final', eventId: 'evt-final', itemId: 'u1', text: 'Oi' },
      { kind: 'turn-notice', message: 'A resposta nÃ£o foi concluÃ­da e nÃ£o serÃ¡ exibida como final.' },
      { kind: 'response-finished', responseId: 'r1', status: 'incomplete' },
    ]);
  });

  it('handles response events arriving out of order and suppresses incomplete assistant transcript as final', () => {
    service.handleMessage(JSON.stringify({ event_id: 'done-first', type: 'response.done', response: { id: 'r2', status: 'completed' } }));
    service.handleMessage(JSON.stringify({ event_id: 'transcript-late', type: 'response.output_audio_transcript.done', response_id: 'r2', item_id: 'a2', transcript: 'Finished answer' }));
    service.handleMessage(JSON.stringify({ event_id: 'done-incomplete', type: 'response.done', response: { id: 'r3', status: 'incomplete' } }));
    service.handleMessage(JSON.stringify({ event_id: 'transcript-incomplete', type: 'response.output_audio_transcript.done', response_id: 'r3', item_id: 'a3', transcript: 'Partial answer' }));

    expect(events).toContainEqual({ kind: 'transcript', role: 'assistant', text: 'Finished answer' });
    expect(events).not.toContainEqual({ kind: 'transcript', role: 'assistant', text: 'Partial answer' });
    expect(events).toContainEqual({ kind: 'turn-notice', message: 'A resposta nÃ£o foi concluÃ­da e nÃ£o serÃ¡ exibida como final.' });
  });

  it('surfaces protocol errors, rejects final transcripts without idempotency identifiers and handles malformed data', () => {
    service.handleMessage(JSON.stringify({ event_id: 'e1', type: 'error', error: { message: 'SessÃ£o encerrada' } }));
    service.handleMessage(JSON.stringify({ type: 'conversation.item.input_audio_transcription.completed', item_id: 'u1', transcript: 'Oi' }));
    service.handleMessage('{invalid');
    expect(events).toEqual([
      { kind: 'error', message: 'SessÃ£o encerrada' },
      { kind: 'error', message: 'A transcriÃ§Ã£o final nÃ£o trouxe os identificadores esperados.' },
      { kind: 'error', message: 'NÃ£o foi possÃ­vel interpretar um evento de voz.' },
    ]);
  });
});
